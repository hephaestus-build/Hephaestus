package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationDispatchScope;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
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

    @Autowired
    private PersonDataCopyFence copyFence;

    private enum Hold {
        RENEWAL,
        EVENT,
        WORKSPACE
    }

    @ParameterizedTest
    @CsvSource({
        "GITHUB,true,RENEWAL",
        "GITHUB,true,EVENT",
        "GITHUB,true,WORKSPACE",
        "GITHUB,false,RENEWAL",
        "GITHUB,false,EVENT",
        "GITLAB,true,RENEWAL",
        "GITLAB,true,EVENT",
        "GITLAB,true,WORKSPACE",
        "GITLAB,false,RENEWAL",
        "GITLAB,false,EVENT",
        "SLACK,true,RENEWAL",
        "SLACK,true,EVENT",
        "SLACK,true,WORKSPACE",
        "SLACK,false,RENEWAL",
        "SLACK,false,EVENT",
        "OUTLINE,true,RENEWAL",
        "OUTLINE,true,EVENT",
        "OUTLINE,true,WORKSPACE",
        "OUTLINE,false,RENEWAL",
        "OUTLINE,false,EVENT",
    })
    void shouldRetryFenceContentionWithoutFailingAnotherReplicasFanout(
            IntegrationKind kind, boolean namedStep, Hold held) throws Exception {
        exerciseContention(kind, namedStep, held, false);
    }

    @ParameterizedTest
    @EnumSource(Hold.class)
    void shouldPropagateRejectedCommitCaptureWithoutAdapterWarnings(Hold held) throws Exception {
        exerciseContention(IntegrationKind.GITHUB, true, held, true);
    }

    private void exerciseContention(IntegrationKind kind, boolean namedStep, Hold held, boolean realCapture)
            throws Exception {
        String unique = UUID.randomUUID().toString();
        Workspace first = workspaces.save(WorkspaceTestFixtures.activeWorkspace("busy-first-" + unique));
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("busy-other-" + unique));
        String provider = kind.name().toLowerCase(Locale.ROOT);
        String subject = provider + ".fixture." + unique + ".message";
        String delivery = UUID.randomUUID().toString();
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        String laterDelivery = delivery + "-later";
        store.store(List.of(
                new ProviderEventStore.Arrival(first.getId(), provider, subject, delivery, Map.of(), payload),
                new ProviderEventStore.Arrival(first.getId(), provider, subject, laterDelivery, Map.of(), payload),
                new ProviderEventStore.Arrival(other.getId(), provider, subject, delivery, Map.of(), payload)));
        // Prioritize only this fixture's rows; unrelated queued events stay untouched.
        jdbc.update(
                "UPDATE provider_event SET available_at='-infinity'::timestamptz WHERE delivery_key IN (?,?)",
                delivery,
                laterDelivery);
        long laterId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM provider_event WHERE delivery_key=?", Long.class, laterDelivery));
        var claimed = store.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.workspaceId()).isEqualTo(first.getId());
        String copy = "fixture/" + unique;
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(kind, "message"));
        doAnswer(invocation -> {
                    Long workspaceId =
                            IntegrationDispatchScope.current().orElseThrow().workspaceId();
                    Workspace target = Objects.equals(workspaceId, first.getId()) ? first : other;
                    if (realCapture) captureCopy(target, copy, unique.replace("-", "") + "a".repeat(8));
                    else
                        transactions.executeWithoutResult(status -> {
                            if (namedStep && !DeliveryInbox.claim("persist")) return;
                            monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(target, copy, 42));
                        });
                    return null;
                })
                .when(handler)
                .onMessage(any(ProviderEvent.class));
        var registry = new SimpleMeterRegistry();
        try (var logs = new LogCapture(ProviderEventConsumer.class);
                var captureLogs = new LogCapture(CommitDetailsPersister.class)) {
            var replicaOne = consumer(handler, subject, registry);
            var replicaTwo = consumer(handler, subject, registry);
            try {
                // A second database session holds the selected row through the handler's fence attempt.
                var renewal = HeldTransaction.holdIn(transactions, jdbc, () -> {
                    switch (held) {
                        case RENEWAL ->
                            assertThat(store.renew(claimed.id(), claimed.leaseEpoch(), Duration.ofMinutes(2)))
                                    .isTrue();
                        case EVENT ->
                            jdbc.queryForObject(
                                    "SELECT id FROM provider_event WHERE id=? FOR UPDATE", Long.class, claimed.id());
                        case WORKSPACE ->
                            jdbc.queryForObject(
                                    "SELECT id FROM workspace WHERE id=? FOR UPDATE", Long.class, first.getId());
                    }
                });
                var handling = CompletableFuture.runAsync(() -> replicaOne.handle(claimed));
                renewal.commitAfter(handling, () -> {
                    if (held == Hold.WORKSPACE)
                        jdbc.update(
                                "UPDATE provider_event SET available_at=clock_timestamp()+interval '1 hour' WHERE id=?",
                                claimed.id());
                    assertThat(copies(first, copy)).isZero();
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM provider_event_step WHERE event_id=?",
                                    Long.class,
                                    claimed.id()))
                            .isZero();
                    assertThat(replicaTwo.claimAndHandle()).isTrue();
                    assertThat(copies(other, copy)).isEqualTo(1);
                });
                handling.get(20, TimeUnit.SECONDS);
                jdbc.update(
                        "UPDATE provider_event SET available_at=clock_timestamp()+interval '1 hour' WHERE id=?",
                        claimed.id());
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                        .isEqualTo("QUEUED");
                assertThat(jdbc.queryForObject(
                                "SELECT failures FROM provider_event WHERE id=?", Integer.class, claimed.id()))
                        .isZero();
                assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
                assertThat(captureLogs.messagesAtLeast(Level.WARN)).isEmpty();
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
                jdbc.update("UPDATE provider_event SET available_at='-infinity'::timestamptz WHERE id=?", claimed.id());
                assertThat(replicaTwo.claimAndHandle()).isTrue();
                assertThat(jdbc.queryForObject(
                                "SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                        .isEqualTo("DONE");
                assertThat(copies(first, copy)).isEqualTo(1);
                assertThat(copies(other, copy)).isEqualTo(1);
                var later = store.claim(Duration.ofMinutes(2)).orElseThrow();
                assertThat(later.id()).isEqualTo(laterId);
                assertThat(store.complete(later.id(), later.leaseEpoch())).isTrue();
                assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
                assertThat(captureLogs.messagesAtLeast(Level.WARN)).isEmpty();
            } finally {
                replicaOne.close();
                replicaTwo.close();
                registry.close();
            }
        }
    }

    private void captureCopy(Workspace target, String copy, String sha) {
        var commits = mock(CommitRepository.class);
        when(commits.findByShaAndRepositoryId(sha, 42L)).thenReturn(Optional.of(TestEntities.commit(42L, sha)));
        var persister = new CommitDetailsPersister(commits, transactions, mock(ApplicationEventPublisher.class));
        var details = new CommitDetails(
                sha,
                "fixture",
                null,
                "Author",
                "author@example.invalid",
                Instant.EPOCH,
                "Committer",
                "committer@example.invalid",
                Instant.EPOCH,
                0,
                0,
                0,
                List.of(),
                List.of(),
                "fixture");
        var origin = new CommitDetailsPersister.Origin(
                target.getId(),
                DataSource.WEBHOOK,
                IdentityProviderType.GITHUB,
                value -> value,
                email -> null,
                (commit, captured, author, committer) ->
                        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(target, copy, 42)));
        persister.persist(details, TestEntities.repository(42L, "fixture/repo", "main"), origin);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("try") // The copy fence models an erasure pass while the consumer attempts its write.
    void shouldRetryAnErasureFenceWithoutHandlerErrors(boolean namedStep) {
        String unique = UUID.randomUUID().toString();
        Workspace target = workspaces.save(WorkspaceTestFixtures.activeWorkspace("copy-busy-" + unique));
        String subject = "github.fixture." + unique + ".message";
        long id = StoredEventHandling.stored(store, jdbc, target.getId(), subject);
        jdbc.update("UPDATE provider_event SET available_at='-infinity'::timestamptz WHERE id=?", id);
        var claimed = store.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITHUB, "message"));
        doAnswer(call -> {
                    transactions.executeWithoutResult(status -> {
                        if (namedStep) DeliveryInbox.claim("persist");
                        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(target, unique, 42));
                    });
                    return null;
                })
                .when(handler)
                .onMessage(any(ProviderEvent.class));
        var registry = new SimpleMeterRegistry();
        var consumer = consumer(handler, subject, registry);
        try (var logs = new LogCapture(ProviderEventConsumer.class)) {
            try (var erasure = copyFence.erase()) {
                consumer.handle(claimed);
            }
            assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, id))
                    .isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT failures FROM provider_event WHERE id=?", Integer.class, id))
                    .isZero();
            assertThat(copies(target, unique)).isZero();
            jdbc.update("UPDATE provider_event SET available_at='-infinity'::timestamptz WHERE id=?", id);
            assertThat(consumer.claimAndHandle()).isTrue();
            assertThat(copies(target, unique)).isEqualTo(1);
            assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
        } finally {
            consumer.close();
            registry.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true"})
    void shouldRetryLockContentionWithoutAHandlerErrorAndWarnWhenItRecurs(boolean translated, boolean recurring) {
        String unique = UUID.randomUUID().toString();
        Workspace target = workspaces.save(WorkspaceTestFixtures.activeWorkspace("lock-timeout-" + unique));
        String subject = "github.fixture." + unique + ".message";
        long id = StoredEventHandling.stored(store, jdbc, target.getId(), subject);
        // The claim counts one more attempt.
        int earlierAttempts = recurring ? properties.poison().maxAttempts() - 1 : 0;
        jdbc.update(
                "UPDATE provider_event SET available_at='-infinity'::timestamptz, attempt=? WHERE id=?",
                earlierAttempts,
                id);
        var claimed = store.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITHUB, "message"));
        RuntimeException lockTimeout = translated
                ? new CannotAcquireLockException("canceling statement due to lock timeout")
                : new IllegalStateException(
                        "untranslated", new SQLException("canceling statement due to lock timeout", "55P03"));
        doThrow(lockTimeout).when(handler).onMessage(any(ProviderEvent.class));
        var registry = new SimpleMeterRegistry();
        var consumer = consumer(handler, subject, registry);
        try (var logs = new LogCapture(ProviderEventConsumer.class)) {
            consumer.handle(claimed);

            assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, id))
                    .isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT failures FROM provider_event WHERE id=?", Integer.class, id))
                    .isZero();
            assertThat(logs.messagesAtLeast(Level.ERROR)).isEmpty();
            if (recurring)
                assertThat(logs.messagesAtLeast(Level.WARN)).containsExactly("Provider event waits for a lock");
            else assertThat(logs.messagesAtLeast(Level.WARN)).isEmpty();
        } finally {
            consumer.close();
            registry.close();
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
