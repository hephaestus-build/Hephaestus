package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts JDBC preparations, including SQL that does not pass through Hibernate. A measurement counts only the data
 * sources that this counter wraps: those of its own Spring context, not those of another cached context.
 */
public class SqlStatementCounter implements BeanPostProcessor {
    /** The measurement on this thread, with the counter that owns it. */
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();

    /** A worker thread whose preparations a test measures from its own thread. */
    private final AtomicReference<@Nullable Worker> worker = new AtomicReference<>();

    /** What an owner policy reads on the connection, and whether the transaction may write. */
    private static final String OWNER =
            "SELECT public.current_owner_account(), current_setting('transaction_read_only') = 'on'";

    /** Counts only preparations on this thread during the block; child threads are not included. */
    public <T> Measurement<T> measure(Supplier<T> read) {
        return measure(read, false);
    }

    /** Like {@link #measure}, and reads for each statement the owner and read-only state of its transaction. */
    public <T> Measurement<T> measureOwners(Supplier<T> read) {
        return measure(read, true);
    }

    /**
     * The preparations of the named thread while the block runs on this one, each with the owner and read-only state of
     * its transaction. A worker thread runs reads that the block only triggers.
     */
    public <T> Measurement<T> measureOwnersOn(String thread, Supplier<T> block) {
        Counter counter = new Counter(true);
        if (!worker.compareAndSet(null, new Worker(thread, counter))) {
            throw new IllegalStateException("Only one worker thread can be measured at a time");
        }
        try {
            T value = block.get();
            return new Measurement<>(value, List.copyOf(counter.prepared));
        } finally {
            worker.set(null);
        }
    }

    private <T> Measurement<T> measure(Supplier<T> read, boolean owners) {
        if (ACTIVE.get() != null) throw new IllegalStateException("SQL measurements cannot be nested");
        Counter counter = new Counter(owners);
        ACTIVE.set(new Active(this, counter));
        try {
            T value = read.get();
            return new Measurement<>(value, List.copyOf(counter.prepared));
        } finally {
            ACTIVE.remove();
        }
    }

    public record Measurement<T>(T value, List<Prepared> prepared) {
        public List<String> sql() {
            return prepared.stream().map(Prepared::sql).toList();
        }

        public long statements() {
            return prepared.size();
        }
    }

    /**
     * One prepared statement. When the measurement reads owners: the owner that {@code public.current_owner_account()}
     * returned on its connection just before, and whether its transaction was read-only. Otherwise no owner and not
     * read-only.
     */
    public record Prepared(String sql, @Nullable Long owner, boolean readOnly) {}

    private record Worker(String thread, Counter counter) {}

    private record Active(SqlStatementCounter owner, Counter counter) {}

    private static class Counter {
        private final boolean owners;
        private final List<Prepared> prepared = new CopyOnWriteArrayList<>();

        Counter(boolean owners) {
            this.owners = owners;
        }

        void add(String sql, Connection connection) throws SQLException {
            if (!owners) {
                prepared.add(new Prepared(sql, null, false));
                return;
            }
            try (Statement probe = connection.createStatement();
                    ResultSet row = probe.executeQuery(OWNER)) {
                row.next();
                long owner = row.getLong(1);
                prepared.add(new Prepared(sql, row.wasNull() ? null : owner, row.getBoolean(2)));
            }
        }
    }

    private @Nullable Counter current() {
        Active measuring = ACTIVE.get();
        if (measuring != null) return measuring.owner().equals(this) ? measuring.counter() : null;
        Worker measured = worker.get();
        return measured != null
                        && measured.thread().equals(Thread.currentThread().getName())
                ? measured.counter()
                : null;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof DataSource source ? new CountingDataSource(source) : bean;
    }

    private class CountingDataSource extends DelegatingDataSource {
        CountingDataSource(DataSource source) {
            super(source);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return count(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return count(super.getConnection(username, password));
        }

        private Connection count(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        Counter counter = current();
                        if (counter != null
                                && method.getName().equals("prepareStatement")
                                && args != null
                                && args.length > 0
                                && args[0] instanceof String sql) {
                            counter.add(sql, connection);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
