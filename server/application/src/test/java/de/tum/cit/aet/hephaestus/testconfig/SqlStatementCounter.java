package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Counts JDBC preparations, including SQL that does not pass through Hibernate. */
public class SqlStatementCounter implements BeanPostProcessor {
    private final AtomicLong statements = new AtomicLong();

    public void reset() {
        statements.set(0);
    }

    public long count() {
        return statements.get();
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
                        if (method.getName().equals("prepareStatement")) statements.incrementAndGet();
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
