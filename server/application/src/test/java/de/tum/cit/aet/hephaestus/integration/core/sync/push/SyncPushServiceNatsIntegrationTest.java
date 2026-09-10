package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class SyncPushServiceNatsIntegrationTest extends BaseIntegrationTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long CONNECTION_ID = 7L;

    private Connection firstConnection;
    private Connection secondConnection;
    private SyncPushService firstService;
    private SyncPushService secondService;
    private SyncEventHub firstHub;
    private SyncEventHub secondHub;
    private RecordingEmitter firstEmitter;
    private RecordingEmitter secondEmitter;

    @BeforeEach
    void setUp() throws Exception {
        String server = NatsTestContainer.getServerUrl();
        firstConnection = Nats.connect(server);
        secondConnection = Nats.connect(server);
        ObjectMapper mapper = new ObjectMapper();
        firstEmitter = new RecordingEmitter();
        secondEmitter = new RecordingEmitter();
        firstHub = new SyncEventHub(mapper, new SimpleMeterRegistry(), Duration.ofMillis(10), () -> firstEmitter);
        secondHub = new SyncEventHub(mapper, new SimpleMeterRegistry(), Duration.ofMillis(10), () -> secondEmitter);
        firstHub.subscribe(WORKSPACE_ID);
        secondHub.subscribe(WORKSPACE_ID);
        firstService = new SyncPushService(
                firstHub, mapper, objectProviderReturning(firstConnection), new SimpleMeterRegistry());
        firstService.subscribeIfNatsAvailable();
        secondService = new SyncPushService(
                secondHub, mapper, objectProviderReturning(secondConnection), new SimpleMeterRegistry());
        secondService.subscribeIfNatsAvailable();
        firstConnection.flush(Duration.ofSeconds(2));
        secondConnection.flush(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (firstService != null) firstService.shutdown();
        if (secondService != null) secondService.shutdown();
        if (firstHub != null) firstHub.shutdown();
        if (secondHub != null) secondHub.shutdown();
        if (firstConnection != null) firstConnection.close();
        if (secondConnection != null) secondConnection.close();
    }

    @Test
    void publishedInvalidationReachesOriginAndSiblingReplica() throws Exception {
        SyncStateChangedEvent event = new SyncStateChangedEvent(
                WORKSPACE_ID, CONNECTION_ID, IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.JOB);

        firstService.onSyncStateChanged(event);
        firstConnection.flush(Duration.ofSeconds(2));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(firstEmitter.frames())
                    .anySatisfy(frame ->
                            assertThat(frame).contains("\"scope\":\"job\"").contains("\"connectionId\":7"));
            assertThat(secondEmitter.frames())
                    .anySatisfy(frame ->
                            assertThat(frame).contains("\"scope\":\"job\"").contains("\"connectionId\":7"));
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Connection> objectProviderReturning(Connection connection) {
        ObjectProvider<Connection> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connection);
        return provider;
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder wire = new StringBuilder();
            builder.build().forEach(entry -> wire.append(entry.getData()));
            frames.add(wire.toString());
        }

        List<String> frames() {
            return List.copyOf(frames);
        }
    }
}
