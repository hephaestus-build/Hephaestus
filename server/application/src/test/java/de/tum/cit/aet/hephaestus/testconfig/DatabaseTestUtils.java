package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestriction;
import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestrictionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;
    private final IdentityRestrictionRepository restrictions;
    private @Nullable String truncateStatement;

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

    public void cleanDatabase() {
        DatabaseCleanupEvent event = new DatabaseCleanupEvent();
        event.begin();
        try {
            transactionTemplate.executeWithoutResult(status -> truncateTables());
        } finally {
            event.commit();
        }
    }

    @Name("hephaestus.test.DatabaseCleanup")
    @Label("Integration database cleanup")
    @Category({"Hephaestus", "Tests"})
    static final class DatabaseCleanupEvent extends Event {}

    private void truncateTables() {
        EntityManager currentEntityManager = Objects.requireNonNull(entityManager);
        currentEntityManager.flush();
        String statement = getTruncateStatement();
        if (!statement.isEmpty()) {
            currentEntityManager.createNativeQuery(statement).executeUpdate();
        }
        currentEntityManager.clear();
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
