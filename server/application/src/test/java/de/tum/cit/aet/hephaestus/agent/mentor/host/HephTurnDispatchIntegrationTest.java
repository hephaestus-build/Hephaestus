package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static de.tum.cit.aet.hephaestus.testconfig.AgentPhaseTestSupport.noopPhaseObservations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnLeaseReaper;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnWaits;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A worker's dispatcher against a fake Hephaestus host: the fake takes the command frames the real host would receive, and
 * the test answers them through the turn endpoints the way the host does. Claims wake on ledger notifications, so a
 * question reaches the host well within the dispatcher's slow backstop. The dispatcher's clock is the test's: a test
 * moves it and runs the dispatcher's tick itself.
 */
class HephTurnDispatchIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    /** The worker id of {@link WorkerPropertiesFixtures#minimal}. */
    private static final String WORKER = "test-worker";

    private static final Duration LEASE = Duration.ofMinutes(1);

    /** Well inside the dispatcher's backstop: only a notification wakes it this fast. */
    private static final Duration NOTIFIED = Duration.ofSeconds(10);

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnWaits waits;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(KEY);

    private final AttemptTokenVerifier verifier = new AttemptTokenVerifier(KEY, new SimpleMeterRegistry());

    @Autowired
    private DataSourceProperties dataSource;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private FakeHost host;
    private MutableClock clock;
    private SimpleMeterRegistry meters;
    private MentorLedgerNotifications notifications;
    private HephTurnDispatcher dispatcher;
    private HephTurnController controller;
    private final ProxyCallsInFlight callsInFlight = new ProxyCallsInFlight(3);
    private final FakeExecSandboxes runtime = new FakeExecSandboxes();
    private final TurnSandboxes sandboxes =
            new TurnSandboxes(runtime, 0, new SimpleMeterRegistry(), noopPhaseObservations());
    private Workspace workspace;
    private MentorConversation conversation;

    /** A host whose command channel is a queue the test reads. */
    private final class FakeHost extends HephHost {
        final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        volatile boolean starts = true;

        /** How many of the next starts fail, as a host that exits before its channel opens. */
        final AtomicInteger failsFirst = new AtomicInteger();

        final AtomicInteger readyAttempts = new AtomicInteger();

        volatile @Nullable RuntimeException breaksOnce;
        volatile Runnable beforeStart = () -> {};
        volatile Runnable lost = () -> {};

        FakeHost() {
            super(mock(HostSandbox.class));
        }

        @Override
        boolean sandboxReady() {
            return true;
        }

        @Override
        void onLost(Runnable lost) {
            this.lost = lost;
        }

        /** The host's channel closed. */
        void lose() {
            lost.run();
        }

        @Override
        boolean awaitReady(Duration timeout) {
            readyAttempts.incrementAndGet();
            RuntimeException failure = breaksOnce;
            if (failure != null) {
                breaksOnce = null;
                throw failure;
            }
            if (failsFirst.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                return false;
            }
            return starts;
        }

        @Override
        boolean isReady() {
            return starts;
        }

        @Override
        void send(String frame) {
            JsonNode command = mapper.readTree(frame);
            if ("start".equals(command.path("type").asString())) {
                beforeStart.run();
            }
            frames.add(command);
        }

        @Override
        void shutdown() {}

        JsonNode next(String type) throws InterruptedException {
            JsonNode frame = frames.poll(NOTIFIED.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).as("a %s frame", type).isNotNull();
            assertThat(Objects.requireNonNull(frame).path("type").asString()).isEqualTo(type);
            return frame;
        }
    }

    @BeforeEach
    void startTheDispatcher() {
        User developer = persistUser("heph-dispatch");
        workspace = createWorkspace(
                "heph-dispatch", "Hephaestus Dispatch", "heph-dispatch-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        host = new FakeHost();
        clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
        meters = new SimpleMeterRegistry();
        // Its own listener, so the dispatcher stops hearing the ledger when this test ends.
        notifications = new MentorLedgerNotifications(turnRepository, dataSource, mapper);
        dispatcher = newDispatcher(host, WORKER, 4);
        controller = new HephTurnController(
                HephTurnFixtures.contexts(beans),
                completion,
                dispatcher,
                callsInFlight,
                WorkerPropertiesFixtures.minimal("1", "4"));
        dispatcher.start();
    }

    private HephTurnDispatcher newDispatcher(FakeHost target, String workerId, int capacity) {
        return new HephTurnDispatcher(
                MentorTurnStarts.admitting(turns, metrics),
                turns,
                completion,
                notifications,
                target,
                callsInFlight,
                sandboxes,
                issuer,
                mapper,
                clock,
                meters,
                workerId,
                capacity,
                LEASE,
                HephTurnDispatcher.STOP_GRACE,
                Duration.ofMinutes(2));
    }

    private void reapStartWaits() {
        new MentorTurnLeaseReaper(
                        turns, completion, metrics, Objects.requireNonNull(transactions.getTransactionManager()), waits)
                .reap();
    }

    @AfterEach
    void stopTheDispatcher() {
        host.starts = false;
        host.lose(); // Turns a test leaves running would hold the drain.
        dispatcher.stop();
        notifications.destroy();
    }

    private MentorTurn ask(String question) {
        return turns.submit(
                        workspace.getId(),
                        conversation.getId(),
                        new MentorTurnService.Question(
                                MentorTurn.Surface.APP,
                                question,
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn();
    }

    /** The started turn's attempt: what its token names, with the conversation and account of the turn's row. */
    private TurnAttempt credential(JsonNode start) {
        var token = verifier.verify(start.path("token").asString());
        assertThat(token.kind()).isEqualTo(AttemptJwt.Kind.TURN);
        assertThat(token.id().toString()).isEqualTo(start.path("turnId").asString());
        assertThat(token.workDeadline()).isNull();
        return new TurnAttempt(
                token.id(), token.workspaceId(), conversation.getAccountId(), conversation.getId(), token.attempt());
    }

    private MentorTurn row(MentorTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    private static JsonNode answer(String text) {
        return MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), text);
    }

    private double slotsTaken() {
        return meters.get(AgentMetrics.MENTOR_HOST_TURNS_ACTIVE).gauge().value();
    }

    /** The attempt takes a turn sandbox, as its first read, bash or load_context call does. */
    private void holdASandbox(MentorTurn asked, int attempt) {
        sandboxes.acquire(new TurnSandboxes.Attempt(asked.getId(), attempt), workspace.getId(), () -> false);
        assertThat(runtime.running).hasSize(1);
    }

    /** The attempt's end removes its sandbox, in the background. */
    private void awaitSandboxRemoved(String attempt) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(runtime.running)
                        .as("the sandbox of " + attempt + " is removed")
                        .isEmpty());
    }

    /** The command's turn, attempt and, for a stop, reason. */
    private static String target(JsonNode command) {
        return command.path("turnId").asString() + "#" + command.path("attempt").asInt()
                + (command.has("reason") ? " " + command.path("reason").asString() : "");
    }

    @Test
    void shouldStartTheTurnOnTheHostAndRenewItsLeaseUntilTheHostEndsItWhenTheDeveloperAsks() throws Exception {
        MentorTurn asked = ask("Is the deployment ready?");

        JsonNode start = host.next("start");
        TurnAttempt turn = credential(start);
        holdASandbox(asked, 1);
        assertThat(start.path("turnId").asString()).isEqualTo(asked.getId().toString());
        assertThat(controller
                        .context(asked.getId(), HephTurnFixtures.authenticated(turn))
                        .prompt())
                .contains("Is the deployment ready?");
        Instant claimedUntil = Objects.requireNonNull(row(asked).getLeasedUntil());

        clock.advance(LEASE.dividedBy(2));
        dispatcher.tick();

        assertThat(row(asked).getLeasedUntil()).isAfter(claimedUntil);
        controller.end(
                asked.getId(),
                new HephTurnController.EndRequest(
                        HephTurnController.Outcome.DONE, List.of(answer("It is ready.")), null, null),
                HephTurnFixtures.authenticated(turn));

        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), asked.getId()))
                .filteredOn(entry -> entry.getKind() == MentorEntry.Kind.HEPH)
                .extracting(MentorEntry::getText)
                .containsExactly("It is ready.");
        assertThat(slotsTaken()).as("an ended turn takes no slot").isZero();
        awaitSandboxRemoved("the ended attempt");
    }

    @Test
    void shouldRenewTheLeaseOfARunningTurnWhileTheSandboxReconcileWaitsOnTheSandboxApi() throws Exception {
        MentorTurn asked = ask("Is the deployment ready?");
        host.next("start");
        Instant claimedUntil = Objects.requireNonNull(row(asked).getLeasedUntil());
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch answers = new CountDownLatch(1);
        FakeExecSandboxes stalled = new FakeExecSandboxes();
        stalled.onList = () -> {
            listing.countDown();
            try {
                answers.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        var configuration = new HephHostConfiguration();
        ScheduledTaskRegistrar reconciles = configuration.turnSandboxReconciles(
                dispatcher, new TurnSandboxes(stalled, 0, new SimpleMeterRegistry(), noopPhaseObservations()));
        ScheduledTaskRegistrar ticks = configuration.hephTurnTicks(dispatcher);
        reconciles.afterPropertiesSet();
        ticks.afterPropertiesSet();
        try {
            assertThat(listing.await(5, TimeUnit.SECONDS))
                    .as("the reconcile waits for the sandbox list")
                    .isTrue();
            clock.advance(LEASE.dividedBy(2));

            await().atMost(HephTurnDispatcher.TICK.multipliedBy(3))
                    .untilAsserted(() -> assertThat(row(asked).getLeasedUntil()).isAfter(claimedUntil));
        } finally {
            answers.countDown();
            ticks.destroy();
            reconciles.destroy();
        }
    }

    @Test
    void shouldShowToolsQuotaWaitAndRestoreTheHostStageWithoutAnotherHostUpdate() throws Exception {
        MentorTurn asked = ask("Read my repository");
        TurnAttempt turn = credential(host.next("start"));
        JsonNode reading = mapper.readTree("""
                {"activity":"tool","tool":"read","stage":"Reading the repository…","messages":[]}""");
        controller.partial(asked.getId(), reading, HephTurnFixtures.authenticated(turn));
        runtime.quotaFull = true;
        var attempt = new TurnSandboxes.Attempt(turn.turnId(), turn.attempt());
        var waiting = CompletableFuture.supplyAsync(() -> sandboxes.acquire(attempt, workspace.getId(), () -> false));
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> sandboxes.waitingForQuota(attempt));
            dispatcher.tick();
            assertThat(Objects.requireNonNull(row(asked).getPartial())
                            .path("stage")
                            .asString())
                    .isEqualTo("Waiting for tools…");
            JsonNode writing = mapper.readTree("""
                    {"activity":"writing","tool":null,"messages":[{"content":[{"type":"text","text":"Found it"}]}]}""");
            controller.partial(asked.getId(), writing, HephTurnFixtures.authenticated(turn));
            dispatcher.tick();
            assertThat(row(asked).getPartial()).isEqualTo(writing);
            controller.partial(asked.getId(), reading, HephTurnFixtures.authenticated(turn));
            dispatcher.tick();
            assertThat(Objects.requireNonNull(row(asked).getPartial())
                            .path("stage")
                            .asString())
                    .isEqualTo("Waiting for tools…");
            runtime.quotaFull = false;
            waiting.get(5, TimeUnit.SECONDS);
            dispatcher.tick();
            assertThat(row(asked).getPartial()).isEqualTo(reading);
        } finally {
            sandboxes.release(turn.turnId(), turn.attempt());
            runtime.quotaFull = false;
            waiting.handle((result, failed) -> null).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldStoreAChangedPartialAnswerInThreeStatementsWithoutAFlushWaitAndAnUnchangedOneInNone() throws Exception {
        MentorTurn asked = ask("How do I pin the image?");
        TurnAttempt turn = credential(host.next("start"));
        JsonNode writing = mapper.readTree("""
                {"activity":"writing","tool":null,"messages":[{"content":[{"type":"text","text":"Pin it"}]}]}""");
        JsonNode longer = mapper.readTree("""
                {"activity":"writing","tool":null,"messages":[{"content":[{"type":"text","text":"Pin it by digest"}]}]}""");

        // The commit mode, the conditional write and its notification; before, a row read, a locking read, a write and
        // the notification.
        assertThat(statements(() -> controller.partial(asked.getId(), writing, HephTurnFixtures.authenticated(turn))))
                .isEqualTo(3);
        assertThat(statements(() ->
                        controller.partial(asked.getId(), writing.deepCopy(), HephTurnFixtures.authenticated(turn))))
                .isZero();
        assertThat(row(asked).getPartial()).isEqualTo(writing);
        // PostgreSQL serialises notifying commits, flush included; a partial answer's commit does not wait for it.
        String commitMode = transactions.execute(tx -> {
            controller.partial(asked.getId(), longer, HephTurnFixtures.authenticated(turn));
            return String.valueOf(entityManager
                    .createNativeQuery("SELECT current_setting('synchronous_commit')")
                    .getSingleResult());
        });
        assertThat(commitMode).isEqualTo("off");
    }

    @Test
    void shouldBillTheLastModelCallWhenTheHostEndsTheTurnBeforeTheProxyRecordedIt() throws Exception {
        MentorTurn asked = ask("Is the deployment ready?");
        TurnAttempt turn = credential(host.next("start"));
        ProxyCallsInFlight.Call lastCall = callsInFlight.begin(new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, asked.getId(), turn.attempt(), BigDecimal.ZERO, WORKER));

        // The host read the end of the answer from the stream; the proxy has not written the call's usage yet.
        CompletableFuture<Void> ended = CompletableFuture.runAsync(() -> controller.end(
                asked.getId(),
                new HephTurnController.EndRequest(
                        HephTurnController.Outcome.DONE, List.of(answer("It is ready.")), null, null),
                HephTurnFixtures.authenticated(turn)));
        Thread.sleep(300);
        assertThat(ended).as("the end waits for the call").isNotDone();
        transactions.executeWithoutResult(tx -> turnRepository.accumulateUsage(
                asked.getId(), turn.attempt(), new MentorTurnUsage(1200, 80, 0, 0, 0, 1)));
        lastCall.close();
        ended.get(10, TimeUnit.SECONDS);

        MentorTurn billed = row(asked);
        assertThat(billed.getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(billed.getLlmCalls()).isEqualTo(1);
        assertThat(billed.getInputTokens()).isEqualTo(1200);
    }

    @Test
    void shouldEndAStoppedTurnAtOnceWhenItsModelCallStillWaitsForTheProvider() throws Exception {
        MentorTurn asked = ask("Walk me through the release.");
        TurnAttempt turn = credential(host.next("start"));
        // A call to a provider that sends nothing: it ends only when the attempt's end cancels it.
        ProxyCallsInFlight.Call silent = callsInFlight.begin(new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, asked.getId(), turn.attempt(), BigDecimal.ZERO, WORKER));
        silent.cancelled().subscribe(null, null, silent::close);
        turns.stop(workspace.getId(), asked.getId());
        host.next("stop");
        long ending = System.nanoTime();

        controller.end(
                asked.getId(),
                new HephTurnController.EndRequest(
                        HephTurnController.Outcome.STOPPED, List.of(answer("First, tag the")), null, null),
                HephTurnFixtures.authenticated(turn));

        assertThat(Duration.ofNanos(System.nanoTime() - ending)).isLessThan(Duration.ofSeconds(2));
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(row(asked).isLeased()).isFalse();
    }

    /** The JDBC statements {@code work} runs in one transaction, counted on that transaction's session alone. */
    private long statements(Runnable work) {
        AtomicLong prepared = new AtomicLong();
        transactions.executeWithoutResult(tx -> {
            entityManager.unwrap(Session.class).addEventListeners(new SessionEventListener() {
                @Override
                public void jdbcPrepareStatementEnd() {
                    prepared.incrementAndGet();
                }
            });
            work.run();
        });
        return prepared.get();
    }

    @Test
    void shouldTellTheHostToStopAndKeepTheCutOffAnswerWhenTheDeveloperStopsTheTurn() throws Exception {
        MentorTurn asked = ask("Walk me through the release.");
        TurnAttempt turn = credential(host.next("start"));

        turns.stop(workspace.getId(), asked.getId());

        JsonNode stop = host.next("stop");
        assertThat(stop.path("turnId").asString()).isEqualTo(asked.getId().toString());
        assertThat(stop.path("reason").asString()).isEqualTo("developer");
        controller.end(
                asked.getId(),
                new HephTurnController.EndRequest(
                        HephTurnController.Outcome.STOPPED, List.of(answer("First, tag the")), null, null),
                HephTurnFixtures.authenticated(turn));
        MentorTurn stopped = row(asked);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stopped.isLeased()).isFalse();
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), asked.getId()))
                .filteredOn(entry -> entry.getKind() == MentorEntry.Kind.HEPH)
                .extracting(MentorEntry::getText)
                .containsExactly("First, tag the");
    }

    @Test
    void shouldKeepTheTurnQueuedUntilAHostCanStart() throws Exception {
        host.starts = false;
        int attempts = host.readyAttempts.get();

        MentorTurn asked = ask("Anything new?");

        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() > attempts);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(asked).getAttempt()).isZero();
        assertThat(dispatcher.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(host.frames).isEmpty();

        host.starts = true;
        dispatcher.resync();
        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(asked.getId().toString());
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(row(asked).getAttempt()).isOne();
    }

    @Test
    void shouldKeepClaimingAfterHostPreparationThrowsWithoutFailingAQueuedTurn() throws Exception {
        host.starts = false;
        host.breaksOnce = new IllegalStateException("the sandbox API went away");
        int attempts = host.readyAttempts.get();
        MentorTurn asked = ask("Wait for the replacement host");
        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() > attempts);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(asked).getAttempt()).isZero();

        host.starts = true;
        dispatcher.resync();

        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(asked.getId().toString());
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(row(asked).getAttempt()).isOne();
    }

    @Test
    void shouldFailTheTurnWithoutFaultWhenHostPreparationKeepsFailing() throws Exception {
        host.starts = false;
        int attempts = host.readyAttempts.get();
        MentorTurn asked = ask("Can Hephaestus start?");
        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() > attempts);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(asked).getAttempt()).isZero();

        dispatcher.resync();
        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() >= attempts + 2);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(asked).getAttempt()).isZero();

        jdbc.update(
                "UPDATE mentor_turn SET queued_at = now() - interval '3 minutes 1 second' WHERE id = ?", asked.getId());
        reapStartWaits();

        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        MentorTurn failed = row(asked);
        assertThat(failed.getError()).isEqualTo(MentorTurnCompletion.COULD_NOT_START);
        assertThat(failed.getFailureCause()).isEqualTo("start_wait_expired");
        assertThat(failed.getAttempt()).isZero();
        assertThat(failed.isLeased()).isFalse();
        assertThat(host.frames).isEmpty();

        host.starts = true;
        MentorTurn next = ask("Try this question instead");
        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(next.getId().toString());
        assertThat(row(next).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldLeaveAFreshQuestionForTheReadyWorkerAfterReplacementWarmupKeepsFailing() throws Exception {
        dispatcher.stop();
        dispatcher = newDispatcher(host, WORKER, 1);
        controller = new HephTurnController(
                HephTurnFixtures.contexts(beans),
                completion,
                dispatcher,
                callsInFlight,
                WorkerPropertiesFixtures.minimal("1", "1"));
        dispatcher.start();
        MentorTurn busy = ask("Keep the ready worker busy");
        var busyAttempt = credential(host.next("start"));
        conversation = conversations.openThread(
                workspace.getId(),
                turns.question(workspace.getId(), busy.getId()).getId());
        FakeHost replacementHost = new FakeHost();
        replacementHost.starts = false;
        HephTurnDispatcher replacement = newDispatcher(replacementHost, "replacement-worker", 1);
        replacement.start();
        try {
            await().atMost(NOTIFIED).until(() -> replacementHost.readyAttempts.get() > 0);
            clock.advance(Duration.ofMinutes(3).plusSeconds(1));
            int probes = replacementHost.readyAttempts.get();
            MentorTurn fresh = ask("A fresh question after the replacement's idle window");
            replacement.resync();
            await().atMost(NOTIFIED).until(() -> replacementHost.readyAttempts.get() > probes);
            assertThat(row(fresh).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
            assertThat(row(fresh).getAttempt()).isZero();
            assertThat(replacementHost.frames).isEmpty();

            controller.end(
                    busy.getId(),
                    new HephTurnController.EndRequest(
                            HephTurnController.Outcome.DONE, List.of(answer("The ready worker finished.")), null, null),
                    HephTurnFixtures.authenticated(busyAttempt));
            assertThat(host.next("start").path("turnId").asString())
                    .isEqualTo(fresh.getId().toString());
            assertThat(row(fresh).getWorkerId()).isEqualTo(WORKER);
        } finally {
            replacementHost.lose();
            replacement.stop();
        }
    }

    @Test
    void shouldKeepTheQuestionsStartDeadlineAcrossAWorkerRestart() throws Exception {
        host.starts = false;
        MentorTurn asked = ask("Keep my original start deadline");
        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() > 0);
        jdbc.update(
                "UPDATE mentor_turn SET queued_at = now() - interval '3 minutes 1 second' WHERE id = ?", asked.getId());
        Instant queuedAt = row(asked).getQueuedAt();

        dispatcher.stop();
        int probes = host.readyAttempts.get();
        dispatcher.start();
        await().atMost(NOTIFIED).until(() -> host.readyAttempts.get() > probes);
        assertThat(row(asked).getQueuedAt()).isEqualTo(queuedAt);
        reapStartWaits();

        MentorTurn expired = row(asked);
        assertThat(expired.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(expired.getAttempt()).isZero();
        assertThat(expired.getFailureCause()).isEqualTo("start_wait_expired");
        assertThat(expired.getError()).isEqualTo(MentorTurnCompletion.COULD_NOT_START);
        assertThat(expired.isLeased()).isFalse();
        assertThat(host.frames).isEmpty();
    }

    @Test
    void shouldLeaveAnOverdueQuestionWithTheReadyWorkerThatClaimedIt() throws Exception {
        host.starts = false;
        MentorTurn asked = ask("A ready worker can still take this question");
        jdbc.update("UPDATE mentor_turn SET queued_at = now() - interval '4 minutes' WHERE id = ?", asked.getId());
        Instant sweepTime = turns.databaseNow();
        assertThat(waits.overdueQueued(sweepTime)).contains(asked.getId());

        host.starts = true;
        dispatcher.resync();
        host.next("start");
        waits.expireQueued(asked.getId(), sweepTime, MentorTurnCompletion.COULD_NOT_START);

        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(row(asked).getAttempt()).isOne();
        assertThat(host.frames).isEmpty();
    }

    @Test
    void shouldKeepAnOverdueFollowUpQueuedWhileItsConversationIsHeld() throws Exception {
        MentorTurn running = ask("Keep answering this question");
        host.next("start");
        MentorTurn followUp = ask("Wait behind the current answer");
        jdbc.update("UPDATE mentor_turn SET queued_at = now() - interval '4 minutes' WHERE id = ?", followUp.getId());

        reapStartWaits();

        assertThat(row(running).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(row(followUp).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(followUp).getAttempt()).isZero();
    }

    @Test
    void shouldStartTheTurnOnceOnAFreshHostWhenTheFirstHostDidNotStart() throws Exception {
        host.failsFirst.set(1);

        MentorTurn asked = ask("Anything new?");

        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(asked.getId().toString());
        assertThat(host.frames)
                .noneMatch(frame -> "start".equals(frame.path("type").asString()));
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldFailTheTurnAndStartTheNextQuestionWhenStartingATurnThrows() throws Exception {
        host.breaksOnce = new IllegalStateException("the database went away");

        MentorTurn broken = ask("First question");
        await().atMost(NOTIFIED).until(() -> row(broken).getStatus() == MentorTurn.Status.FAILED);
        MentorTurn next = ask("Second question");

        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(next.getId().toString());
        assertThat(row(broken).getError()).isEqualTo(MentorTurnCompletion.COULD_NOT_START);
        assertThat(meters.counter(AgentMetrics.MENTOR_TURN_CLAIM_FAILURE).count())
                .isEqualTo(1.0);
        assertThat(callsInFlight
                        .begin(new ProxyRouting.BilledAttempt(
                                UsageSourceType.MENTOR_TURN, broken.getId(), 1, BigDecimal.ZERO, WORKER))
                        .isCancelled())
                .as("a call of the failed attempt is refused")
                .isTrue();
    }

    @Test
    void shouldSendTheStopAfterTheStartWhenTheDeveloperStopsTheTurnWhileItStarts() throws Exception {
        host.beforeStart = () -> {
            host.beforeStart = () -> {};
            MentorTurn starting = turnRepository
                    .findByWorkspaceIdAndConversationIdAndStatus(
                            workspace.getId(), conversation.getId(), MentorTurn.Status.RUNNING)
                    .getFirst();
            dispatcher.turnChanged(new MentorLedgerEvents.TurnChanged(
                    workspace.getId(),
                    conversation.getId(),
                    starting.getId(),
                    MentorTurn.Status.STOPPED,
                    MentorTurn.Surface.APP,
                    starting.getAttempt()));
        };

        MentorTurn asked = ask("Stop me at once");

        assertThat(host.next("start").path("turnId").asString())
                .isEqualTo(asked.getId().toString());
        JsonNode stop = host.next("stop");
        assertThat(stop.path("turnId").asString()).isEqualTo(asked.getId().toString());
        assertThat(stop.path("reason").asString()).isEqualTo("developer");
    }

    @Test
    void shouldLeaveTheLeaseToExpireWhenTheHostIsLost() throws Exception {
        MentorTurn asked = ask("Are you there?");
        host.next("start");
        holdASandbox(asked, 1);
        Instant claimedUntil = Objects.requireNonNull(row(asked).getLeasedUntil());
        // A model call of the attempt.
        ProxyCallsInFlight.Call program = callsInFlight.begin(
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, asked.getId(), 1, BigDecimal.ZERO, WORKER));

        host.lose();
        clock.advance(LEASE.dividedBy(2));
        dispatcher.tick();

        assertThat(row(asked).getLeasedUntil()).isEqualTo(claimedUntil);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(program.isCancelled())
                .as("the lost host's model call is cancelled")
                .isTrue();
        program.close();
        awaitSandboxRemoved("the lost host's attempt");
    }

    @Test
    void shouldStopTheLostAttemptBeforeItsRetryStartsWhenTheLeaseReaperRequeuesTheTurn() throws Exception {
        MentorTurn asked = ask("Is the deployment ready?");
        JsonNode first = host.next("start");
        assertThat(first.path("attempt").asInt()).isOne();
        holdASandbox(asked, 1);
        ProxyCallsInFlight.Call program = callsInFlight.begin(
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, asked.getId(), 1, BigDecimal.ZERO, WORKER));

        // What the lease reaper does once the lease expired.
        turns.renewLease(asked.getId(), 1, WORKER, Duration.ofSeconds(-1));
        transactions.executeWithoutResult(status ->
                completion.billLostAttempt(turns.expireLease(asked.getId()).orElseThrow()));

        assertThat(target(host.next("stop"))).isEqualTo(asked.getId() + "#1 lease");
        JsonNode retry = host.next("start");
        assertThat(target(retry)).isEqualTo(asked.getId() + "#2");
        assertThat(credential(retry).attempt()).isEqualTo(2);
        assertThat(row(asked).getAttempt()).isEqualTo(2);
        assertThat(slotsTaken()).as("the retry takes the lost attempt's slot").isOne();
        assertThat(program.isCancelled())
                .as("the lost attempt's model call is cancelled")
                .isTrue();
        program.close();
        awaitSandboxRemoved("the lost attempt");

        // The reaper's notification can arrive after this worker claimed the retry: it names the lost attempt.
        dispatcher.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                conversation.getId(),
                asked.getId(),
                MentorTurn.Status.QUEUED,
                MentorTurn.Surface.APP,
                1));
        assertThat(host.frames).as("the retry keeps running").isEmpty();
        assertThat(slotsTaken()).isOne();
    }

    @Test
    void shouldTellTheHostToStopWhenTheTickFindsThatTheAttemptLostItsLease() throws Exception {
        MentorTurn asked = ask("Are you still on it?");
        host.next("start");
        holdASandbox(asked, 1);
        ProxyCallsInFlight.Call program = callsInFlight.begin(
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, asked.getId(), 1, BigDecimal.ZERO, WORKER));
        // Another worker holds the lease now, and nothing told this one.
        jdbc.update("UPDATE mentor_turn SET worker_id = 'another-worker' WHERE id = ?", asked.getId());

        clock.advance(LEASE.dividedBy(2));
        dispatcher.tick();

        assertThat(target(host.next("stop"))).isEqualTo(asked.getId() + "#1 lease");
        assertThat(slotsTaken()).isZero();
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(program.isCancelled())
                .as("the attempt's model call is cancelled, as a stop cancels it")
                .isTrue();
        program.close();
        awaitSandboxRemoved("the attempt that lost its lease");
    }

    @Test
    void shouldStopTheTurnAtItsDeadlineAndFailItWhenTheHostDoesNotEndItWithinTheGrace() throws Exception {
        MentorTurn asked = ask("Summarise every pull request.");
        TurnAttempt turn = credential(host.next("start"));
        ProxyCallsInFlight.Call silent = silentCall(asked, turn.attempt());
        Duration timeout =
                Duration.ofSeconds(LlmCatalogTestFixtures.admittedMentorConfig().timeoutSeconds());

        clock.advance(timeout.plusSeconds(1));
        dispatcher.tick();
        assertThat(target(host.next("stop"))).isEqualTo(asked.getId() + "#1 deadline");
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);

        clock.advance(HephTurnDispatcher.STOP_GRACE.plusSeconds(1));
        dispatcher.tick();

        MentorTurn failed = row(asked);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isEqualTo(HephTurnDispatcher.TOOK_TOO_LONG);
        assertThat(failed.isLeased()).isFalse();
        assertThat(slotsTaken()).isZero();
        assertSettled(asked, turn.attempt(), silent);
    }

    @Test
    void shouldEndTheTurnStoppedWhenTheHostDoesNotEndItWithinTheGraceAfterTheDeveloperStoppedIt() throws Exception {
        MentorTurn asked = ask("Walk me through the release.");
        TurnAttempt turn = credential(host.next("start"));
        ProxyCallsInFlight.Call silent = silentCall(asked, turn.attempt());
        turns.stop(workspace.getId(), asked.getId());
        assertThat(target(host.next("stop"))).isEqualTo(asked.getId() + "#1 developer");

        clock.advance(HephTurnDispatcher.STOP_GRACE.plusSeconds(1));
        dispatcher.tick();

        MentorTurn stopped = row(asked);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stopped.getError()).isNull();
        assertThat(stopped.isLeased()).isFalse();
        assertThat(slotsTaken()).isZero();
        assertSettled(asked, turn.attempt(), silent);
    }

    /** Usage a provider that sends nothing more has already streamed when the call is cancelled. */
    private static final MentorTurnUsage RECEIVED = new MentorTurnUsage(900, 40, 0, 0, 0, 1);

    /**
     * A call in flight to a provider that sends nothing more: it ends only when the attempt's end cancels it, and then
     * records the usage it received, as the proxy does.
     */
    private ProxyCallsInFlight.Call silentCall(MentorTurn asked, int attempt) {
        ProxyCallsInFlight.Call call = callsInFlight.begin(new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, asked.getId(), attempt, BigDecimal.ZERO, WORKER));
        call.cancelled().subscribe(null, null, () -> {
            transactions.executeWithoutResult(tx -> turnRepository.accumulateUsage(asked.getId(), attempt, RECEIVED));
            call.close();
        });
        return call;
    }

    /** The worker's own end cancelled the call, billed what it received, and refuses the attempt's later calls. */
    private void assertSettled(MentorTurn asked, int attempt, ProxyCallsInFlight.Call call) {
        assertThat(call.isCancelled()).as("the call in flight is cancelled").isTrue();
        MentorTurn billed = row(asked);
        assertThat(billed.getLlmCalls()).isEqualTo(1);
        assertThat(billed.getInputTokens()).isEqualTo(900);
        assertThat(callsInFlight
                        .begin(new ProxyRouting.BilledAttempt(
                                UsageSourceType.MENTOR_TURN, asked.getId(), attempt, BigDecimal.ZERO, WORKER))
                        .isCancelled())
                .as("a later call of the attempt is refused")
                .isTrue();
    }
}
