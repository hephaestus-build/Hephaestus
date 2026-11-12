package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationCoreConnectionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackChannelDeletedMessageHandler;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ProviderEventErasureIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ProviderEventStore store;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PersonDataCopyFence copyFence;

    @Autowired
    private IntegrationCoreConnectionPersonDataCatalog catalog;

    @Autowired
    private IdentityProviderRepository providers;

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
    private EventPayloadDeserializer deserializer;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "GITLAB", "SLACK", "OUTLINE"})
    void shouldDropLateWritesWhenLeasedPayloadsAreErasedAcrossWorkspaces(IntegrationKind kind) throws Exception {
        lateWriteAfterErasure(kind, true);
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "GITLAB", "SLACK", "OUTLINE"})
    void shouldFenceNaturalKeyWritesWithoutAnExplicitStepAfterErasure(IntegrationKind kind) throws Exception {
        lateWriteAfterErasure(kind, false);
    }

    @SuppressWarnings("try") // The erasure lease covers all selected stores, as PersonDataService does.
    private void lateWriteAfterErasure(IntegrationKind kind, boolean namedStep) throws Exception {
        String unique = UUID.randomUUID().toString();
        Workspace target = workspaces.save(WorkspaceTestFixtures.activeWorkspace("erase-a-" + unique));
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("erase-b-" + unique));
        String origin = "https://" + unique + ".invalid";
        var provider = providers.save(new IdentityProvider(IdentityProviderType.valueOf(kind.name()), origin));
        String person = kind == IntegrationKind.GITHUB || kind == IntegrationKind.GITLAB ? "42" : "U42";
        String body = kind == IntegrationKind.GITHUB || kind == IntegrationKind.GITLAB
                ? "{\"sender\":{\"id\":42,\"login\":\"erased-person\"},\"text\":\"private-" + unique + "\"}"
                : "{\"user\":\"U42\",\"text\":\"private-" + unique + "\"}";
        String prefix = kind.name().toLowerCase(Locale.ROOT);
        String subject = prefix + ".T1." + unique + ".message";
        Map<String, String> headers = Map.of(ProviderEvent.SOURCE_SERVER_URL, origin);
        var held = leased(target, subject, headers, body);
        var samePersonElsewhere = leased(other, subject, headers, body);
        var unrelated = leased(other, prefix + ".T1.other-" + unique + ".message", headers, "{}");
        var scope = new PersonScope(
                null,
                List.of(new PersonIdentity(
                        Objects.requireNonNull(provider.getId()), person, kind == IntegrationKind.SLACK ? "T1" : null)),
                List.of());
        var preparationStarted = new CountDownLatch(1);
        var finishPreparation = new CountDownLatch(1);
        String derivedCopy = "private/" + unique;
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(kind, "message"));
        doAnswer(invocation -> {
                    preparationStarted.countDown();
                    await(finishPreparation);
                    transactions.executeWithoutResult(status -> {
                        if (namedStep && !DeliveryInbox.claim("persist")) return;
                        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(target, derivedCopy, 42));
                    });
                    return null;
                })
                .when(handler)
                .onMessage(held.event());
        var registry = new SimpleMeterRegistry();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var consumer = consumer(handler, subject, registry, mock(ConnectionActivityRecorder.class));
            try {
                var handling = workers.submit(() -> consumer.handle(held));
                try {
                    assertThat(preparationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                    try (var erasure = copyFence.erase()) {
                        transactions.executeWithoutResult(status -> {
                            var contributor = catalog.contributors().stream()
                                    .filter(candidate -> candidate.store().equals("provider_event"))
                                    .findFirst()
                                    .orElseThrow();
                            var selection = contributor.select(scope);
                            assertThat(selection.rows()).hasSize(2);
                            assertThat(contributor.erase(selection, scope)).isEqualTo(2);
                        });
                    }
                    assertThat(exists(held.id())).isFalse();
                    assertThat(exists(samePersonElsewhere.id())).isFalse();
                    assertThat(exists(unrelated.id())).isTrue();
                } finally {
                    finishPreparation.countDown();
                }
                handling.get(20, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=? AND name_with_owner=?",
                                Long.class,
                                target.getId(),
                                derivedCopy))
                        .isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM provider_event_step WHERE event_id IN (?, ?)",
                                Long.class,
                                held.id(),
                                samePersonElsewhere.id()))
                        .isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM provider_event WHERE headers->>?=? AND convert_from(payload,'UTF8') LIKE ?",
                                Long.class,
                                ProviderEvent.SOURCE_SERVER_URL,
                                origin,
                                "%private-" + unique + "%"))
                        .isZero();
                assertThat(registry.get(IntegrationCoreMetrics.INTEGRATION_MESSAGE_DROPPED)
                                .tag("kind", prefix)
                                .tag("reason", "erased-during-processing")
                                .counter()
                                .count())
                        .isEqualTo(1);
                assertThat(registry.find(IntegrationCoreMetrics.INTEGRATION_CONSUMER_RETRY)
                                .counter())
                        .isNull();
                assertThat(exists(held.id())).isFalse();
            } finally {
                finishPreparation.countDown();
                consumer.close();
            }
        }
    }

    @Test
    void shouldRejectAnOldLeaseEpochBeforeItWrites() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("epoch-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.epoch.issues", Map.of(), "{}");
        jdbc.update("UPDATE provider_event SET lease_epoch=2 WHERE id=?", event.id());
        assertThatThrownBy(() -> inbox.dispatch(
                        event.id(),
                        1,
                        Instant.now(),
                        () -> transactions.executeWithoutResult(status -> {
                            DeliveryInbox.claim("persist");
                            monitors.saveAndFlush(
                                    WorkspaceTestFixtures.repositoryMonitor(workspace, "must/not-exist", 42));
                        })))
                .isInstanceOf(ProviderEventFenceException.class)
                .hasMessage("lease-lost");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=?",
                        Long.class,
                        workspace.getId()))
                .isZero();
    }

    @Test
    void shouldFenceNestedIndependentWritesWithoutLockingAgainstItsOwnOuterStep() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("nested-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.nested.issues", Map.of(), "{}");
        var requiresNew = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inbox.dispatch(
                event.id(),
                1,
                Instant.now(),
                () -> transactions.executeWithoutResult(status -> {
                    assertThat(DeliveryInbox.claim("outer")).isTrue();
                    requiresNew.executeWithoutResult(inner -> monitors.saveAndFlush(
                            WorkspaceTestFixtures.repositoryMonitor(workspace, "survives/nested", 42)));
                }));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=?",
                        Long.class,
                        workspace.getId()))
                .isEqualTo(1);
        assertThat(store.hasStep(event.id(), "outer")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("try") // A separate erasure session must not wait for a writer that holds domain locks.
    void shouldRollBackWithoutWaitingWhenErasureOwnsTheCopyFence(boolean namedStep) throws Exception {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("busy-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.busy.issues", Map.of(), "{}");
        try (var erasure = copyFence.erase();
                var workers = Executors.newSingleThreadExecutor()) {
            var writing = workers.submit(() -> assertThatThrownBy(() -> inbox.dispatch(
                            event.id(),
                            1,
                            Instant.now(),
                            () -> transactions.executeWithoutResult(status -> {
                                if (namedStep) DeliveryInbox.claim("persist");
                                monitors.saveAndFlush(
                                        WorkspaceTestFixtures.repositoryMonitor(workspace, "must/not-exist", 42));
                            })))
                    .isInstanceOf(TransientDataAccessResourceException.class));
            writing.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=?",
                        Long.class,
                        workspace.getId()))
                .isZero();
        assertThat(store.hasStep(event.id(), "persist")).isFalse();
        assertThat(exists(event.id())).isTrue();
    }

    @Test
    void shouldRecordErasureAfterAConcurrentDeleteCommits() throws Exception {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deleting-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.deleting.issues", Map.of(), "{}");
        var deletion = HeldTransaction.holdIn(
                transactions, jdbc, () -> jdbc.update("DELETE FROM provider_event WHERE id=?", event.id()));
        var outcome = CompletableFuture.runAsync(() -> assertThatThrownBy(() -> store.assertLease(event.id(), 1))
                .isInstanceOf(ProviderEventFenceException.class)
                .hasMessage("erased-during-processing"));
        deletion.commitAfter(outcome);
        outcome.get(10, TimeUnit.SECONDS);
        assertThat(exists(event.id())).isFalse();
    }

    @Test
    void shouldSkipFencedRowsDuringRenewalAndReapingWithoutBlockingOtherLeases() throws Exception {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("renew-" + UUID.randomUUID()));
        var held = leased(workspace, "github.acme.held.issues", Map.of(), "{}");
        var other = leased(workspace, "github.acme.other.issues", Map.of(), "{}");
        jdbc.update(
                "UPDATE provider_event SET leased_until=clock_timestamp()-interval '1 second' WHERE id IN (?,?)",
                held.id(),
                other.id());
        var write = HeldTransaction.holdIn(transactions, jdbc, () -> store.claimStep(held.id(), 1, "persist"));
        try {
            transactions.executeWithoutResult(status -> {
                store.boundStatements(Duration.ofSeconds(1));
                assertThat(store.renew(other.id(), 1, Duration.ofMinutes(2))).isTrue();
                store.reapExpired(1);
                assertThat(store.renew(held.id(), 1, Duration.ofMinutes(2))).isTrue();
            });
            assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, held.id()))
                    .isEqualTo("LEASED");
            assertThat(jdbc.queryForObject("SELECT failures FROM provider_event WHERE id=?", Integer.class, held.id()))
                    .isZero();
        } finally {
            write.commitAfter(CompletableFuture.completedFuture(null));
        }
        store.reapExpired(1);
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, held.id())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT failures FROM provider_event WHERE id=?", Integer.class, held.id())).isZero();
        assertThat(store.complete(held.id(), 1)).isTrue();
    }

    @Test
    @SuppressWarnings("try") // A long erasure must not stop unrelated webhook arrivals.
    void shouldKeepAdmittingPayloadsWhileErasureOwnsTheCopyFence() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("admission-" + UUID.randomUUID()));
        String delivery = UUID.randomUUID().toString();
        try (var erasure = copyFence.erase()) {
            assertThat(store.store(List.of(new ProviderEventStore.Arrival(
                            workspace.getId(),
                            "slack",
                            "slack.T1.C1.message",
                            delivery,
                            Map.of(),
                            "{}".getBytes(StandardCharsets.UTF_8)))))
                    .isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE delivery_key=?", Long.class, delivery))
                .isEqualTo(1);
    }

    @Test
    void shouldPropagateASwallowedWriteFenceFailureToTheConsumerBoundary() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("swallowed-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.swallowed.issues", Map.of(), "{}");
        jdbc.update("UPDATE provider_event SET lease_epoch=2 WHERE id=?", event.id());
        assertThatThrownBy(() -> inbox.dispatch(event.id(), 1, Instant.now(), () -> {
                    try {
                        transactions.executeWithoutResult(status -> monitors.saveAndFlush(
                                WorkspaceTestFixtures.repositoryMonitor(workspace, "must/not-exist", 42)));
                    } catch (RuntimeException ignored) {
                        // A provider handler may catch an enrichment failure. It must not hide a failed inbox fence.
                    }
                }))
                .isInstanceOf(ProviderEventFenceException.class)
                .hasMessage("lease-lost");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=?",
                        Long.class,
                        workspace.getId()))
                .isZero();
    }

    @Test
    void shouldRecognizeOnlyCommittedSelfErasureAsSuccessfulHandling() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("self-" + UUID.randomUUID()));
        var event = leased(workspace, "slack.T1.C1.channel_deleted", Map.of(), "{}");
        boolean erased = inbox.dispatch(
                event.id(),
                1,
                Instant.now(),
                () -> transactions.executeWithoutResult(status -> {
                    assertThat(DeliveryInbox.claim("erase-channel")).isTrue();
                    assertThat(store.deleteResource(workspace.getId(), "slack", "C1"))
                            .isEqualTo(1);
                }));
        assertThat(erased).isTrue();
        assertThat(exists(event.id())).isFalse();
        assertThat(store.hasStep(event.id(), "erase-channel")).isFalse();
    }

    @Test
    void shouldCompleteSlackChannelDeletedWhenItErasesItsOwnInboxRow() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deleted-" + UUID.randomUUID()));
        String subject = "slack.T1.C1.channel_deleted";
        var event = leased(workspace, subject, Map.of(), """
                {"team_id":"T1","event":{"type":"channel_deleted","channel":"C1"}}
                """);
        var lifecycle = mock(SlackChannelLifecycleService.class);
        doAnswer(call -> {
                    transactions.executeWithoutResult(status -> {
                        if (DeliveryInbox.claim("slack.channel.erase"))
                            store.deleteResource(workspace.getId(), "slack", "C1");
                    });
                    return null;
                })
                .when(lifecycle)
                .onDeleted(eq("T1"), any());
        var handler = new SlackChannelDeletedMessageHandler(lifecycle, deserializer);
        var registry = new SimpleMeterRegistry();
        var activity = mock(ConnectionActivityRecorder.class);
        var consumer = consumer(handler, subject, registry, activity);
        try {
            consumer.handle(event);
            assertThat(exists(event.id())).isFalse();
            assertThat(store.hasStep(event.id(), "slack.channel.erase")).isFalse();
            assertThat(registry.get(IntegrationCoreMetrics.INTEGRATION_MESSAGE_PROCESS_DURATION)
                            .tag("kind", "slack")
                            .tag("outcome", "completed")
                            .timer()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.find(IntegrationCoreMetrics.INTEGRATION_MESSAGE_DROPPED)
                            .counter())
                    .isNull();
            verify(activity).recordEventProcessed(workspace.getId(), IntegrationKind.SLACK, "channel_deleted");
        } finally {
            consumer.close();
            registry.close();
        }
    }

    @Test
    void shouldKeepTheEventWhenSelfErasureRollsBack() {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("rollback-" + UUID.randomUUID()));
        var event = leased(workspace, "slack.T1.C1.channel_deleted", Map.of(), "{}");
        assertThatThrownBy(() -> inbox.dispatch(
                        event.id(),
                        1,
                        Instant.now(),
                        () -> transactions.executeWithoutResult(status -> {
                            DeliveryInbox.claim("slack.channel.erase");
                            store.deleteResource(workspace.getId(), "slack", "C1");
                            throw new IllegalStateException("Rollback erasure");
                        })))
                .isInstanceOf(IllegalStateException.class);
        assertThat(exists(event.id())).isTrue();
        assertThat(store.hasStep(event.id(), "slack.channel.erase")).isFalse();
        assertThat(inbox.dispatch(event.id(), 1, Instant.now(), () -> {})).isFalse();
    }

    @Test
    void shouldRecheckTheEpochAtCommitAfterAnEarlierStepClaim() throws Exception {
        Workspace workspace =
                workspaces.save(WorkspaceTestFixtures.activeWorkspace("commit-epoch-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.epoch.issues", Map.of(), "{}");
        var writing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var handler = workers.submit(() -> inbox.dispatch(
                    event.id(),
                    1,
                    Instant.now(),
                    () -> transactions.executeWithoutResult(status -> {
                        DeliveryInbox.claim("persist");
                        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, "must/not-exist", 42));
                        writing.countDown();
                        try {
                            await(finish);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    })));
            try {
                assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
                transactions.executeWithoutResult(status -> {
                    store.boundStatements(Duration.ofSeconds(1));
                    jdbc.update("UPDATE provider_event SET lease_epoch=2 WHERE id=?", event.id());
                });
            } finally {
                finish.countDown();
            }
            assertThatThrownBy(() -> handler.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ProviderEventFenceException.class);
        }
        assertThat(store.hasStep(event.id(), "persist")).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM repository_to_monitor WHERE workspace_id=?",
                        Long.class,
                        workspace.getId()))
                .isZero();
    }

    @Test
    void shouldRecognizeSelfErasureWithoutANamedStep() {
        Workspace workspace =
                workspaces.save(WorkspaceTestFixtures.activeWorkspace("natural-erase-" + UUID.randomUUID()));
        var event = leased(workspace, "slack.T1.C1.channel_deleted", Map.of(), "{}");
        assertThat(inbox.dispatch(
                        event.id(),
                        1,
                        Instant.now(),
                        () -> transactions.executeWithoutResult(
                                status -> store.deleteResource(workspace.getId(), "slack", "C1"))))
                .isTrue();
        assertThat(exists(event.id())).isFalse();
    }

    @Test
    void shouldKeepAnotherActorsReplayAttributionAfterThePreviewChanges() {
        long person =
                Objects.requireNonNull(accounts.save(new Account("Person")).getId());
        long other = Objects.requireNonNull(
                accounts.save(new Account("Other person")).getId());
        Workspace workspace =
                workspaces.save(WorkspaceTestFixtures.activeWorkspace("replay-actor-" + UUID.randomUUID()));
        var event = leased(workspace, "github.acme.replay.issues", Map.of(), "{}");
        jdbc.update("UPDATE provider_event SET replayed_by=? WHERE id=?", person, event.id());
        var scope = new PersonScope(person, List.of(), List.of());
        var contributor = catalog.contributors().stream()
                .filter(store -> store.store().equals("provider_event_replay"))
                .findFirst()
                .orElseThrow();
        var selection = contributor.select(scope);
        assertThat(selection.rows()).hasSize(1);
        jdbc.update("UPDATE provider_event SET replayed_by=? WHERE id=?", other, event.id());
        assertThat(contributor.export(selection, scope)).isEmpty();
        Long erased = transactions.execute(status -> contributor.erase(selection, scope));
        assertThat(erased).isZero();
        assertThat(jdbc.queryForObject("SELECT replayed_by FROM provider_event WHERE id=?", Long.class, event.id()))
                .isEqualTo(other);
    }

    private ProviderEventConsumer consumer(
            IntegrationMessageHandler handler,
            String subject,
            SimpleMeterRegistry registry,
            ConnectionActivityRecorder activity) {
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
                activity,
                new ProviderEventNotifications(),
                properties,
                mock(ApplicationEventPublisher.class),
                clock);
    }

    private ProviderEventStore.Claimed leased(
            Workspace workspace, String subject, Map<String, String> headers, String body) {
        String delivery = UUID.randomUUID().toString();
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        store.store(List.of(new ProviderEventStore.Arrival(
                workspace.getId(), subject.substring(0, subject.indexOf('.')), subject, delivery, headers, payload)));
        long id = Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM provider_event WHERE delivery_key=?", Long.class, delivery));
        jdbc.update(
                "UPDATE provider_event SET state='LEASED',lease_epoch=1,leased_until=clock_timestamp()+interval '5 minutes' WHERE id=?",
                id);
        return new ProviderEventStore.Claimed(
                id, workspace.getId(), new ProviderEvent(subject, headers, payload, Instant.now()), 1, 0, 1);
    }

    private boolean exists(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM provider_event WHERE id=?)", Boolean.class, id));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Handler preparation did not finish");
    }
}
