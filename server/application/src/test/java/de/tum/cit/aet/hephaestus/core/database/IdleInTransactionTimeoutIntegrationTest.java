package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.tum.cit.aet.hephaestus.core.TransientFailures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database ends a pooled session that sits idle in a transaction past the limit. The caller sees that failure as
 * a retryable one, never as a rollback error that hides it. Migrations and the LISTEN listeners have no limit.
 */
class IdleInTransactionTimeoutIntegrationTest extends BaseIntegrationTest {
    private static final String IDLE_LIMIT =
            "SELECT setting::bigint FROM pg_settings WHERE name = 'idle_in_transaction_session_timeout'";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceProperties properties;

    @Autowired
    private SpringLiquibase liquibase;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldLimitOnlyPooledSessionsToAMinuteIdleInATransaction() throws Exception {
        try (var pooled = dataSource.getConnection()) {
            assertThat(milliseconds(pooled)).isEqualTo(TimeUnit.MINUTES.toMillis(1));
        }
        try (var listener = unpooled()) {
            assertThat(milliseconds(listener)).isZero();
        }
        try (var migration = liquibase.getDataSource().getConnection()) {
            assertThat(milliseconds(migration)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldAnswerASessionEndedForIdlingAsARetryableFailureNotAsARollbackError(boolean throughJpa) throws Exception {
        var backend = new AtomicReference<Integer>();
        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            idleLimitOfThisTransaction("200ms");
            backend.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            sleep(1_000);
            if (throughJpa) entityManager.createNativeQuery("SELECT 1").getSingleResult();
            else jdbc.queryForObject("SELECT 1", Integer.class);
        }));

        assertThat(sqlStates(failure)).contains("25P03");
        assertThat(TransientFailures.isTransient(failure)).isTrue();
        assertEnded(Objects.requireNonNull(backend.get()));
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldAnswerACommitOnASessionEndedForIdlingAsAResourceFailure() throws Exception {
        var backend = new AtomicReference<Integer>();
        Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            idleLimitOfThisTransaction("200ms");
            backend.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            sleep(1_000);
        }));

        assertThat(failure).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(TransientFailures.isTransient(failure)).isTrue();
        assertEnded(Objects.requireNonNull(backend.get()));
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    /** The configured limit is a minute; a test transaction lowers it for itself only. */
    private void idleLimitOfThisTransaction(String limit) {
        jdbc.execute("SET LOCAL idle_in_transaction_session_timeout = '" + limit + "'");
    }

    private void assertEnded(int backend) throws Exception {
        try (var observer = unpooled();
                var query = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?")) {
            query.setInt(1, backend);
            try (var result = query.executeQuery()) {
                result.next();
                assertThat(result.getLong(1)).as("the ended backend is gone").isZero();
            }
        }
    }

    private static List<String> sqlStates(Throwable failure) {
        var states = new ArrayList<String>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
        }
        return states;
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** A session as the LISTEN listeners open one: a plain driver connection from the data source properties. */
    private Connection unpooled() throws SQLException {
        return DriverManager.getConnection(
                properties.determineUrl(), properties.determineUsername(), properties.determinePassword());
    }

    private static long milliseconds(Connection connection) throws SQLException {
        try (var result = connection.createStatement().executeQuery(IDLE_LIMIT)) {
            result.next();
            return result.getLong(1);
        }
    }
}
