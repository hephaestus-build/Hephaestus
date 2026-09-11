package de.tum.cit.aet.hephaestus.testconfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DatabaseTestUtils {

    // ShedLock caches known lock rows; truncating them can strand acquisition until the context restarts.
    private static final Set<String> IGNORED_TABLES = Set.of("databasechangelog", "databasechangeloglock", "shedlock");
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "40001", "55P03");
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 100;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;
    private @Nullable String truncateStatement;

    public DatabaseTestUtils(PlatformTransactionManager transactionManager) {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void cleanDatabase() {
        DatabaseCleanupEvent event = new DatabaseCleanupEvent();
        event.begin();
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> truncateTables());
                event.attempts = attempt;
                event.commit();
                return;
            } catch (RuntimeException exception) {
                lastException = exception;
                if (!isRetryable(exception) || attempt == MAX_ATTEMPTS) {
                    break;
                }
                sleepBeforeRetry(attempt);
            }
        }
        event.attempts = MAX_ATTEMPTS;
        event.commit();
        throw new IllegalStateException("Failed to clean integration-test database", lastException);
    }

    @Name("hephaestus.test.DatabaseCleanup")
    @Label("Integration database cleanup")
    @Category({"Hephaestus", "Tests"})
    static final class DatabaseCleanupEvent extends Event {

        @Label("Attempts")
        int attempts;
    }

    private void truncateTables() {
        EntityManager currentEntityManager = Objects.requireNonNull(entityManager);
        currentEntityManager.flush();
        String statement = getTruncateStatement();
        if (!statement.isEmpty()) {
            currentEntityManager.createNativeQuery(statement).executeUpdate();
        }
        currentEntityManager.clear();
    }

    private boolean isRetryable(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && (RETRYABLE_SQL_STATES.contains(sqlState) || sqlState.startsWith("08"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (1L << (attempt - 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying integration-test database cleanup", exception);
        }
    }

    private synchronized String getTruncateStatement() {
        if (truncateStatement == null) {
            // Singleton caches outlive database cleanup. Never reuse ids for different rows in one context.
            truncateStatement = fetchApplicationTables().stream()
                    .sorted()
                    .map(this::quoteIdentifier)
                    .collect(Collectors.collectingAndThen(
                            Collectors.joining(", "),
                            tables -> tables.isEmpty() ? "" : "TRUNCATE TABLE " + tables + " CASCADE"));
        }
        return Objects.requireNonNull(truncateStatement);
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchApplicationTables() {
        List<Object> tables = Objects.requireNonNull(entityManager)
                .createNativeQuery("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name NOT LIKE 'pg_%' AND table_name NOT LIKE 'sql_%'")
                .getResultList();
        return tables.stream()
                .map(Object::toString)
                .filter(name -> !IGNORED_TABLES.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private String quoteIdentifier(String tableName) {
        return "\"" + tableName + "\"";
    }
}
