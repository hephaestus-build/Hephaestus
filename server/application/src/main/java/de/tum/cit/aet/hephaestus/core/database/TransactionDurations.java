package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Times every transaction from begin to commit or rollback, which is how long it holds its pooled connection, tagged by
 * its boundary. Spring Boot registers this listener with the auto-configured transaction manager.
 */
@Component
public class TransactionDurations implements TransactionExecutionListener {

    /** A {@code TransactionTemplate} without a name has no boundary of its own to report. */
    static final String UNNAMED = "programmatic";

    // A transaction begins, commits and rolls back on one thread; the status object is the same in every callback.
    private final ThreadLocal<Map<TransactionExecution, Timer.Sample>> open =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final MeterRegistry registry;
    private final Meter.MeterProvider<Timer> timers;

    public TransactionDurations(MeterRegistry registry) {
        this.registry = registry;
        this.timers = Timer.builder(CoreMetrics.DATABASE_TRANSACTION_DURATION)
                .description("Time from transaction begin to commit or rollback, while it holds a pooled connection")
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))
                .withRegistry(registry);
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure == null) {
            open.get().put(transaction, Timer.start(registry));
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, @Nullable Throwable commitFailure) {
        stop(transaction, commitFailure == null ? "commit" : "rollback");
    }

    @Override
    public void afterRollback(TransactionExecution transaction, @Nullable Throwable rollbackFailure) {
        stop(transaction, "rollback");
    }

    private void stop(TransactionExecution transaction, String outcome) {
        Timer.Sample sample = open.get().remove(transaction);
        if (sample != null) {
            sample.stop(timers.withTags("boundary", boundary(transaction.getTransactionName()), "outcome", outcome));
        }
    }

    /** The declaring class and method of a declarative boundary, without the package. */
    static String boundary(@Nullable String transactionName) {
        if (transactionName == null || transactionName.isBlank()) {
            return UNNAMED;
        }
        int method = transactionName.lastIndexOf('.');
        int type = method < 0 ? -1 : transactionName.lastIndexOf('.', method - 1);
        return transactionName.substring(type + 1);
    }
}
