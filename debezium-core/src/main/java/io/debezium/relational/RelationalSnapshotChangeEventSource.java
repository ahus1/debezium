/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.ConfigurationDefaults;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.EventDispatcher.SnapshotReceiver;
import io.debezium.pipeline.source.spi.SnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;
import io.debezium.util.Strings;
import io.debezium.util.Threads;
import io.debezium.util.Threads.Timer;

/**
 * Base class for {@link SnapshotChangeEventSource} for relational databases with or without a schema history.
 * <p>
 * A transaction is managed by this base class, sub-classes shouldn't rollback or commit this transaction. They are free
 * to use nested transactions or savepoints, though.
 *
 * @author Gunnar Morling
 */
public abstract class RelationalSnapshotChangeEventSource implements SnapshotChangeEventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(RelationalSnapshotChangeEventSource.class);

    /**
     * Interval for showing a log statement with the progress while scanning a single table.
     */
    private static final Duration LOG_INTERVAL = Duration.ofMillis(10_000);

    private final RelationalDatabaseConnectorConfig connectorConfig;
    private final OffsetContext previousOffset;
    private final JdbcConnection jdbcConnection;
    private final HistorizedRelationalDatabaseSchema schema;
    private final EventDispatcher<TableId> dispatcher;
    protected final Clock clock;
    private final SnapshotProgressListener snapshotProgressListener;

    public RelationalSnapshotChangeEventSource(RelationalDatabaseConnectorConfig connectorConfig,
                                               OffsetContext previousOffset, JdbcConnection jdbcConnection, HistorizedRelationalDatabaseSchema schema,
                                               EventDispatcher<TableId> dispatcher, Clock clock, SnapshotProgressListener snapshotProgressListener) {
        this.connectorConfig = connectorConfig;
        this.previousOffset = previousOffset;
        this.jdbcConnection = jdbcConnection;
        this.schema = schema;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.snapshotProgressListener = snapshotProgressListener;
    }

    public RelationalSnapshotChangeEventSource(RelationalDatabaseConnectorConfig connectorConfig,
                                               OffsetContext previousOffset, JdbcConnection jdbcConnection,
                                               EventDispatcher<TableId> dispatcher, Clock clock, SnapshotProgressListener snapshotProgressListener) {
        this(connectorConfig, previousOffset, jdbcConnection, null, dispatcher, clock, snapshotProgressListener);
    }

    @Override
    public SnapshotResult execute(ChangeEventSourceContext context) throws InterruptedException {
        SnapshottingTask snapshottingTask = getSnapshottingTask(previousOffset);

        // Neither schema nor data require snapshotting
        if (!snapshottingTask.snapshotSchema() && !snapshottingTask.snapshotData()) {
            LOGGER.debug("Skipping snapshotting");
            return SnapshotResult.skipped(previousOffset);
        }

        delaySnapshotIfNeeded(context);

        Connection connection = null;

        final SnapshotContext ctx;
        try {
            ctx = prepare(context);
        }
        catch (Exception e) {
            LOGGER.error("Failed to initialize snapshot context.", e);
            throw new RuntimeException(e);
        }

        try {
            LOGGER.info("Snapshot step 1 - Preparing");
            snapshotProgressListener.snapshotStarted();

            if (previousOffset != null && previousOffset.isSnapshotRunning()) {
                LOGGER.info("Previous snapshot was cancelled before completion; a new snapshot will be taken.");
            }

            connection = jdbcConnection.connection();
            connection.setAutoCommit(false);
            connectionCreated(ctx);

            LOGGER.info("Snapshot step 2 - Determining captured tables");

            // Note that there's a minor race condition here: a new table matching the filters could be created between
            // this call and the determination of the initial snapshot position below; this seems acceptable, though
            determineCapturedTables(ctx);
            snapshotProgressListener.monitoredTablesDetermined(ctx.capturedTables);

            LOGGER.info("Snapshot step 3 - Locking captured tables");

            if (snapshottingTask.snapshotSchema()) {
                lockTablesForSchemaSnapshot(context, ctx);
            }

            LOGGER.info("Snapshot step 4 - Determining snapshot offset");
            determineSnapshotOffset(ctx);

            LOGGER.info("Snapshot step 5 - Reading structure of captured tables");
            readTableStructure(context, ctx);

            if (snapshottingTask.snapshotSchema()) {
                LOGGER.info("Snapshot step 6 - Persisting schema history");

                createSchemaChangeEventsForTables(context, ctx);

                // if we've been interrupted before, the TX rollback will cause any locks to be released
                releaseSchemaSnapshotLocks(ctx);
            }
            else {
                LOGGER.info("Snapshot step 6 - Skipping persisting of schema history");
            }

            if (snapshottingTask.snapshotData()) {
                LOGGER.info("Snapshot step 7 - Snapshotting data");
                createDataEvents(context, ctx);
            }
            else {
                LOGGER.info("Snapshot step 7 - Skipping snapshotting of data");
                ctx.offset.preSnapshotCompletion();
                ctx.offset.postSnapshotCompletion();
            }

            dispatcher.alwaysDispatchHeartbeatEvent(ctx.offset);
            snapshotProgressListener.snapshotCompleted();
            return SnapshotResult.completed(ctx.offset);
        }
        catch (InterruptedException e) {
            LOGGER.warn("Snapshot was interrupted before completion");
            snapshotProgressListener.snapshotAborted();
            throw e;
        }
        catch (RuntimeException e) {
            snapshotProgressListener.snapshotAborted();
            throw e;
        }
        catch (Throwable e) {
            snapshotProgressListener.snapshotAborted();
            throw new RuntimeException(e);
        }
        finally {
            rollbackTransaction(connection);

            LOGGER.info("Snapshot step 8 - Finalizing");

            complete(ctx);
        }
    }

    /**
     * Returns the snapshotting task based on the previous offset (if available) and the connector's snapshotting mode.
     */
    protected abstract SnapshottingTask getSnapshottingTask(OffsetContext previousOffset);

    /**
     * Delays snapshot execution as per the {@link CommonConnectorConfig#SNAPSHOT_DELAY_MS} parameter.
     */
    private void delaySnapshotIfNeeded(ChangeEventSourceContext context) throws InterruptedException {
        Duration snapshotDelay = connectorConfig.getSnapshotDelay();

        if (snapshotDelay.isZero() || snapshotDelay.isNegative()) {
            return;
        }

        Timer timer = Threads.timer(Clock.SYSTEM, snapshotDelay);
        Metronome metronome = Metronome.parker(ConfigurationDefaults.RETURN_CONTROL_INTERVAL, Clock.SYSTEM);

        while (!timer.expired()) {
            if (!context.isRunning()) {
                throw new InterruptedException("Interrupted while awaiting initial snapshot delay");
            }

            LOGGER.info("The connector will wait for {}s before proceeding", timer.remaining().getSeconds());
            metronome.pause();
        }
    }

    /**
     * Prepares the taking of a snapshot and returns an initial {@link SnapshotContext}.
     */
    protected abstract SnapshotContext prepare(ChangeEventSourceContext changeEventSourceContext) throws Exception;

    /**
     * Executes steps which have to be taken just after the database connection is created.
     */
    protected void connectionCreated(SnapshotContext snapshotContext) throws Exception {
    }

    private Stream<TableId> toTableIds(Set<TableId> tableIds, Pattern pattern) {
        return tableIds
                .stream()
                .filter(tid -> pattern.asPredicate().test(tid.toString()))
                .sorted();
    }

    private Set<TableId> sort(Set<TableId> capturedTables) throws Exception {
        String value = connectorConfig.getConfig().getString(RelationalDatabaseConnectorConfig.TABLE_WHITELIST);
        if (value != null) {
            return Strings.listOfRegex(value, Pattern.CASE_INSENSITIVE)
                    .stream()
                    .flatMap(pattern -> toTableIds(capturedTables, pattern))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return capturedTables
                .stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void determineCapturedTables(SnapshotContext ctx) throws Exception {
        Set<TableId> allTableIds = getAllTableIds(ctx);

        Set<TableId> capturedTables = new HashSet<>();

        for (TableId tableId : allTableIds) {
            if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(tableId)) {
                LOGGER.trace("Adding table {} to the list of captured tables", tableId);
                capturedTables.add(tableId);
            }
            else {
                LOGGER.trace("Ignoring table {} as it's not included in the filter configuration", tableId);
            }
        }

        ctx.capturedTables = sort(capturedTables);
    }

    /**
     * Returns all candidate tables; the current filter configuration will be applied to the result set, resulting in
     * the effective set of captured tables.
     */
    protected abstract Set<TableId> getAllTableIds(SnapshotContext snapshotContext) throws Exception;

    /**
     * Locks all tables to be captured, so that no concurrent schema changes can be applied to them.
     */
    protected abstract void lockTablesForSchemaSnapshot(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws Exception;

    /**
     * Determines the current offset (MySQL binlog position, Oracle SCN etc.), storing it into the passed context
     * object. Subsequently, the DB's schema (and data) will be be read at this position. Once the snapshot is
     * completed, a {@link StreamingChangeEventSource} will be set up with this initial position to continue with stream
     * reading from there.
     */
    protected abstract void determineSnapshotOffset(SnapshotContext snapshotContext) throws Exception;

    /**
     * Reads the structure of all the captured tables, writing it to {@link SnapshotContext#tables}.
     */
    protected abstract void readTableStructure(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws Exception;

    /**
     * Releases all locks established in order to create a consistent schema snapshot.
     */
    protected abstract void releaseSchemaSnapshotLocks(SnapshotContext snapshotContext) throws Exception;

    private void createSchemaChangeEventsForTables(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws Exception {
        for (TableId tableId : snapshotContext.capturedTables) {
            if (!sourceContext.isRunning()) {
                throw new InterruptedException("Interrupted while capturing schema of table " + tableId);
            }

            LOGGER.debug("Capturing structure of table {}", tableId);

            Table table = snapshotContext.tables.forTable(tableId);

            if (schema != null) {
                schema.applySchemaChange(getCreateTableEvent(snapshotContext, table));
            }
        }
    }

    /**
     * Creates a {@link SchemaChangeEvent} representing the creation of the given table.
     */
    protected abstract SchemaChangeEvent getCreateTableEvent(SnapshotContext snapshotContext, Table table) throws Exception;

    private void createDataEvents(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws InterruptedException {
        SnapshotReceiver snapshotReceiver = dispatcher.getSnapshotChangeEventReceiver();
        snapshotContext.offset.preSnapshotStart();

        for (Iterator<TableId> tableIdIterator = snapshotContext.capturedTables.iterator(); tableIdIterator.hasNext();) {
            final TableId tableId = tableIdIterator.next();
            snapshotContext.lastTable = !tableIdIterator.hasNext();

            if (!sourceContext.isRunning()) {
                throw new InterruptedException("Interrupted while snapshotting table " + tableId);
            }

            LOGGER.debug("Snapshotting table {}", tableId);

            createDataEventsForTable(sourceContext, snapshotContext, snapshotReceiver, snapshotContext.tables.forTable(tableId));
        }

        snapshotContext.offset.preSnapshotCompletion();
        snapshotReceiver.completeSnapshot();
        snapshotContext.offset.postSnapshotCompletion();
    }

    /**
     * Dispatches the data change events for the records of a single table.
     */
    private void createDataEventsForTable(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext, SnapshotReceiver snapshotReceiver,
                                          Table table)
            throws InterruptedException {

        long exportStart = clock.currentTimeInMillis();
        LOGGER.info("\t Exporting data from table '{}'", table.id());

        final Optional<String> selectStatement = determineSnapshotSelect(snapshotContext, table.id());
        if (!selectStatement.isPresent()) {
            LOGGER.warn("For table '{}' the select statement was not provided, skipping table", table.id());
            return;
        }
        LOGGER.info("\t For table '{}' using select statement: '{}'", table.id(), selectStatement.get());

        try (Statement statement = readTableStatement();
                ResultSet rs = statement.executeQuery(selectStatement.get())) {

            Column[] columns = getColumnsForResultSet(table, rs);
            final int numColumns = table.columns().size();
            long rows = 0;
            Timer logTimer = getTableScanLogTimer();
            snapshotContext.lastRecordInTable = false;

            if (rs.next()) {
                while (!snapshotContext.lastRecordInTable) {
                    if (!sourceContext.isRunning()) {
                        throw new InterruptedException("Interrupted while snapshotting table " + table.id());
                    }

                    rows++;
                    final Object[] row = new Object[numColumns];
                    for (int i = 0; i < numColumns; i++) {
                        row[i] = getColumnValue(rs, i + 1, columns[i]);
                    }

                    snapshotContext.lastRecordInTable = !rs.next();
                    if (logTimer.expired()) {
                        long stop = clock.currentTimeInMillis();
                        LOGGER.info("\t Exported {} records for table '{}' after {}", rows, table.id(),
                                Strings.duration(stop - exportStart));
                        snapshotProgressListener.rowsScanned(table.id(), rows);
                        logTimer = getTableScanLogTimer();
                    }

                    if (snapshotContext.lastTable && snapshotContext.lastRecordInTable) {
                        snapshotContext.offset.markLastSnapshotRecord();
                    }
                    dispatcher.dispatchSnapshotEvent(table.id(), getChangeRecordEmitter(snapshotContext, table.id(), row), snapshotReceiver);
                }
            }
            else if (snapshotContext.lastTable) {
                // if the last table does not contain any records we still need to mark the last processed event as the last one
                snapshotContext.offset.markLastSnapshotRecord();
            }

            LOGGER.info("\t Finished exporting {} records for table '{}'; total duration '{}'", rows,
                    table.id(), Strings.duration(clock.currentTimeInMillis() - exportStart));
            snapshotProgressListener.tableSnapshotCompleted(table.id(), rows);
        }
        catch (SQLException e) {
            throw new ConnectException("Snapshotting of table " + table.id() + " failed", e);
        }
    }

    private Timer getTableScanLogTimer() {
        return Threads.timer(clock, LOG_INTERVAL);
    }

    /**
     * Returns a {@link ChangeRecordEmitter} producing the change records for the given table row.
     */
    protected ChangeRecordEmitter getChangeRecordEmitter(SnapshotContext snapshotContext, TableId tableId, Object[] row) {
        snapshotContext.offset.event(tableId, getClock().currentTime());
        return new SnapshotChangeRecordEmitter(snapshotContext.offset, row, getClock());
    }

    /**
     * Returns a valid query string for the specified table, either given by the user via snapshot select overrides or
     * defaulting to a statement provided by the DB-specific change event source.
     *
     * @param tableId the table to generate a query for
     * @return a valid query string or empty if table will not be snapshotted
     */
    private Optional<String> determineSnapshotSelect(SnapshotContext snapshotContext, TableId tableId) {
        String overriddenSelect = connectorConfig.getSnapshotSelectOverridesByTable().get(tableId);

        // try without catalog id, as this might or might not be populated based on the given connector
        if (overriddenSelect == null) {
            overriddenSelect = connectorConfig.getSnapshotSelectOverridesByTable().get(new TableId(null, tableId.schema(), tableId.table()));
        }

        return overriddenSelect != null ? Optional.of(overriddenSelect) : getSnapshotSelect(snapshotContext, tableId);
    }

    /**
     * Returns the SELECT statement to be used for scanning the given table or empty value if
     * the table will be streamed from but not snapshotted
     */
    // TODO Should it be Statement or similar?
    // TODO Handle override option generically; a problem will be how to handle the dynamic part (Oracle's "... as of
    // scn xyz")
    protected abstract Optional<String> getSnapshotSelect(SnapshotContext snapshotContext, TableId tableId);

    private Column[] getColumnsForResultSet(Table table, ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        Column[] columns = new Column[metaData.getColumnCount()];

        for (int i = 0; i < columns.length; i++) {
            columns[i] = table.columnWithName(metaData.getColumnName(i + 1));
        }

        return columns;
    }

    protected Object getColumnValue(ResultSet rs, int columnIndex, Column column) throws SQLException {
        return rs.getObject(columnIndex);
    }

    private Statement readTableStatement() throws SQLException {
        int fetchSize = connectorConfig.getSnapshotFetchSize();
        Statement statement = jdbcConnection.connection().createStatement(); // the default cursor is FORWARD_ONLY
        statement.setFetchSize(fetchSize);
        return statement;
    }

    /**
     * Completes the snapshot, doing any required clean-up (resource disposal etc.).
     * @param snapshotContext snapshot context
     */
    protected abstract void complete(SnapshotContext snapshotContext);

    private void rollbackTransaction(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            }
            catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Mutable context which is populated in the course of snapshotting.
     */
    public static class SnapshotContext implements AutoCloseable {
        public final String catalogName;
        public final Tables tables;

        public Set<TableId> capturedTables;
        public OffsetContext offset;
        public boolean lastTable;
        public boolean lastRecordInTable;

        public SnapshotContext(String catalogName) throws SQLException {
            this.catalogName = catalogName;
            this.tables = new Tables();
        }

        @Override
        public void close() throws Exception {
        }
    }

    /**
     * A configuration describing the task to be performed during snapshotting.
     */
    public static class SnapshottingTask {

        private final boolean snapshotSchema;
        private final boolean snapshotData;

        public SnapshottingTask(boolean snapshotSchema, boolean snapshotData) {
            this.snapshotSchema = snapshotSchema;
            this.snapshotData = snapshotData;
        }

        /**
         * Whether data (rows in captured tables) should be snapshotted.
         */
        public boolean snapshotData() {
            return snapshotData;
        }

        /**
         * Whether the schema of captured tables should be snapshotted.
         */
        public boolean snapshotSchema() {
            return snapshotSchema;
        }

        @Override
        public String toString() {
            return "SnapshottingTask [snapshotSchema=" + snapshotSchema + ", snapshotData=" + snapshotData + "]";
        }
    }

    protected Clock getClock() {
        return clock;
    }
}
