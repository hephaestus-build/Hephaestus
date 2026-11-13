package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationDispatchScope;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ProviderEventContentionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ProviderEventStore store;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventTraceContext traceContext;

    @Autowired
    private ProviderEventConsumerProperties properties;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @CsvSource({
        "GITHUB,true",
        "GITHUB,false",
        "GITLAB,true",
        "GITLAB,false",
        "SLACK,true",
        "SLACK,false",
        "OUTLINE,true",
        "OUTLINE,false"
    })
    void shouldRetryRenewalContentionWithoutFailingAnotherReplicasFanout(IntegrationKind kind, boolean namedStep)
            throws Exception {
        String unique = UUID.randomUUID().toString();
        Workspace first = workspaces.save(WorkspaceTestFixtures.activeWorkspace("busy-first-" + unique));
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("busy-other-" + unique));
        String provider = kind.name().toLowerCase(Locale.ROOT);
        String subject = provider + ".fixture." + unique + ".message";
        String delivery = UUID.randomUUID().toString();
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        store.store(List.of(
                new ProviderEventStore.Arrival(first.getId(), provider, subject, delivery, Map.of(), payload),
                new ProviderEventStore.Arrival(other.getId(), provider, subject, delivery, Map.of(), payload)));
        var claimed = store.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.workspaceId()).isEqualTo(first.getId());
        String copy = "fixture/" + unique;
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(kind, "message"));
        doAnswer(invocation -> {
                    Long workspaceId =
                            IntegrationDispatchScope.current().orElseThrow().workspaceId();
                    Workspace target = Objects.equals(workspaceId, first.getId()) ? first : other;
                    transactions.executeWithoutResult(status -> {
                        if (namedStep && !DeliveryInbox.claim("persist")) return;
                        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(target, copy, 42));
                    });
                    return null;
                })
                .when(handler)
                .onMessage(any(ProviderEvent.class));
        var registry = new SimpleMeterRegistry();
        try (var logs = new LogCapture(ProviderEventConsumer.class)) {
            var replicaOne = consumer(handler, subject, registry);
            var replicaTwo = consumer(handler, subject, registry);
            try {
                // Hold the actual renewal UPDATE open while the handler reaches its commit fence.
                var renewal = HeldTransaction.holdIn(
                        transactions,
                        jdbc,
                        () -> assertThat(store.renew(claimed.id(), claimed.leaseEpoch(), Duration.ofMinutes(2)))
                                .isTrue());
                var handling = CompletableFuture.runAsync(() -> replicaOne.handle(claimed));
                renewal.commitAfter(handling, () -> {
                    assertThat(copies(first, copy)).isZero();
                    assertThat(store.hasStep(claimed.id(), "persist")).isFalse();
                    assertThat(replicaTwo.claimAndHandle()).isTrue();
                    assertThat(copies(other, copy)).isEqualTo(1);
                    assertThat(replicaTwo.claimAndHandle()).isFalse();
                });
                handling.get(20, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                        .isEqualTo("QUEUED");
                assertThat(jdbc.queryForObject(
                                "SELECT failures FROM provider_event WHERE id=?", Integer.class, claimed.id()))
                        .isZero();
                assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
                assertThat(registry.get(IntegrationCoreMetrics.INTEGRATION_CONSUMER_RETRY)
                                .tag("kind", provider)
                                .counter()
                                .count())
                        .isEqualTo(1);
                assertThat(registry.get(IntegrationCoreMetrics.INTEGRATION_MESSAGE_PROCESS_DURATION)
                                .tags("kind", provider, "outcome", "retried")
                                .timer()
                                .count())
                        .isEqualTo(1);
                jdbc.update("UPDATE provider_event SET available_at=clock_timestamp() WHERE id=?", claimed.id());
                assertThat(replicaTwo.claimAndHandle()).isTrue();
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                        .isEqualTo("DONE");
                assertThat(copies(first, copy)).isEqualTo(1);
                assertThat(copies(other, copy)).isEqualTo(1);
                assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
            } finally {
                replicaOne.close();
                replicaTwo.close();
                registry.close();
            }
        }
    }

    private long copies(Workspace workspace, String name) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=? AND name_with_owner=?",
                Long.class,
                workspace.getId(),
                name));
    }

    private ProviderEventConsumer consumer(
            IntegrationMessageHandler handler, String subject, SimpleMeterRegistry registry) {
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.dispatch(subject)).thenReturn(Optional.of(handler));
        return new ProviderEventConsumer(
                store,
                dispatcher,
                List.of(),
                inbox,
                new IntegrationPoisonHandler(properties, registry, store),
                traceContext,
                new IntegrationMessageMeters(registry, clock),
                mock(ConnectionActivityRecorder.class),
                new ProviderEventNotifications(),
                properties,
                mock(ApplicationEventPublisher.class),
                clock);
    }
}
