package org.embulk.input.jdbc;

import java.util.List;
import java.util.Properties;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.embulk.config.CommitReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Column;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;
import org.embulk.spi.Exec;
import org.embulk.input.jdbc.getter.ColumnGetter;
import org.embulk.input.jdbc.getter.ColumnGetterFactory;
import org.embulk.input.jdbc.JdbcInputConnection.BatchSelect;

public abstract class AbstractJdbcInputPlugin
        implements InputPlugin
{
    protected final Logger logger = Exec.getLogger(getClass());

    public interface PluginTask extends Task
    {
        @Config("options")
        @ConfigDefault("{}")
        public Properties getOptions();

        @Config("table")
        @ConfigDefault("null")
        public Optional<String> getTable();

        @Config("query")
        @ConfigDefault("null")
        public Optional<String> getQuery();

        @Config("select")
        @ConfigDefault("null")
        public Optional<String> getSelect();

        @Config("where")
        @ConfigDefault("null")
        public Optional<String> getWhere();

        @Config("order_by")
        @ConfigDefault("null")
        public Optional<String> getOrderBy();

        //// TODO See bellow.
        //@Config("last_value")
        //@ConfigDefault("null")
        //public Optional<String> getLastValue();

        // TODO limit_value is necessary to make sure repeated bulk load transactions
        //      don't a same record twice or miss records when the column
        //      specified at order_by parameter is not unique.
        //      For example, if the order_by column is "timestamp created_at"
        //      column whose precision is second, the table can include multiple
        //      records with the same created_at time. At the first bulk load
        //      transaction, it loads a record with created_at=2015-01-02 00:00:02.
        //      Then next transaction will use WHERE created_at > '2015-01-02 00:00:02'.
        //      However, if another record with created_at=2014-01-01 23:59:59 is
        //      inserted between the 2 transactions, the new record will be skipped.
        //      To prevent this scenario, we want to specify
        //      limit_value=2015-01-02 00:00:00 (exclusive). With this way, as long as
        //      a transaction runs after 2015-01-02 00:00:00 + some minutes, we don't
        //      skip records. Ideally, to automate the scheduling, we want to set
        //      limit_value="today".
        //
        //@Config("limit_value")
        //@ConfigDefault("null")
        //public Optional<String> getLimitValue();

        //// TODO probably limit_rows is unnecessary as long as this has
        //        supports parallel execution (partition_by option) and resuming.
        //@Config("limit_rows")
        //@ConfigDefault("null")
        //public Optional<Integer> getLimitRows();

        @Config("fetch_rows")
        @ConfigDefault("10000")
        // TODO set minimum number
        public int getFetchRows();

        // TODO parallel execution using "partition_by" config

        public JdbcSchema getQuerySchema();
        public void setQuerySchema(JdbcSchema schema);

        @ConfigInject
        public BufferAllocator getBufferAllocator();
    }

    // for subclasses to add @Config
    protected Class<? extends PluginTask> getTaskClass()
    {
        return PluginTask.class;
    }

    protected abstract JdbcInputConnection newConnection(PluginTask task) throws SQLException;

    protected ColumnGetterFactory newColumnGetterFactory(PluginTask task) throws SQLException
    {
        return new ColumnGetterFactory();
    }

    @Override
    public ConfigDiff transaction(ConfigSource config,
            InputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(getTaskClass());

        //if (task.getLastValue().isPresent() && !task.getOrderBy().isPresent()) {
        //    throw new ConfigException("order_by parameter must be set if last_value parameter is set");
        //}

        Schema schema;
        try (JdbcInputConnection con = newConnection(task)) {
            schema = setupTask(con, task);
        } catch (SQLException ex) {
            throw Throwables.propagate(ex);
        }

        return buildNextConfigDiff(task, control.run(task.dump(), schema, 1));
    }

    private Schema setupTask(JdbcInputConnection con, PluginTask task) throws SQLException
    {
        // build SELECT query and gets schema of its result
        JdbcSchema querySchema = con.getSchemaOfQuery(getQuery(task, con));
        task.setQuerySchema(querySchema);

        ColumnGetterFactory factory = newColumnGetterFactory(task);
        ImmutableList.Builder<Column> columns = ImmutableList.builder();
        for (int i = 0; i < querySchema.getCount(); i++) {
            columns.add(new Column(i,
                    querySchema.getColumnName(i),
                    factory.newColumnGetter(querySchema.getColumn(i)).getToType()));
        }
        return new Schema(columns.build());
    }

    private String getQuery(PluginTask task, JdbcInputConnection con)
    {
        if (task.getQuery().isPresent()) {
            if (task.getTable().isPresent() || task.getSelect().isPresent() ||
                    task.getWhere().isPresent() || task.getOrderBy().isPresent()) {
                throw new ConfigException("'table', 'select', 'where' and 'order_by' parameters are unnecessary if 'query' parameter is set.");
            }
            return task.getQuery().get();
        } else if (task.getTable().isPresent()) {
            return con.buildSelectQuery(task.getTable().get(), task.getSelect(),
                    task.getWhere(), task.getOrderBy());
        } else {
            throw new ConfigException("'table' parameter is required (if 'query' parameter is not set)");
        }
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            Schema schema, int taskCount,
            InputPlugin.Control control)
    {
        PluginTask task = taskSource.loadTask(getTaskClass());

        // TODO when parallel execution is implemented and enabled, (maybe) order_by
        //      is necessary to resume. transaction() gets the range of order_by
        //      colum and set it to WHERE condition to make the operation deterministic

        return buildNextConfigDiff(task, control.run(taskSource, schema, taskCount));
    }

    public ConfigDiff guess(ConfigSource config)
    {
        return Exec.newConfigDiff();
    }

    protected ConfigDiff buildNextConfigDiff(PluginTask task, List<CommitReport> reports)
    {
        ConfigDiff next = Exec.newConfigDiff();
        // TODO
        //if (task.getOrderBy().isPresent()) {
        //    // TODO when parallel execution is implemented, calculate the max last_value
        //    //      from the all commit reports.
        //    next.set("last_value", reports.get(0).get(JsonNode.class, "last_value"));
        //}
        return next;
    }

    @Override
    public void cleanup(TaskSource taskSource,
            Schema schema, int taskCount,
            List<CommitReport> successCommitReports)
    {
        // do nothing
    }

    @Override
    public CommitReport run(TaskSource taskSource,
            Schema schema, int taskIndex,
            PageOutput output)
    {
        PluginTask task = taskSource.loadTask(getTaskClass());

        JdbcSchema querySchema = task.getQuerySchema();
        BufferAllocator allocator = task.getBufferAllocator();
        PageBuilder pageBuilder = new PageBuilder(allocator, schema, output);

        try {
            List<ColumnGetter> getters = newColumnGetters(task, querySchema);

            try (JdbcInputConnection con = newConnection(task)) {
                try (BatchSelect cursor = con.newSelectCursor(getQuery(task, con), task.getFetchRows())) {
                    while (true) {
                        // TODO run fetch() in another thread asynchronously
                        // TODO retry fetch() if it failed (maybe order_by is required and unique_column(s) option is also required)
                        boolean cont = fetch(cursor, getters, pageBuilder);
                        if (!cont) {
                            break;
                        }
                    }
                }
            }

        } catch (SQLException ex) {
            throw Throwables.propagate(ex);
        }
        pageBuilder.finish();

        CommitReport report = Exec.newCommitReport();
        // TODO
        //if (orderByColumn != null) {
        //    report.set("last_value", lastValue);
        //}
        return report;
    }

    private List<ColumnGetter> newColumnGetters(PluginTask task, JdbcSchema querySchema) throws SQLException
    {
        ColumnGetterFactory factory = newColumnGetterFactory(task);
        ImmutableList.Builder<ColumnGetter> getters = ImmutableList.builder();
        for (JdbcColumn c : querySchema.getColumns()) {
            getters.add(factory.newColumnGetter(c));
        }
        return getters.build();
    }

    private boolean fetch(BatchSelect cursor,
            List<ColumnGetter> getters, PageBuilder pageBuilder) throws SQLException
    {
        ResultSet result = cursor.fetch();
        if (result == null || !result.next()) {
            return false;
        }

        List<Column> columns = pageBuilder.getSchema().getColumns();
        long rows = 0;
        long reportRows = 500;
        do {
            for (int i=0; i < getters.size(); i++) {
                int index = i + 1;  // JDBC column index begins from 1
                getters.get(i).getAndSet(result, index, pageBuilder, columns.get(i));
            }
            pageBuilder.addRecord();
            rows++;
            if (rows % reportRows == 0) {
                logger.info(String.format("Fetched %,d rows.", rows));
                reportRows *= 2;
            }
        } while (result.next());
        return true;
    }

    //// TODO move to embulk.spi.util?
    //private static class ListPageOutput
    //{
    //    public ImmutableList.Builder<Page> pages;
    //
    //    public ListPageOutput()
    //    {
    //        reset();
    //    }
    //
    //    @Override
    //    public void add(Page page)
    //    {
    //        pages.add(page);
    //    }
    //
    //    @Override
    //    public void finish()
    //    {
    //    }
    //
    //    @Override
    //    public void close()
    //    {
    //    }
    //
    //    public List<Page> getPages()
    //    {
    //        return pages.build();
    //    }
    //
    //    public void reset()
    //    {
    //        pages = ImmutableList.builder();
    //    }
    //}
}
