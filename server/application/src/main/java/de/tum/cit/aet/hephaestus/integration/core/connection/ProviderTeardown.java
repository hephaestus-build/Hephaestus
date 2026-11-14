package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.SweepRowFailures;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairDispatcher;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Credentials expire independently of durable provider-removal fences (ADR 0101). */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Credential expiry names each connection and workspace; unresolved provider identity remains global")
public class ProviderTeardown {
    private static final Logger log = LoggerFactory.getLogger(ProviderTeardown.class);

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    static final Duration WINDOW = Duration.ofHours(24);
    private final ConnectionRepository connections;
    private final CredentialBundleConverter converter;
    private final ProviderResourceRepairs repairs;
    private final ProviderResourceRepairDispatcher dispatcher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProviderTeardown(
            ConnectionRepository connections,
            CredentialBundleConverter converter,
            ProviderResourceRepairs repairs,
            ProviderResourceRepairDispatcher dispatcher,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.connections = connections;
        this.converter = converter;
        this.repairs = repairs;
        this.dispatcher = dispatcher;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProviderTeardownRequested requested) {
        transactions.executeWithoutResult(status -> {
            connections
                    .findByIdAndWorkspaceId(requested.connectionId(), requested.workspaceId())
                    .filter(connection -> connection.getState() == IntegrationState.UNINSTALLED
                            && connection.getProviderTeardownRequestedAt() != null)
                    .ifPresent(connection -> {
                        repairs.coordinate(connection);
                        if (connections
                                .findAndLockLifecycleConnection(connection.getId(), requested.workspaceId())
                                .isEmpty()) return;
                        Objects.requireNonNull(entityManager).refresh(connection);
                        if (connection.getState() == IntegrationState.UNINSTALLED
                                && connection.getCredentialsGeneration() == requested.credentialsGeneration()
                                && connection.getProviderTeardownRequestedAt() != null) captureIfNeeded(connection);
                    });
        });
        for (var repair : repairs.forConnection(requested.workspaceId(), requested.connectionId())) {
            dispatcher.dispatch(repair.getId());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void tearDownForErasure(Connection pending) {
        repairs.coordinate(pending);
        connections.acquireLifecycleLock(pending.getId(), pending.getWorkspace().getId());
        Objects.requireNonNull(entityManager).refresh(pending);
        if (pending.getState() != IntegrationState.UNINSTALLED) return;
        captureIfNeeded(pending);
        // Erasure never waits for provider proof or retains a credential beyond its existing deadline.
        pending.releaseProviderFootprint(converter);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @SchedulerLock(name = "provider-teardown-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void expire() {
        Instant cutoff = clock.instant().minus(WINDOW);
        for (Connection expired :
                connections.findByStateAndProviderTeardownRequestedAtBeforeOrderByProviderTeardownRequestedAtAsc(
                        IntegrationState.UNINSTALLED, cutoff)) {
            long workspaceId = expired.getWorkspace().getId();
            try {
                transactions.executeWithoutResult(status -> {
                    repairs.coordinate(expired);
                    connections.acquireLifecycleLock(expired.getId(), workspaceId);
                    connections
                            .findByIdAndWorkspaceId(expired.getId(), workspaceId)
                            .filter(connection -> connection.getState() == IntegrationState.UNINSTALLED)
                            .filter(connection -> connection.getProviderTeardownRequestedAt() != null
                                    && connection
                                            .getProviderTeardownRequestedAt()
                                            .isBefore(cutoff))
                            .ifPresent(connection -> {
                                captureIfNeeded(connection);
                                connection.releaseProviderFootprint(converter);
                            });
                });
            } catch (RuntimeException failure) {
                SweepRowFailures.skipUnlessTransient(
                        log, "provider-teardown-expiry", workspaceId, expired.getId(), failure);
            }
        }
    }

    private void captureIfNeeded(Connection connection) {
        if (!connection.providerRepairWasCaptured()) {
            repairs.requestRemoval(connection, false);
            connection.markProviderRepairCaptured();
        }
    }
}
