package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestriction;
import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestrictionRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyBypass;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
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
    /**
     * Kept between tests. {@code provenance_signing_key} holds the key that the start-up bootstrap made for this
     * context; only the bootstrap makes one, so a wiped table would leave every later signature without a key.
     * The migration's sweep signing key is kept too. Fixture system secrets are deleted individually.
     */
    private static final Set<String> IGNORED_TABLES =
            Set.of("databasechangelog", "databasechangeloglock", "shedlock", "provenance_signing_key", "system_secret");

    /** How long the cleanup keeps asking for its table locks before it fails. */
    private static final Duration LOCK_PATIENCE = Duration.ofSeconds(30);

    private static final Duration LOCK_RETRY_PAUSE = Duration.ofMillis(10);

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;
    private final IdentityRestrictionRepository restrictions;
    private @Nullable String applicationTables;

    public DatabaseTestUtils(
            PlatformTransactionManager transactionManager, IdentityRestrictionRepository restrictions) {
        this.restrictions = restrictions;
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID erase(long providerId, String userKey, @Nullable String teamKey) {
        var restriction = new IdentityRestriction();
        restriction.setProviderId(providerId);
        restriction.setProviderUserKey(userKey);
        restriction.setTeamKey(teamKey == null ? "" : teamKey);
        restriction.setKind(IdentityRestriction.Kind.ERASED);
        return restrictions.saveAndFlush(restriction).getId();
    }

    /**
     * Empties every application table. The ledger listener of each cached test context can read them at any moment, and
     * a read locks its tables in its own order. The cleanup therefore never waits for a table lock: it gets all of them
     * at once or starts again, so it is never part of a deadlock.
     */
    public void cleanDatabase() {
        DatabaseCleanupEvent event = new DatabaseCleanupEvent();
        event.begin();
        try {
            long deadline = System.nanoTime() + LOCK_PATIENCE.toNanos();
            while (true) {
                try {
                    transactionTemplate.executeWithoutResult(status -> truncateTables());
                    return;
                } catch (RuntimeException failure) {
                    if (!foundATableHeld(failure) || System.nanoTime() - deadline > 0) throw failure;
                    LockSupport.parkNanos(LOCK_RETRY_PAUSE.toNanos());
                }
            }
        } finally {
            event.commit();
        }
    }

    /** PostgreSQL's {@code lock_not_available}, which {@code NOWAIT} raises for a held table. */
    private static boolean foundATableHeld(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    @Name("hephaestus.test.DatabaseCleanup")
    @Label("Integration database cleanup")
    @Category({"Hephaestus", "Tests"})
    static final class DatabaseCleanupEvent extends Event {}

    @SuppressWarnings("try") // Closing the scope restores tenancy enforcement.
    private void truncateTables() {
        EntityManager currentEntityManager = Objects.requireNonNull(entityManager);
        currentEntityManager.flush();
        String tables = getApplicationTables();
        if (!tables.isEmpty()) {
            try (var ignored = TenancyBypass.open("Clear fixture rows in the integration database")) {
                currentEntityManager
                        .createNativeQuery("LOCK TABLE " + tables + " IN ACCESS EXCLUSIVE MODE NOWAIT")
                        .executeUpdate();
                // Singleton caches outlive database cleanup. Never reuse ids for different rows in one context.
                currentEntityManager
                        .createNativeQuery("TRUNCATE TABLE " + tables + " CASCADE")
                        .executeUpdate();
                currentEntityManager
                        .createNativeQuery("DELETE FROM system_secret WHERE name <> :sweepKey")
                        .setParameter("sweepKey", RowSweepAdmission.KEY_NAME)
                        .executeUpdate();
            }
        }
        currentEntityManager.clear();
    }

    /** The application tables as one comma-separated list, or an empty string. */
    private synchronized String getApplicationTables() {
        if (applicationTables == null) {
            applicationTables = fetchApplicationTables().stream()
                    .sorted()
                    .map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
        }
        return Objects.requireNonNull(applicationTables);
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
