package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueEntry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The grace window is what keeps a new subscription's first deliveries from a 410: a route allowed before its
 * connection stores it survives every rebuild for {@link OutlineRouteProjection#REGISTRATION_GRACE}, on whichever
 * server it was allowed, and a rebuild never drops a route because the database could not be read.
 */
class OutlineRouteProjectionTest extends BaseUnitTest {

    private static final String STORED = "k1/storedRoute";
    private static final String NEW = "k1/newRoute";

    @Mock
    private Connection nats;

    @Mock
    private KeyValueManagement management;

    @Mock
    private KeyValue bucket;

    @Mock
    private ConnectionRepository connections;

    private final Set<String> stored = new HashSet<>();
    /** When the broker stamped each key's write. */
    private final Map<String, Instant> written = new HashMap<>();

    private final MutableClock clock = MutableClock.atFixedInstant();
    private OutlineRouteProjection projection;

    @BeforeEach
    void setUp() throws Exception {
        when(nats.keyValueManagement()).thenReturn(management);
        when(management.getBucketNames()).thenReturn(List.of(OutlineWebhookController.ROUTES_BUCKET));
        when(nats.keyValue(OutlineWebhookController.ROUTES_BUCKET)).thenReturn(bucket);
        lenient().when(bucket.keys()).thenAnswer(invocation -> new ArrayList<>(stored));
        lenient().when(bucket.put(anyString(), any(byte[].class))).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            written.put(invocation.getArgument(0), clock.instant());
            return 1L;
        });
        lenient()
                .doAnswer(invocation -> {
                    written.remove(invocation.<String>getArgument(0));
                    return stored.remove(invocation.<String>getArgument(0));
                })
                .when(bucket)
                .delete(anyString());
        lenient().when(bucket.get(anyString())).thenAnswer(invocation -> {
            Instant at = written.get(invocation.<String>getArgument(0));
            if (at == null) return null;
            KeyValueEntry entry = mock(KeyValueEntry.class);
            when(entry.getCreated()).thenReturn(at.atZone(ZoneOffset.UTC));
            return entry;
        });
        projection = new OutlineRouteProjection(nats, connections, clock);
    }

    @Test
    void shouldKeepANewRouteThroughRebuildsWhenItsConnectionHasNotStoredItYet() {
        databaseHolds(STORED);
        projection.allow(NEW);

        clock.advance(OutlineRouteProjection.REGISTRATION_GRACE.minusSeconds(1));
        projection.rebuild();

        assertThat(stored).containsExactlyInAnyOrder(key(STORED), key(NEW));
    }

    @Test
    void shouldDropANewRouteWhenItsConnectionNeverStoredItWithinTheGrace() {
        databaseHolds(STORED);
        projection.allow(NEW);

        clock.advance(OutlineRouteProjection.REGISTRATION_GRACE.plusSeconds(1));
        projection.rebuild();

        assertThat(stored).containsExactly(key(STORED));
    }

    @Test
    void shouldKeepAStoredRouteWhenItsGraceHasPassed() {
        projection.allow(STORED);
        databaseHolds(STORED);

        clock.advance(OutlineRouteProjection.REGISTRATION_GRACE.multipliedBy(2));
        projection.rebuild();

        assertThat(stored).containsExactly(key(STORED));
    }

    @Test
    void shouldKeepARouteThatAnotherServerAllowedWhenThisServerRebuilds() {
        var otherServer = new OutlineRouteProjection(nats, connections, clock);
        databaseHolds(STORED);
        otherServer.allow(NEW);

        clock.advance(OutlineRouteProjection.REGISTRATION_GRACE.minusSeconds(1));
        projection.rebuild();

        assertThat(stored).containsExactlyInAnyOrder(key(STORED), key(NEW));
    }

    @Test
    void shouldLeaveTheBucketAsItIsWhenTheDatabaseCannotBeRead() {
        databaseHolds(STORED);
        projection.rebuild();
        when(connections.findActiveOutlineRoutes()).thenThrow(new DataAccessResourceFailureException("down"));

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(projection::rebuild).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(stored).containsExactly(key(STORED));
    }

    @Test
    void shouldProjectNoKeyForAnActiveConnectionWithoutARoute() {
        databaseHolds(STORED, null);

        projection.rebuild();

        assertThat(stored).containsExactly(key(STORED));
    }

    private void databaseHolds(@Nullable String... routes) {
        List<ConnectionRepository.OutlineRouteRow> rows = new ArrayList<>();
        for (String route : routes) {
            rows.add(new ConnectionRepository.OutlineRouteRow() {
                @Override
                public Long getWorkspaceId() {
                    return 1L;
                }

                @Override
                public @Nullable String getRoute() {
                    return route;
                }
            });
        }
        when(connections.findActiveOutlineRoutes()).thenReturn(rows);
    }

    private static String key(String route) {
        return OutlineWebhookController.routeKey(route);
    }
}
