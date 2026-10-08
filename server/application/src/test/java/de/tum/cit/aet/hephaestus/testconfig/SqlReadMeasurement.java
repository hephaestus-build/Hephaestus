package de.tum.cit.aet.hephaestus.testconfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.hibernate.CacheMode;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Measures a synchronous service read in a fresh persistence context, without shared cache hits. */
public class SqlReadMeasurement {
    private final SqlStatementCounter statements;
    private final TransactionTemplate transaction;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    SqlReadMeasurement(SqlStatementCounter statements, PlatformTransactionManager manager) {
        this.statements = statements;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> Read<T> measure(Supplier<T> read) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Session session = Objects.requireNonNull(entityManager).unwrap(Session.class);
            session.setCacheMode(CacheMode.IGNORE);
            var measured = statements.measure(read);
            return new Read<>(
                    measured.value(),
                    new Cost(measured.statements(), session.getStatistics().getEntityCount()));
        }));
    }

    public record Cost(long statements, int entities) {}

    public record Read<T>(T value, Cost cost) {}
}
