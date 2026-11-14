package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A pooled session cannot wait forever on a lock. Migrations, the LISTEN listeners and the copy fence's own waits are
 * unbounded.
 */
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
                var held = holder.createStatement();
                var pooled = dataSource.getConnection();
                var blocked = pooled.createStatement()) {
            var bound = Duration.ofMillis(milliseconds(pooled, "lock_timeout"));
            assertThat(bound).isPositive();
            held.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")");

            long started = System.nanoTime();
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> blocked.execute("SELECT pg_advisory_lock(" + LOCK_KEY + ")"))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            var waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).isGreaterThanOrEqualTo(bound.minusMillis(100)).isLessThan(bound.multipliedBy(3));
        }
    }

    @Test
    void shouldBoundOnlyThePoolAndNotMigrationsListenerSessionsOrACopyFenceLease() throws Exception {
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
    }

    @Test
    void shouldKeepRowValuesOutOfAMigrationErrorMessage() throws Exception {
        try (var migration = liquibase.getDataSource().getConnection();
                var statement = migration.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE migration_detail (id bigint PRIMARY KEY)");
            statement.execute("INSERT INTO migration_detail VALUES (" + LOCK_KEY + ")");

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.execute("INSERT INTO migration_detail VALUES (" + LOCK_KEY + ")"))
                    .satisfies(failure -> {
                        assertThat(failure.getSQLState()).isEqualTo("23505");
                        assertThat(failure.getMessage()).doesNotContain(Long.toString(LOCK_KEY));
                    });
        }
    }

    /**
     * Capture admission queues behind a running erasure, and the next erasure behind both: each waits past the bound
     * and is admitted once the erasure ends. The rest of each transaction has the bound again.
     */
    @Test
    void shouldLetCaptureAdmissionAndAnErasureWaitPastTheBoundWhileAnErasureRuns() throws Exception {
        long bound;
        try (var pooled = dataSource.getConnection()) {
            bound = milliseconds(pooled, "lock_timeout");
        }
        var executor = Executors.newFixedThreadPool(2);
        try {
            var running = copyFence.erase();
            var capture = executor.submit(() -> boundAfter(copyFence::holdForCapture));
            try {
                awaitCopyFenceWaiters(1);
                var erasure = executor.submit(() -> boundAfter(copyFence::holdForErasure));
                awaitCopyFenceWaiters(2);
                Thread.sleep(bound + 500);
                assertThat(capture).isNotDone();
                assertThat(erasure).isNotDone();

                running.close();
                assertThat(capture.get(10, TimeUnit.SECONDS)).isEqualTo(bound);
                assertThat(erasure.get(10, TimeUnit.SECONDS)).isEqualTo(bound);
            } finally {
                running.close();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private @Nullable Long boundAfter(Runnable hold) {
        return transactions.execute(status -> {
            hold.run();
            return jdbc.queryForObject(LOCK_TIMEOUT, Long.class);
        });
    }

    private void awaitCopyFenceWaiters(int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=2165 AND objid=1"
                                + " AND NOT granted",
                        Long.class))
                < waiters) {
            if (System.nanoTime() > deadline) throw new AssertionError("The copy fence has no waiting transaction");
            Thread.sleep(20);
        }
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
