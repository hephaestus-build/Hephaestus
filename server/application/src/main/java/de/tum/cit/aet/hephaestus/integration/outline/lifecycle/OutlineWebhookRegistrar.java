package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.metrics.OutlineMetrics;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineRouteCredential;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers and tears down a workspace's Outline change-notification subscription. Runs at connect time and
 * as a self-heal inside every full reconcile, because Outline auto-disables a subscription after repeated
 * delivery failures. Each registration gets a new route ({@link OutlineRouteCredential}); the subscription id and
 * the route are stored on the Connection, and the signing secret is derived from the route, never stored.
 *
 * <p>Best-effort and never throws: without an external URL or token, or on any Outline-side failure, the
 * periodic reconcile remains the source of truth and a missing subscription only costs freshness.
 *
 * <p>The single seam through which a subscription id changes. The workspace's {@code NatsSubscriptionProvider}
 * derives the {@code outline} subject from that id, so every change must be followed by a scope-consumer
 * reconcile — otherwise the running consumer keeps filtering on a stale subject and the workspace receives no
 * deliveries until the next restart. {@link #reconcileScopeConsumer} therefore runs from every branch that
 * changes the id, never from the early no-op returns.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(OutlineWebhookRegistrar.class);

    /** Subscription display name in the owner's Outline settings. */
    private static final String SUBSCRIPTION_NAME = "Hephaestus documentation sync";

    private final ConnectionService connectionService;
    private final OutlineWebhookClient outlineApiClient;
    private final OutlineRouteCredential credential;
    private final String externalUrl;

    /** Absent when {@code hephaestus.sync.nats.enabled=false}, so every use goes through {@link ObjectProvider#ifAvailable}. */
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumer;

    /** Absent without NATS or off the server role; then no receiver reads routes either. */
    private final ObjectProvider<OutlineRouteProjection> routes;

    private final ConnectionRepository connections;

    private final Counter failedDeletions;

    /** ACTIVE connections whose subscription is missing or not on a current route, as of the last heal. */
    private final AtomicInteger unrouted = new AtomicInteger();

    public OutlineWebhookRegistrar(
            ConnectionService connectionService,
            OutlineWebhookClient outlineApiClient,
            OutlineRouteCredential credential,
            @Value("${hephaestus.webhook.external-url:}") String externalUrl,
            ObjectProvider<IntegrationNatsConsumer> natsConsumer,
            ObjectProvider<OutlineRouteProjection> routes,
            ConnectionRepository connections,
            MeterRegistry meterRegistry) {
        this.connectionService = connectionService;
        this.outlineApiClient = outlineApiClient;
        this.credential = credential;
        this.externalUrl = externalUrl;
        this.natsConsumer = natsConsumer;
        this.routes = routes;
        this.connections = connections;
        this.failedDeletions = Counter.builder(OutlineMetrics.OUTLINE_WEBHOOK_SUBSCRIPTION_DELETION_FAILED)
                .description("Outline subscriptions that Hephaestus could not delete upstream")
                .register(meterRegistry);
        Gauge.builder(OutlineMetrics.OUTLINE_WEBHOOK_SUBSCRIPTION_UNROUTED, unrouted, AtomicInteger::get)
                .description("ACTIVE Outline connections whose subscription is missing or not on a current route")
                .register(meterRegistry);
    }

    /**
     * Registers the subscription of every ACTIVE connection that has none on a current route: after an upgrade, a
     * routing key rotation, or a registration that failed. Runs at start and every 5 minutes until none is left, so no
     * operator step and no full sync is needed; {@code outline.webhook.subscription.unrouted} counts what is left.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    @WorkspaceAgnostic(
            "Heals the Outline subscription of every workspace; each registration is scoped to its workspace")
    public void healRoutes() {
        if (externalUrl == null || externalUrl.isBlank() || !credential.isConfigured()) {
            return;
        }
        int left = 0;
        for (ConnectionRepository.OutlineRouteRow row : connections.findActiveOutlineRoutes()) {
            String route = row.getRoute();
            if (route != null && credential.isCurrent(route)) {
                continue;
            }
            ensureSubscription(row.getWorkspaceId());
            left++;
        }
        unrouted.set(left);
    }

    /**
     * Brings the workspace's scope consumer in line with the subscription id this class just stored or
     * invalidated. {@code startConsumingScope} no-ops once the scope already has consumers (the common
     * case: an SCM stream is already running) and {@code updateScopeConsumer} no-ops when the scope has
     * none yet (the rare case: Outline is the workspace's first-ever integration) — calling both in
     * sequence therefore reconciles every state without duplicating work in either.
     */
    private void reconcileScopeConsumer(long workspaceId) {
        natsConsumer.ifAvailable(consumer -> {
            consumer.startConsumingScope(workspaceId);
            consumer.updateScopeConsumer(workspaceId);
        });
    }

    /**
     * Ensures the workspace has a live change-notification subscription. Without a stored id this
     * registers one (a quiet no-op when the integration has no external URL, ACTIVE Outline connection,
     * server URL, or token). With a stored id it verifies the subscription upstream: gone,
     * {@code enabled=false} (Outline disables a subscription whose deliveries fail too often), posting to
     * another URL, or on a route of a retired routing key is replaced: a new subscription is registered and
     * stored, then the old one is deleted upstream; an unverifiable upstream (listing failed) changes nothing.
     */
    public void ensureSubscription(long workspaceId) {
        if (externalUrl == null || externalUrl.isBlank() || !credential.isConfigured()) {
            return;
        }
        Optional<Connection> active = connectionService.findActive(workspaceId, IntegrationKind.OUTLINE);
        if (active.isEmpty() || !(active.get().getConfig() instanceof ConnectionConfig.OutlineConfig config)) {
            return;
        }
        String serverUrl = config.serverUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            return;
        }
        Optional<BearerToken> bearer = connectionService.findActiveBearerToken(workspaceId, IntegrationKind.OUTLINE);
        if (bearer.isEmpty()) {
            return;
        }
        String token = bearer.get().token();

        String storedId = config.webhookSubscriptionId();
        if (storedId != null && !storedId.isBlank()) {
            String storedRoute = config.webhookRoute();
            if (storedRoute != null && credential.isCurrent(storedRoute)) {
                Boolean healthy = isSubscriptionHealthy(serverUrl, token, storedId, deliveryUrl(storedRoute));
                if (healthy == null || healthy) {
                    return; // healthy, or unverifiable — don't churn a subscription we cannot see
                }
            }
            log.info(
                    "outline.webhook: stored subscription {} is missing, disabled or off its current route for workspaceId={} — re-registering",
                    storedId,
                    workspaceId);
            // The new subscription first: a failed registration keeps the old one, and the next reconcile retries.
            if (register(workspaceId, serverUrl, token)) {
                deleteQuietly(serverUrl, token, storedId);
            }
            return;
        }
        register(workspaceId, serverUrl, token);
    }

    /**
     * Whether the stored subscription still exists upstream, is enabled and posts to {@code deliveryUrl}.
     * {@code null} when the upstream listing itself failed (unknown — the caller must not churn on that).
     */
    private @Nullable Boolean isSubscriptionHealthy(
            String serverUrl, String token, String subscriptionId, String deliveryUrl) {
        try {
            for (OutlineWebhookSubscription subscription :
                    outlineApiClient.listWebhookSubscriptions(serverUrl, token)) {
                if (subscriptionId.equals(subscription.getId())) {
                    return !Boolean.FALSE.equals(subscription.getEnabled())
                            && deliveryUrl.equals(subscription.getUrl());
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("outline.webhook: could not verify subscription {}: {}", subscriptionId, e.toString());
            return null;
        }
    }

    /** Registers a subscription on a new route and stores it; {@code false} when Outline did not register one. */
    private boolean register(long workspaceId, String serverUrl, String token) {
        // Deliveries land on the receiver's route endpoint, are verified against the route's derived secret,
        // and are published to the durable `outline` stream (ADR 0023 §3, ADR 0066).
        OutlineRouteCredential.Issued route = credential.issue();
        String deliveryUrl = deliveryUrl(route.route());
        try {
            // Allowed first: the receiver refuses a route it does not know, and Outline may deliver at once.
            routes.ifAvailable(projection -> projection.allow(route.route()));
            String subscriptionId = outlineApiClient.createWebhookSubscription(
                    serverUrl,
                    token,
                    SUBSCRIPTION_NAME,
                    deliveryUrl,
                    route.signingSecret(),
                    OutlineWebhookEvents.SUBSCRIBED_EVENTS);
            if (subscriptionId == null || subscriptionId.isBlank()) {
                log.warn("outline.webhook: register returned no subscription id for workspaceId={}", workspaceId);
                return false;
            }
            boolean stored = connectionService
                    .updateConfig(workspaceId, IntegrationKind.OUTLINE, cfg -> {
                        if (!(cfg instanceof ConnectionConfig.OutlineConfig outlineCfg)) {
                            throw new IllegalStateException("Expected OutlineConfig on workspace=" + workspaceId);
                        }
                        return outlineCfg.withWebhookSubscription(subscriptionId, route.route());
                    })
                    .isPresent();
            if (!stored) {
                // The connection left ACTIVE meanwhile: nothing holds this subscription, so it must not stay upstream.
                log.warn(
                        "outline.webhook: connection of workspaceId={} is no longer active; deleting the new subscription",
                        workspaceId);
                deleteQuietly(serverUrl, token, subscriptionId);
                return false;
            }
            log.info("outline.webhook: registered subscription {} for workspaceId={}", subscriptionId, workspaceId);
            // The subject the scope consumer filters on is derived from this id — reconcile now instead of
            // waiting for a restart (fresh register) or the next 6h reconcile (self-heal re-register).
            reconcileScopeConsumer(workspaceId);
            return true;
        } catch (RuntimeException e) {
            // Best-effort: the periodic reconcile still keeps the mirror fresh without a subscription.
            log.warn("outline.webhook: registration failed for workspaceId={}: {}", workspaceId, e.toString());
            return false;
        }
    }

    private String deliveryUrl(String route) {
        return externalUrl.replaceAll("/+$", "") + OutlineWebhookController.PATH_PREFIX + route;
    }

    /**
     * Deletes a subscription upstream. A failure leaves it posting to a route that the receiver refuses once the route
     * left the projection, so Outline disables it; it is still counted, because Outline may first retry it for a while.
     */
    private void deleteQuietly(String serverUrl, String token, String subscriptionId) {
        try {
            outlineApiClient.deleteWebhookSubscription(serverUrl, token, subscriptionId);
        } catch (RuntimeException e) {
            failedDeletions.increment();
            log.warn("outline.webhook: could not delete subscription {}: {}", subscriptionId, e.toString());
        }
    }

    /**
     * Deactivation-time teardown: the connection just left ACTIVE, so it is resolved by id regardless of
     * state. SUSPENDED connections still carry credentials and get a real upstream delete; UNINSTALLED
     * ones had their credentials purged — then this only logs; the receiver refuses the orphaned subscription's
     * route once the projection drops it, and Outline disables the subscription. The stored id/route stay on the row (the ACTIVE-scoped config mutator no
     * longer reaches it); reactivation's {@link #ensureSubscription} self-heal replaces them.
     *
     * <p>Runs {@link Propagation#NOT_SUPPORTED}, matching {@code GitLabWebhookService}: the upstream
     * DELETE is an external HTTP round-trip that must not hold a database transaction open.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregister(long workspaceId, long connectionId) {
        boolean hadSubscription;
        try {
            Optional<Runnable> deletion = prepareDeregistration(workspaceId, connectionId);
            deletion.ifPresent(Runnable::run);
            hadSubscription = deletion.isPresent();
        } catch (RuntimeException e) {
            log.warn("outline.webhook: deregistration failed for connectionId={}: {}", connectionId, e.toString());
            hadSubscription = true;
        }
        if (hadSubscription) {
            reconcileScopeConsumer(workspaceId);
        }
    }

    /**
     * Reads the connection's stored subscription and token and returns the Outline call that deletes the
     * subscription, which touches no database. Empty when no subscription is stored; throws when one is
     * stored but cannot be deleted with what the connection holds.
     */
    public Optional<Runnable> prepareDeregistration(long workspaceId, long connectionId) {
        Optional<Connection> connection = connectionService.findInWorkspace(workspaceId, connectionId);
        if (connection.isEmpty() || !(connection.get().getConfig() instanceof ConnectionConfig.OutlineConfig config)) {
            return Optional.empty();
        }
        String subscriptionId = config.webhookSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        String serverUrl = config.serverUrl();
        Optional<BearerToken> bearer = connectionService.findBearerToken(workspaceId, connectionId);
        if (serverUrl == null || serverUrl.isBlank() || bearer.isEmpty()) {
            throw new IllegalStateException("Outline webhook credentials are unavailable");
        }
        String token = bearer.get().token();
        return Optional.of(() -> {
            outlineApiClient.deleteWebhookSubscription(serverUrl, token, subscriptionId);
            log.info(
                    "outline.webhook: deleted subscription {} for deactivated connectionId={}",
                    subscriptionId,
                    connectionId);
        });
    }
}
