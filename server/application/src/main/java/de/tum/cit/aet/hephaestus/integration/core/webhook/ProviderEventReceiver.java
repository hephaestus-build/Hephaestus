package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.consumer.EventTraceContext;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores a verified delivery in the provider event inbox: one event for each workspace whose subscription matches its
 * subject, and one installation event when its subject is installation-wide. The subscribers are read in the
 * transaction that stores the events, so a workspace receives each delivery that arrives after its subscription
 * commits, and none is lost between the two.
 */
@Component
@ConditionalOnServerRole
public class ProviderEventReceiver {

    /** How long the receive path may take to store a delivery. */
    static final Duration DEADLINE = Duration.ofSeconds(2);

    /** The bound of each statement of the receive transaction; a blocked one fails before the deadline. */
    private static final Duration STATEMENT_TIMEOUT = Duration.ofMillis(1500);

    private final ProviderEventStore store;
    private final ProviderEventSubscriptions subscriptions;
    private final EventTraceContext traceContext;
    private final TransactionTemplate transactions;
    private final ExecutorService stores = Executors.newVirtualThreadPerTaskExecutor();

    public ProviderEventReceiver(
            ProviderEventStore store,
            ProviderEventSubscriptions subscriptions,
            EventTraceContext traceContext,
            TransactionTemplate transactions) {
        this.store = store;
        this.subscriptions = subscriptions;
        this.traceContext = traceContext;
        this.transactions = transactions;
    }

    /**
     * Stores the delivery within {@link #DEADLINE}, or fails it. The receiver then answers 503 inside every provider's
     * response window, Slack's 3 seconds the shortest, instead of waiting for the pool or a blocked statement. A store
     * that commits after its deadline is harmless: the provider's retry or redelivery finds the row and adds none.
     *
     * @return the number of events stored; 0 when no workspace receives the delivery or it was stored before
     * @throws QueryTimeoutException when the inbox did not store the delivery within the deadline
     */
    public int receive(
            IntegrationKind kind, String subject, String deliveryKey, Map<String, String> headers, byte[] body) {
        Map<String, String> stored = new LinkedHashMap<>(headers);
        traceContext.inject(stored);
        Future<Integer> pending = stores.submit(() -> store(kind, subject, deliveryKey, stored, body));
        try {
            return pending.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            pending.cancel(true);
            throw new QueryTimeoutException("The inbox did not store the delivery within " + DEADLINE, late);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new QueryTimeoutException("The receiver stopped while the inbox stored the delivery", interrupted);
        } catch (ExecutionException failed) {
            throw unwrap(failed);
        }
    }

    /** The store's own failure, so the pipeline maps a database failure to its 503 as before. */
    @SuppressWarnings("PMD.PreserveStackTrace") // The wrapper only carries the store thread's failure across.
    private static RuntimeException unwrap(ExecutionException failed) {
        Throwable cause = failed.getCause();
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(failed);
    }

    private int store(
            IntegrationKind kind, String subject, String deliveryKey, Map<String, String> stored, byte[] body) {
        String provider = kind.name().toLowerCase(Locale.ROOT);
        Integer count = transactions.execute(status -> {
            store.boundStatements(STATEMENT_TIMEOUT);
            List<ProviderEventStore.Arrival> arrivals = new ArrayList<>();
            if (kind == IntegrationKind.GITHUB
                    && ConsumerSubjectMath.matchesFilter(
                            ConsumerSubjectMath.installationAwareSubjectFilter(kind), subject)) {
                arrivals.add(new ProviderEventStore.Arrival(null, provider, subject, deliveryKey, stored, body));
            }
            for (long workspaceId : subscriptions.subscribers(subject)) {
                arrivals.add(new ProviderEventStore.Arrival(workspaceId, provider, subject, deliveryKey, stored, body));
            }
            return store.store(arrivals);
        });
        return count == null ? 0 : count;
    }

    @PreDestroy
    void close() {
        stores.shutdownNow();
    }
}
