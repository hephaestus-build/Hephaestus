package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Counts JDBC preparations, including SQL that does not pass through Hibernate. */
public class SqlStatementCounter implements BeanPostProcessor {
    private static final ThreadLocal<Counter> ACTIVE = new ThreadLocal<>();

    /** Counts only preparations on this thread during the block; child threads are not included. */
    public <T> Measurement<T> measure(Supplier<T> read) {
        if (ACTIVE.get() != null) throw new IllegalStateException("SQL measurements cannot be nested");
        Counter counter = new Counter();
        ACTIVE.set(counter);
        try {
            T value = read.get();
            return new Measurement<>(value, counter.statements);
        } finally {
            ACTIVE.remove();
        }
    }

    public record Measurement<T>(T value, long statements) {}

    private static class Counter {
        private long statements;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof DataSource source ? new CountingDataSource(source) : bean;
    }

    private static class CountingDataSource extends DelegatingDataSource {
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
                        Counter counter = ACTIVE.get();
                        if (counter != null && method.getName().equals("prepareStatement")) counter.statements++;
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
