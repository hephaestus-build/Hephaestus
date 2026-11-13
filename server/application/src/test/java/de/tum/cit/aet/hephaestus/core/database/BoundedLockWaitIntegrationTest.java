package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** A pooled session cannot wait forever on a lock; migrations, the LISTEN listeners and the copy fence wait unbounded. */
class BoundedLockWaitIntegrationTest extends BaseIntegrationTest {
    private static final long LOCK_KEY = 979_901L;
    private static final String LOCK_TIMEOUT = "SELECT setting::bigint FROM pg_settings WHERE name = 'lock_timeout'";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceProperties properties;

    @Autowired
    private SpringLiquibase liquibase;

    @Autowired
    private PersonDataCopyFence copyFence;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldFailABlockedLockWithinTheBoundWhenAnotherSessionHoldsIt() throws Exception {
        try (var holder = unpooled();
                var pooled = dataSource.getConnection()) {
            var bound = Duration.ofMillis(milliseconds(pooled, "lock_timeout"));
            assertThat(bound).isPositive();
            holder.createStatement().execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");

            long started = System.nanoTime();
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> pooled.createStatement().execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")"))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            var waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isGreaterThanOrEqualTo(bound.minusMillis(100)).isLessThan(bound.multipliedBy(3));
        }
    }

    @Test
    void shouldBoundOnlyThePoolAndNotMigrationsListenerSessionsOrTheCopyFence() throws Exception {
        try (var listener = unpooled()) {
            assertThat(milliseconds(listener, "lock_timeout")).isZero();
        }
        var migrations = liquibase.getDataSource();
        assertThat(migrations).isNotSameAs(dataSource);
        try (var migration = migrations.getConnection()) {
            assertThat(milliseconds(migration, "lock_timeout")).isZero();
        }
        // The copy fence waits for running copies or an erasure to finish, however long that takes.
        try (var lease = copyFence.capture()) {
            assertThat(lease.jdbc().queryForObject(LOCK_TIMEOUT, Long.class)).isZero();
        }
        Long erasureBound = transactions.execute(status -> {
            copyFence.holdForErasure();
            return jdbc.queryForObject(LOCK_TIMEOUT, Long.class);
        });
        assertThat(erasureBound).isZero();
    }

    /** A session as the LISTEN listeners open one: a plain driver connection from the data source properties. */
    private Connection unpooled() throws SQLException {
        return DriverManager.getConnection(
                properties.determineUrl(), properties.determineUsername(), properties.determinePassword());
    }

    private static long milliseconds(Connection connection, String setting) throws SQLException {
        try (var result = connection
                .createStatement()
                .executeQuery("SELECT setting::bigint FROM pg_settings WHERE name = '" + setting + "'")) {
            result.next();
            return result.getLong(1);
        }
    }
}
