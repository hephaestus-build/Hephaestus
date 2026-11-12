package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.consumer.EventTraceContext;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
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

    private static final Logger log = LoggerFactory.getLogger(ProviderEventReceiver.class);

    /** How long the receive path may take to store a delivery. */
    static final Duration DEADLINE = Duration.ofSeconds(2);

    /** The bound of each statement of the receive transaction; a blocked one fails before the deadline. */
    private static final Duration STATEMENT_TIMEOUT = Duration.ofMillis(1500);

    private final ProviderEventStore store;
    private final ProviderEventSubscriptions subscriptions;
    private final ConnectionRepository connections;
    private final MeterRegistry meters;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final EventTraceContext traceContext;
    private final TransactionTemplate transactions;
    private final ExecutorService stores = Executors.newVirtualThreadPerTaskExecutor();

    public ProviderEventReceiver(
            ProviderEventStore store,
            ProviderEventSubscriptions subscriptions,
            ConnectionRepository connections,
            MeterRegistry meters,
            EventTraceContext traceContext,
            TransactionTemplate transactions) {
        this.store = store;
        this.subscriptions = subscriptions;
        this.connections = connections;
        this.meters = meters;
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
            boolean installation =
                    kind == IntegrationKind.GITHUB && ConsumerSubjectMath.isInstallationControlSubject(subject);
            if (installation) {
                var installationHeaders = new LinkedHashMap<>(stored);
                installationHeaders.put(ProviderEvent.SOURCE_SERVER_URL, PersonSourceNamespace.GITHUB_DEFAULT_URL);
                arrivals.add(new ProviderEventStore.Arrival(
                        null, provider, subject, deliveryKey, installationHeaders, body));
            } else if (kind == IntegrationKind.GITHUB
                    && ConsumerSubjectMath.matchesFilter(
                            ConsumerSubjectMath.installationAwareSubjectFilter(kind), subject)) {
                rejected(provider, "work-event-without-source");
                return 0;
            }
            var candidates = installation
                    ? List.<ProviderEventSubscriptions.Subscription>of()
                    : subscriptions.subscribers(subject);
            if (candidates.isEmpty() && arrivals.isEmpty()) {
                rejected(provider, "no-subscription");
            }
            // An App delivery can fan out to PAT workspaces that monitor the same repository. Its source
            // installation must still be active, even when its own workspace does not monitor this repository.
            String installationId =
                    kind == IntegrationKind.GITHUB ? stored.get(ProviderEvent.GITHUB_INSTALLATION_ID) : null;
            var sources = !installation && installationId != null
                    ? connections.findAllByKindAndInstanceKeyAndStateIn(
                            kind, installationId, Arrays.asList(IntegrationState.values()))
                    : List.<Connection>of();
            var locked = new TreeMap<Long, Connection>();
            var configurations = new LinkedHashMap<Long, ConnectionConfig>();
            for (var source : sources) {
                locked.put(Objects.requireNonNull(source.getId()), source);
            }
            for (var candidate : candidates) {
                connections
                        .findByIdAndWorkspaceId(candidate.connectionId(), candidate.workspaceId())
                        .ifPresent(connection -> {
                            locked.put(candidate.connectionId(), connection);
                            configurations.put(candidate.connectionId(), connection.getConfig());
                        });
            }
            // All fan-out and source locks use the same global order, including cross-workspace deliveries.
            for (var entry : locked.entrySet()) {
                Connection connection = entry.getValue();
                connections.acquireLifecycleLock(
                        entry.getKey(), connection.getWorkspace().getId());
                // Selection may have loaded this entity before a concurrent suspension committed.
                Objects.requireNonNull(entityManager).refresh(connection);
            }
            if (!installation
                    && installationId != null
                    && sources.stream()
                            .noneMatch(source -> source.getState() == IntegrationState.ACTIVE
                                    && source.getConfig() instanceof ConnectionConfig.GitHubAppConfig config
                                    && config.installationId() != null
                                    && installationId.equals(
                                            config.installationId().toString()))) {
                rejected(provider, "connection-not-active");
                return 0;
            }
            for (var candidate : candidates) {
                long workspaceId = candidate.workspaceId();
                Connection connection = locked.get(candidate.connectionId());
                if (connection == null) {
                    rejected(provider, "connection-missing");
                    continue;
                }
                ConnectionConfig configured = Objects.requireNonNull(configurations.get(candidate.connectionId()));
                if (connection.getKind() != kind || connection.getState() != IntegrationState.ACTIVE) {
                    rejected(provider, "connection-not-active");
                    continue;
                }
                if (!configured.equals(connection.getConfig())) {
                    // Retry selection on the current route instead of attributing an old event to a new config.
                    throw new TransientDataAccessResourceException("Connection changed during webhook admission");
                }
                if (!matchesVerifiedRoute(kind, connection, workspaceId, stored)) {
                    rejected(provider, "connection-route-mismatch");
                    continue;
                }
                String source = kind == IntegrationKind.GITHUB
                        ? PersonSourceNamespace.GITHUB_DEFAULT_URL
                        : PersonSourceNamespace.from(kind.name(), SourceProviderNamespaces.configuredUrl(configured))
                                .orElseThrow(() ->
                                        new IllegalStateException("An active connection requires its source namespace"))
                                .serverUrl();
                var workspaceHeaders = new LinkedHashMap<>(stored);
                workspaceHeaders.put(ProviderEvent.SOURCE_SERVER_URL, source);
                arrivals.add(new ProviderEventStore.Arrival(
                        workspaceId, provider, subject, deliveryKey, workspaceHeaders, body));
            }
            return store.store(arrivals);
        });
        return count == null ? 0 : count;
    }

    private void rejected(String provider, String reason) {
        log.info("Webhook admission refused: provider={}, reason={}", provider, reason);
        meters.counter(IntegrationCoreMetrics.WEBHOOK_REJECTED, "provider", provider, "reason", reason)
                .increment();
    }

    /** Route metadata comes from signature verification, never from a request's passthrough headers. */
    private static boolean matchesVerifiedRoute(
            IntegrationKind kind, Connection connection, long workspaceId, Map<String, String> headers) {
        return switch (kind) {
            case GITHUB ->
                SourceProviderNamespaces.originOf(kind, connection.getConfig())
                                .filter(PersonSourceNamespace.GITHUB_DEFAULT_URL::equals)
                                .isPresent()
                        && switch (connection.getConfig()) {
                            case ConnectionConfig.GitHubAppConfig config ->
                                config.installationId() != null
                                        && config.installationId()
                                                .toString()
                                                .equals(headers.get(ProviderEvent.GITHUB_INSTALLATION_ID));
                            case ConnectionConfig.GitHubPatConfig ignored -> true;
                            default -> false;
                        };
            case SLACK -> true;
            case GITLAB ->
                connection.getConfig() instanceof ConnectionConfig.GitLabConfig config
                        && Long.toString(workspaceId).equals(headers.get(GitLabRouteCredential.HEADER_WORKSPACE))
                        && ScmOrigin.of(config.serverUrl())
                                .map(origin -> origin.equals(headers.get(GitLabRouteCredential.HEADER_ORIGIN)))
                                .orElse(false)
                        && config.gitlabGroupId().toString().equals(headers.get(GitLabRouteCredential.HEADER_GROUP_ID));
            case OUTLINE ->
                connection.getConfig() instanceof ConnectionConfig.OutlineConfig config
                        && config.webhookRoute() != null
                        && config.webhookRoute().equals(headers.get(OutlineWebhookController.HEADER_ROUTE));
        };
    }

    @PreDestroy
    void close() {
        stores.shutdownNow();
    }
}
