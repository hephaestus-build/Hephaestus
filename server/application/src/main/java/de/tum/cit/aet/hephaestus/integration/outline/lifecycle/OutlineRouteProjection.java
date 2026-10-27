package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.webhook.ProjectedKeys;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import io.nats.client.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes the routes of the current Outline subscriptions to the NATS key-value bucket that the receiver reads, so the
 * receiver refuses a route that no ACTIVE connection holds. Outline then disables an orphaned subscription (one whose
 * connection was disconnected, or whose deletion failed) instead of streaming document text into the receiver.
 *
 * <p>A route is allowed before its subscription is registered, so the first delivery is never refused, and a rebuild
 * keeps it for {@link #REGISTRATION_GRACE} until the connection stores it. A rebuild at start and every minute removes
 * routes that left the database, and recreates the bucket when NATS lost it.
 *
 * <p>The grace lives in this process's memory, so exactly one server process may allow and rebuild (ADR 0066): another
 * process's rebuild would remove a route allowed here until its connection stores it.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(
        name = {"hephaestus.integration.outline.enabled", "hephaestus.sync.nats.enabled"},
        havingValue = "true")
public class OutlineRouteProjection {

    private static final Logger log = LoggerFactory.getLogger(OutlineRouteProjection.class);

    /** How long an allowed route survives rebuilds before its connection must hold it. */
    static final Duration REGISTRATION_GRACE = Duration.ofMinutes(5);

    private final ProjectedKeys routes;
    private final ConnectionRepository connections;
    private final Clock clock;

    /** Routes allowed but maybe not stored yet, by when they were allowed. Guarded by {@code this}. */
    private final Map<String, Instant> allowed = new HashMap<>();

    public OutlineRouteProjection(Connection nats, ConnectionRepository connections, Clock clock) {
        this.routes = new ProjectedKeys(nats, OutlineWebhookController.ROUTES_BUCKET);
        this.connections = connections;
        this.clock = clock;
    }

    /** Lets the receiver accept {@code route}; throws {@link ProjectedKeys.UnavailableException} when it cannot. */
    public synchronized void allow(String route) {
        String key = OutlineWebhookController.routeKey(route);
        routes.add(key);
        allowed.put(key, clock.instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "outline-route-projection", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @WorkspaceAgnostic(
            "Projects the webhook routes of every workspace's Outline connection; a route names no workspace")
    public synchronized void rebuild() {
        Set<String> current = connections.findActiveOutlineRoutes().stream()
                .map(ConnectionRepository.OutlineRouteRow::getRoute)
                .filter(Objects::nonNull)
                .map(OutlineWebhookController::routeKey)
                .collect(Collectors.toCollection(HashSet::new));
        Instant cutoff = clock.instant().minus(REGISTRATION_GRACE);
        allowed.values().removeIf(at -> at.isBefore(cutoff));
        current.addAll(allowed.keySet());
        try {
            routes.replaceAll(current);
        } catch (ProjectedKeys.UnavailableException e) {
            log.warn("Outline route projection rebuild failed: bucket={}, cause={}", e.bucket(), e.causeClass());
        }
    }
}
