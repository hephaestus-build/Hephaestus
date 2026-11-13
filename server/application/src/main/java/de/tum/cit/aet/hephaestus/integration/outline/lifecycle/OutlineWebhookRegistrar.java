package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.metrics.OutlineMetrics;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineRouteCredential;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookEvents;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Registers and tears down a workspace's Outline change-notification subscription. Runs at connect time and
 * as a self-heal inside every full reconcile, because Outline auto-disables a subscription after repeated
 * delivery failures. Each registration gets a new route ({@link OutlineRouteCredential}); the subscription id and
 * the route are stored on the Connection, and the signing secret is derived from the route, never stored.
 *
 * <p>Best-effort and never throws: without a routing key or token, or on any Outline-side failure, the
 * periodic reconcile remains the source of truth and a missing subscription only costs freshness.
 *
 * <p>The single seam through which a subscription id changes. The receiver reads the id from the connection when a
 * delivery arrives, so a stored id takes effect with the delivery after its commit.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(OutlineWebhookRegistrar.class);

    /** Subscription display name in the owner's Outline settings. */
    private static final String SUBSCRIPTION_NAME = "Hephaestus documentation sync";

    private final ConnectionService connectionService;
    private final ProviderResourceRepairs repairs;
    private final OutlineWebhookClient outlineApiClient;
    private final OutlineRouteCredential credential;
    private final PublicApiUrls publicApiUrls;

    private final ConnectionRepository connections;

    private final ClusterLock locks;

    /** How long a registration waits for another of the same workspace; one that waits longer is left to the heal. */
    private static final Duration REGISTRATION_WAIT = Duration.ofMinutes(1);

    /** ACTIVE connections whose subscription is missing or not on a current route, as of the last heal. */
    private final AtomicInteger unrouted = new AtomicInteger();

    public OutlineWebhookRegistrar(
            ConnectionService connectionService,
            ProviderResourceRepairs repairs,
            OutlineWebhookClient outlineApiClient,
            OutlineRouteCredential credential,
            PublicApiUrls publicApiUrls,
            ConnectionRepository connections,
            ClusterLock locks,
            MeterRegistry meterRegistry) {
        this.connectionService = connectionService;
        this.repairs = repairs;
        this.outlineApiClient = outlineApiClient;
        this.credential = credential;
        this.publicApiUrls = publicApiUrls;
        this.connections = connections;
        this.locks = locks;
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
    @SchedulerLock(name = "outline-webhook-heal", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic(
            "Heals the Outline subscription of every workspace; each registration is scoped to its workspace")
    public void healRoutes() {
        if (!credential.isConfigured()) {
            return;
        }
        int left = 0;
        for (ConnectionRepository.OutlineRouteRow row : connections.findActiveOutlineRoutes()) {
            String route = row.getRoute();
            if (route != null && credential.isCurrent(route)) {
                continue;
            }
            try {
                ensureSubscription(row.getWorkspaceId());
            } catch (RuntimeException e) {
                log.warn(
                        "outline.webhook: heal failed for workspaceId={}: {}",
                        row.getWorkspaceId(),
                        e.getClass().getSimpleName());
            }
            left++;
        }
        unrouted.set(left);
    }

    /**
     * Ensures the workspace has a live change-notification subscription. Without a stored id this
     * registers one (a quiet no-op when the integration has no routing key, ACTIVE Outline connection,
     * server URL, or token). With a stored id it verifies the subscription upstream: gone,
     * {@code enabled=false} (Outline disables a subscription whose deliveries fail too often), posting to
     * another URL, or on a route of a retired routing key is replaced: a new subscription is registered and
     * stored, then the old one is deleted upstream; an unverifiable upstream (listing failed) changes nothing.
     *
     * <p>Serialized per workspace on every server ({@link ClusterLock}): the heal, the activation listener and the
     * sync can reach the same route-less workspace at once, on one server or on two, and two registrations would leave
     * the loser's subscription upstream. A call that cannot take the lock leaves the workspace to the next heal.
     */
    public void ensureSubscription(long workspaceId) {
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Provider calls require no active transaction");
        String skipped;
        try {
            skipped = locks.run("outline-subscription:" + workspaceId, REGISTRATION_WAIT, () -> ensure(workspaceId))
                    ? null
                    : "lockBusy";
        } catch (ClusterLock.UnavailableException e) {
            skipped = e.reason();
        }
        if (skipped != null) {
            log.warn("outline.webhook: subscription check skipped for workspaceId={}: reason={}", workspaceId, skipped);
        }
    }

    private void ensure(long workspaceId) {
        if (!credential.isConfigured()) {
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
            repairs.retireCurrentHook(workspaceId, active.get().getId());
            register(workspaceId, serverUrl, token);
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
            log.warn(
                    "outline.webhook: could not verify subscription {}: {}",
                    subscriptionId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Registers a subscription on a new route and stores it; {@code false} when Outline did not register one. */
    private boolean register(long workspaceId, String serverUrl, String token) {
        // Deliveries land on the receiver's route endpoint, are verified against the route's derived secret, and are
        // stored in the provider event inbox (ADR 0023 §3).
        OutlineRouteCredential.Issued route = credential.issue();
        String deliveryUrl = deliveryUrl(route.route());
        Connection owner = connectionService
                .findActive(workspaceId, IntegrationKind.OUTLINE)
                .orElseThrow();
        String marker = UUID.randomUUID().toString();
        UUID registration = repairs.beginRegistration(
                workspaceId,
                owner.getId(),
                new Resource(
                        IntegrationKind.OUTLINE,
                        serverUrl,
                        ResourceType.REGISTRATION,
                        "",
                        marker,
                        ProviderResourceAdapter.fingerprint(deliveryUrl)));
        String subscriptionId = null;
        try {
            subscriptionId = outlineApiClient.createWebhookSubscription(
                    serverUrl,
                    token,
                    SUBSCRIPTION_NAME + " [" + marker + "]",
                    deliveryUrl,
                    route.signingSecret(),
                    OutlineWebhookEvents.SUBSCRIBED_EVENTS);
            if (subscriptionId == null || subscriptionId.isBlank()) {
                log.warn("outline.webhook: register returned no subscription id for workspaceId={}", workspaceId);
                return false;
            }
            String created = subscriptionId;
            boolean stored = repairs.completeRegistration(workspaceId, owner.getId(), registration, cfg -> {
                if (!(cfg instanceof ConnectionConfig.OutlineConfig outlineCfg)) {
                    throw new IllegalStateException("Expected OutlineConfig on workspace=" + workspaceId);
                }
                return outlineCfg.withWebhookSubscription(created, route.route());
            });
            if (!stored) {
                // The connection left ACTIVE meanwhile: nothing holds this subscription, so it must not stay upstream.
                log.warn(
                        "outline.webhook: connection of workspaceId={} is no longer active; deleting the new subscription",
                        workspaceId);
                return false;
            }
            log.info("outline.webhook: registered subscription {} for workspaceId={}", subscriptionId, workspaceId);
            return true;
        } catch (RuntimeException e) {
            // Best-effort: the periodic reconcile still keeps the mirror fresh without a subscription.
            log.warn(
                    "outline.webhook: registration failed for workspaceId={}: {}",
                    workspaceId,
                    e.getClass().getSimpleName());

            return false;
        }
    }

    private String deliveryUrl(String route) {
        int separator = route.indexOf('/');
        return publicApiUrls
                .originUri(
                        OutlineWebhookController.PATH_PREFIX + "{keyId}/{routeId}",
                        route.substring(0, separator),
                        route.substring(separator + 1))
                .toString();
    }
}
