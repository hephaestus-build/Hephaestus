package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ProviderFootprint;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes at the provider what a disconnected connection created there, then clears its retained credential and
 * provider ids. The event publication registry delivers {@link ProviderTeardownRequested} at least once and resubmits
 * a failed teardown, for at most {@link #WINDOW}.
 *
 * <p>One transaction holds the connection's lifecycle lock from the generation check, across the provider call, to the
 * clear. This is the documented exception to the rule that no transaction stays open across a network call (ADR 0075):
 * a reconnect takes the same lock first, so it can never receive a token or an installation that this call removes.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Each teardown names its connection and the workspace that owns it")
public class ProviderTeardown {

    /** How long a failing provider is retried before Hephaestus clears the credential without removing it there. */
    static final Duration WINDOW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ProviderTeardown.class);

    enum Outcome {
        /** The provider confirmed the removal, or the connection left nothing there. */
        REVOKED,
        /** The window ended while the provider still failed. */
        PURGED_UNREVOKED,
        /** A reconnect revived the connection, which uses its footprint at the provider again. */
        ADOPTED
    }

    private final ConnectionRepository connections;
    private final CredentialReader credentials;
    private final CredentialBundleConverter converter;
    private final IntegrationAttentionService attention;
    private final Map<IntegrationKind, ConnectionStrategy> strategies = new EnumMap<>(IntegrationKind.class);
    private final Meter.MeterProvider<Counter> outcomes;
    private final TransactionTemplate transactions;
    private final TransactionTemplate suspended;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderTeardown(
            ConnectionRepository connections,
            CredentialReader credentials,
            CredentialBundleConverter converter,
            IntegrationAttentionService attention,
            List<ConnectionStrategy> strategyBeans,
            MeterRegistry registry,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.connections = connections;
        this.credentials = credentials;
        this.converter = converter;
        this.attention = attention;
        strategyBeans.forEach(strategy -> strategies.put(strategy.kind(), strategy));
        this.outcomes = Counter.builder(IntegrationCoreMetrics.INTEGRATION_PROVIDER_TEARDOWN)
                .description("Disconnects whose provider footprint Hephaestus removed or gave up on")
                .withRegistry(registry);
        this.transactions = new TransactionTemplate(transactionManager);
        this.suspended = new TransactionTemplate(transactionManager);
        this.suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProviderTeardownRequested requested) {
        transactions.executeWithoutResult(status -> tearDown(requested));
    }

    /**
     * A reconnect revived this connection, so it uses what it left at the provider again. The pending teardown then has
     * nothing to remove, and an event that is still delivered later no longer matches the generation.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onActivated(ConnectionLifecycleEvent.Activated activated) {
        connections
                .findByIdAndWorkspaceId(activated.connectionId(), activated.workspaceId())
                .filter(connection -> connection.getProviderTeardownRequestedAt() != null)
                .ifPresent(connection -> {
                    connection.adoptProviderFootprint();
                    count(connection, Outcome.ADOPTED);
                });
    }

    /**
     * Removes a pending teardown's footprint now, for an erasure that must not leave the credential behind. The caller
     * holds its transaction; the provider call runs with that transaction suspended. Throws when the provider does not
     * confirm the removal, so the erasure rolls back and can be retried.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tearDownForErasure(Connection pending) {
        long workspaceId = pending.getWorkspace().getId();
        connections.acquireLifecycleLock(pending.getId(), workspaceId);
        Connection connection =
                connections.findByIdAndWorkspaceId(pending.getId(), workspaceId).orElse(null);
        if (connection == null) {
            return;
        }
        // The purge loaded this row before the lock; a teardown or a reconnect may have committed since.
        Objects.requireNonNull(entityManager).refresh(connection);
        if (connection.getState() != IntegrationState.UNINSTALLED
                || connection.getProviderTeardownRequestedAt() == null) {
            return;
        }
        if (!strategies.containsKey(connection.getKind())) {
            // An integration that is turned off cannot remove anything; the erasure must not wait for it.
            release(connection, Outcome.PURGED_UNREVOKED);
            return;
        }
        Runnable teardown = prepare(connection).orElse(null);
        if (teardown != null) {
            suspended.executeWithoutResult(status -> run(connection, teardown));
        }
        release(connection, Outcome.REVOKED);
    }

    /**
     * Clears what a disconnect retained once its window has passed, also when no teardown event is left to deliver.
     * Each connection is cleared in a transaction of its own under its lifecycle lock.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @SchedulerLock(name = "provider-teardown-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Finds expired teardowns across workspaces; each clear names its connection and workspace")
    public void expire() {
        Instant cutoff = clock.instant().minus(WINDOW);
        for (Connection expired :
                connections.findByStateAndProviderTeardownRequestedAtBefore(IntegrationState.UNINSTALLED, cutoff)) {
            long connectionId = Objects.requireNonNull(expired.getId());
            long workspaceId = expired.getWorkspace().getId();
            transactions.executeWithoutResult(status -> {
                connections.acquireLifecycleLock(connectionId, workspaceId);
                connections
                        .findByIdAndWorkspaceId(connectionId, workspaceId)
                        .filter(locked -> locked.getState() == IntegrationState.UNINSTALLED)
                        .filter(locked -> {
                            Instant requestedAt = locked.getProviderTeardownRequestedAt();
                            return requestedAt != null && requestedAt.isBefore(cutoff);
                        })
                        .ifPresent(locked -> release(locked, Outcome.PURGED_UNREVOKED));
            });
        }
    }

    private void tearDown(ProviderTeardownRequested requested) {
        connections.acquireLifecycleLock(requested.connectionId(), requested.workspaceId());
        Connection connection = connections
                .findByIdAndWorkspaceId(requested.connectionId(), requested.workspaceId())
                .filter(found -> found.getState() == IntegrationState.UNINSTALLED
                        && found.getCredentialsGeneration() == requested.credentialsGeneration())
                .orElse(null);
        Instant requestedAt = connection == null ? null : connection.getProviderTeardownRequestedAt();
        if (connection == null || requestedAt == null) {
            return;
        }
        if (clock.instant().isAfter(requestedAt.plus(WINDOW))) {
            release(connection, Outcome.PURGED_UNREVOKED);
            return;
        }
        prepare(connection).ifPresent(teardown -> run(connection, teardown));
        release(connection, Outcome.REVOKED);
    }

    private Optional<Runnable> prepare(Connection connection) {
        ConnectionStrategy strategy = strategies.get(connection.getKind());
        if (strategy == null) {
            throw new IllegalStateException("No provider teardown is available for " + connection.getKind());
        }
        IntegrationRef ref = new IntegrationRef(
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getInstanceKey(),
                connection.getId());
        return strategy.prepareProviderTeardown(
                ref,
                new ProviderFootprint(
                        connection.getConfig(),
                        credentials.retainedForTeardown(connection).orElse(null)));
    }

    @SuppressWarnings(
            "PMD.PreserveStackTrace") // A provider failure can carry request details, so its cause stays here.
    private static void run(Connection connection, Runnable teardown) {
        try {
            teardown.run();
        } catch (RuntimeException failure) {
            log.warn(
                    "Provider teardown failed and will be retried: connectionId={}, kind={}, failure={}",
                    connection.getId(),
                    connection.getKind(),
                    failure.getClass().getSimpleName());
            throw new IllegalStateException("Provider teardown failed for connection " + connection.getId());
        }
    }

    private void release(Connection connection, Outcome outcome) {
        connection.releaseProviderFootprint(converter);
        count(connection, outcome);
        if (outcome == Outcome.REVOKED) {
            log.info("Provider teardown completed: connectionId={}, kind={}", connection.getId(), connection.getKind());
            return;
        }
        log.warn(
                "Provider footprint cleared without removal at the provider: connectionId={}, kind={}, outcome={}",
                connection.getId(),
                connection.getKind(),
                tag(outcome));
        attention.report(
                connection.getId(), connection.getWorkspace().getId(), Problem.PROVIDER_CLEANUP_UNCONFIRMED, false);
    }

    private void count(Connection connection, Outcome outcome) {
        outcomes.withTags("kind", connection.getKind().name().toLowerCase(Locale.ROOT), "outcome", tag(outcome))
                .increment();
    }

    private static String tag(Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }
}
