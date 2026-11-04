package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.RequestReplySenderContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts the outbound HTTP requests that start while a transaction is open, tagged by the boundary that opened it. Such
 * a request holds a pooled connection and the transaction's row locks for as long as the other service takes to answer.
 *
 * <p>It sees every client built from a builder that Spring injects, on the thread where the request starts. A retry
 * that a reactive client schedules on another thread is not counted. It does not see the Slack SDK, NATS or the
 * Kubernetes client.
 */
@Component
public class HttpRequestsInTransactions implements ObservationHandler<RequestReplySenderContext<?, ?>> {

    private final Meter.MeterProvider<Counter> counters;

    public HttpRequestsInTransactions(MeterRegistry registry) {
        this.counters = Counter.builder(CoreMetrics.DATABASE_TRANSACTION_HTTP_REQUESTS)
                .description("Outbound HTTP requests that started while a database transaction was open")
                .withRegistry(registry);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof RequestReplySenderContext<?, ?>;
    }

    @Override
    public void onStart(RequestReplySenderContext<?, ?> context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            counters.withTags(
                            "boundary",
                            TransactionDurations.boundary(
                                    TransactionSynchronizationManager.getCurrentTransactionName()))
                    .increment();
        }
    }
}
