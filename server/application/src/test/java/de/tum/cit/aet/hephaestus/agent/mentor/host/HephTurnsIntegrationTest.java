package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static de.tum.cit.aet.hephaestus.testconfig.LifecycleTestSupport.assertResumes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.FakeToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnClaims;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversation turns of the installation as every worker serves them: a pull claims a turn, and each later call
 * of the attempt reads its row, on whichever worker it reaches. The worker role is off in the test context, so each
 * test builds its workers' turns from the context's beans. Most tests hand them ledger changes directly; the
 * notification test owns its PostgreSQL listener. Their clock moves past an attempt's work deadline. The run's
 * grace runs on the database clock: a test lets it pass and runs the run reaper.
 */
class HephTurnsIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** Long enough for a claim to commit, short enough that a pull for nothing returns soon. */
    private static final Duration PULL_WAIT = Duration.ofMillis(300);

    /** This test's worker key: the worker role, which signs attempt tokens, is off in the test context. */
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    /** The billed lease of a model call; the attempt's own lease fences its turn row, not its calls. */
    private static final UUID BILLED_LEASE = UUID.randomUUID();

    /** Usage a provider that sends nothing more has already streamed when the call is cancelled. */
    @Autowired
    private ApplicationContext beans;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turnService;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LlmUsageRecorder recorder;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private MentorTurnClaims claims;

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(KEY));

    private final AttemptTokenVerifier verifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(KEY), new SimpleMeterRegistry());

    private final ProxyCallsInFlight callsInFlight = new ProxyCallsInFlight(12);
    private final FakeToolsPods runtime = new FakeToolsPods();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<HephTurns> workers = new ArrayList<>();
    private ConversationToolsPods toolsPods;
    private MutableClock clock;
    private Workspace workspace;
    private MentorConversation conversation;
    private HephTurns worker;

    @BeforeEach
    void startAWorker() {
        User developer = persistUser("heph-turns");
        workspace = createWorkspace("heph-turns", "Hephaestus Turns", "heph-turns-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
        toolsPods = HephTurnFixtures.toolsPods(beans, runtime);
        worker = worker(Integer.MAX_VALUE);
    }

    @AfterEach
    void stopTheWorkers() {
        workers.forEach(HephTurns::stop);
        toolsPods.close();
    }

    /** A started worker that hands out at most {@code cap} turns at once in the installation. */
    private HephTurns worker(int cap) {
        return worker(MentorTurnStarts.admitting(beans, metrics, cap), issuer, PULL_WAIT);
    }

    private HephTurns worker(MentorTurnStarter starter, AttemptTokenIssuer signing, Duration pullWait) {
        return worker(starter, signing, pullWait, mock(MentorLedgerNotifications.class), callsInFlight);
    }

    private HephTurns worker(
            MentorTurnStarter starter,
            AttemptTokenIssuer signing,
            Duration pullWait,
            MentorLedgerNotifications notifications,
            ProxyCallsInFlight calls) {
        var started = new HephTurns(
                starter,
                turnService,
                turnRepository,
                beans.getBean(MentorRedaction.class),
                completion,
                beans.getBean(MentorTurnProgress.class),
                notifications,
                calls,
                toolsPods,
                signing,
                beans.getBean(TurnRunsAdapter.class),
                clock,
                meters,
                pullWait);
        started.start();
        workers.add(started);
        return started;
    }

    /** The turn endpoints of a worker, with the turns it serves. */
    private HephTurnController controller(HephTurns served) {
        return new HephTurnController(HephTurnFixtures.contexts(beans), completion, served, callsInFlight);
    }

    private MentorTurn ask(String question) {
        return turnService
                .submit(
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

    private HephTurns.Assignment assigned(HephTurns from, MentorTurn asked) {
        HephTurns.Assignment assignment = from.assign().orElseThrow(() -> new AssertionError("no turn was assigned"));
        assertThat(assignment.turnId()).isEqualTo(asked.getId());
        return assignment;
    }

    private AttemptJwt token(HephTurns.Assignment assignment) {
        return verifier.verify(assignment.token());
    }

    /** The attempt the token names, with the conversation and developer of this test. */
    private TurnAttempt attempt(AttemptJwt token) {
        return new TurnAttempt(
                token.id(),
                token.workspaceId(),
                conversation.getAccountId(),
                conversation.getId(),
                token.epoch(),
                token.runId());
    }

    private MentorTurn row(MentorTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    /** When the lease of the turn's run ends. */
    private Instant leasedUntil(MentorTurn turn) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT leased_until FROM agent_run WHERE id = ?",
                        Timestamp.class,
                        row(turn).getRunId()))
                .toInstant();
    }

    /** Whether a host still holds the turn: its run is leased. */
    private boolean held(MentorTurn turn) {
        return turnService.isHeld(row(turn));
    }

    /** Lets the deadline of the turn's run pass, as its grace does on the database clock, and runs the reaper. */
    private void deadlinePasses(MentorTurn turn) {
        jdbc.update(
                "UPDATE agent_run SET deadline = clock_timestamp() - interval '1 second' WHERE id = ?",
                row(turn).getRunId());
        reaper.reap();
    }

    private static JsonNode answer(String text) {
        return MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), text);
    }

    private static Duration timeout() {
        return Duration.ofSeconds(LlmCatalogTestFixtures.admittedMentorConfig().timeoutSeconds());
    }

    private ProxyCallsInFlight.Call call(MentorTurn turn, int attempt) {
        return callsInFlight.begin(
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, turn.getId(), attempt, BILLED_LEASE));
    }

    /**
     * A call in flight to a provider that sends nothing more: it ends only when the attempt's end cancels it, and then
     * records the usage it received, as the proxy does.
     */
    private ProxyCallsInFlight.Call silentCall(MentorTurn asked, int attempt) {
        ProxyCallsInFlight.Call call = call(asked, attempt);
        UUID row = openCall(asked, attempt);
        call.cancelled().subscribe(null, null, () -> {
            completeCall(asked, row, 900);
            call.close();
        });
        return call;
    }

    /** Opens the ledger row of one model call of the attempt, as the proxy does before the call leaves. */
    private UUID openCall(MentorTurn asked, int attempt) {
        MentorTurn turn = row(asked);
        return Objects.requireNonNull(transactions.execute(tx -> recorder.open(new LlmUsageRecorder.OpenCall(
                turn.getWorkspaceId(),
                turn.getRunId(),
                attempt,
                UsageSourceType.MENTOR_TURN.name(),
                UsageSourceType.MENTOR_TURN,
                turn.getId(),
                attempt,
                null,
                ModelKind.CHAT,
                null,
                "test-model",
                LlmPriceSnapshot.unpricedInstance(),
                null))));
    }

    private void completeCall(MentorTurn asked, UUID call, long inputTokens) {
        transactions.executeWithoutResult(tx -> recorder.complete(
                call, asked.getWorkspaceId(), new LlmUsageRecorder.CallUsage(inputTokens, 40, 0, 0, 0, 0)));
    }

    /** The input tokens that the attempt's call rows measured. */
    private long measuredInput(MentorTurn asked, int attempt) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COALESCE(SUM(input_tokens), 0) FROM llm_usage_event WHERE source_type = 'MENTOR_TURN'"
                        + " AND source_id = ? AND source_attempt = ? AND measurement_verification = 'VERIFIED'",
                Long.class,
                asked.getId(),
                attempt));
    }

    /** The worker's own end cancelled the call, billed what it received, and refuses the attempt's later calls. */
    private void assertSettled(MentorTurn asked, int attempt, ProxyCallsInFlight.Call call) {
        assertThat(call.isCancelled()).as("the call in flight is cancelled").isTrue();
        assertThat(measuredInput(asked, attempt)).isEqualTo(900);
        assertThat(call(asked, attempt).isCancelled())
                .as("a later call of the attempt is refused")
                .isTrue();
    }

    @Test
    void shouldClaimAQueuedTurnAndHandOutATokenWhoseWorkEndsAtTheAdmittedTimeout() {
        MentorTurn asked = ask("Is the deployment ready?");

        HephTurns.Assignment assignment = assigned(worker, asked);

        AttemptJwt token = token(assignment);
        MentorTurn running = row(asked);
        assertThat(running.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(running.getAttempt()).isOne();
        assertThat(token.kind()).isEqualTo(AttemptJwt.Kind.TURN);
        assertThat(token.id()).isEqualTo(asked.getId());
        assertThat(token.workspaceId()).isEqualTo(workspace.getId());
        assertThat(token.epoch()).isEqualTo(assignment.attempt()).isOne();
        assertThat(token.runId()).isEqualTo(running.getRunId());
        Instant deadline = clock.instant().plus(timeout());
        assertThat(assignment.deadline()).isEqualTo(deadline);
        assertThat(token.workDeadline()).isEqualTo(deadline.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void shouldAnswerNoTurnAfterItsWaitWhenNoneIsQueued() {
        long started = System.nanoTime();

        assertThat(worker.assign()).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(PULL_WAIT);
    }

    @Test
    void shouldLeaveASecondTurnQueuedWhileTheInstallationsCapIsTaken() {
        HephTurns capped = worker(1);
        MentorTurn busy = ask("Keep the installation busy");
        AttemptJwt held = token(assigned(capped, busy));
        conversation = conversations.openThread(
                workspace.getId(),
                turnService.question(workspace.getId(), busy.getId()).getId());
        MentorTurn waiting = ask("A question in another thread");

        assertThat(capped.assign()).isEmpty();

        assertThat(row(waiting).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(row(waiting).getAttempt()).isZero();
        controller(capped)
                .end(
                        busy.getId(),
                        new HephTurnController.EndRequest(
                                HephTurnController.Outcome.DONE, List.of(answer("Done.")), null, null),
                        HephTurnFixtures.authenticated(held));
        assigned(capped, waiting);
        assertThat(row(waiting).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldServeEveryLaterCallOfTheAttemptOnAnotherWorker() {
        MentorTurn asked = ask("Is the deployment ready?");
        AttemptJwt token = token(assigned(worker, asked));
        HephTurns other = worker(Integer.MAX_VALUE);
        Instant claimedUntil = leasedUntil(asked);

        TurnAttempt served = other.held(token, false);
        other.lease(token);
        assertThat(leasedUntil(asked)).isNotEqualTo(claimedUntil);
        controller(other)
                .end(
                        asked.getId(),
                        new HephTurnController.EndRequest(
                                HephTurnController.Outcome.DONE, List.of(answer("It is ready.")), null, null),
                        HephTurnFixtures.authenticated(token));

        assertThat(served.conversationId()).isEqualTo(conversation.getId());
        assertThat(served.accountId()).isEqualTo(conversation.getAccountId());
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(held(asked)).isFalse();
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), asked.getId()))
                .filteredOn(entry -> entry.getKind() == MentorEntry.Kind.HEPH)
                .extracting(MentorEntry::getText)
                .containsExactly("It is ready.");
    }

    @Test
    void shouldTellTheAttemptToStopAndEndItStoppedWhenItsLoopDoesNotWithinTheGraceAfterTheDeveloperStopsIt() {
        MentorTurn asked = ask("Walk me through the release.");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call silent = silentCall(asked, token.epoch());

        turnService.stop(workspace.getId(), asked.getId());

        assertThatThrownBy(() -> worker.held(token, false))
                .isInstanceOfSatisfying(
                        HephTurns.StopException.class,
                        stop -> assertThat(stop.reason()).isEqualTo(HephTurns.Stop.DEVELOPER));
        assertThat(worker.held(token, true).turnId())
                .as("its end is still taken")
                .isEqualTo(asked.getId());
        Instant claimedUntil = leasedUntil(asked);
        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(HephTurns.StopException.class);
        assertThat(leasedUntil(asked))
                .as("its lease is renewed, so that its loop can end it")
                .isNotEqualTo(claimedUntil);

        // Every worker seals the stopped attempt's calls in flight, which record what they received.
        worker.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                conversation.getId(),
                asked.getId(),
                MentorTurn.Status.STOPPED,
                MentorTurn.Surface.APP,
                token.epoch()));
        assertThat(silent.isCancelled()).isTrue();
        deadlinePasses(asked);
        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(MentorLeaseLostException.class);

        MentorTurn stopped = row(asked);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stopped.getError()).isNull();
        assertThat(turnService.isHeld(stopped)).isFalse();
        assertSettled(asked, token.epoch(), silent);
    }

    @Test
    void shouldTellTheAttemptToStopAtItsDeadlineAndFailItWhenItsLoopDoesNotEndItWithinTheGrace() {
        MentorTurn asked = ask("Summarise every pull request.");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call silent = silentCall(asked, token.epoch());

        clock.advance(timeout().plusSeconds(1));

        assertThatThrownBy(() -> worker.held(token, false))
                .isInstanceOfSatisfying(
                        HephTurns.StopException.class,
                        stop -> assertThat(stop.reason()).isEqualTo(HephTurns.Stop.DEADLINE));
        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(HephTurns.StopException.class);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);

        deadlinePasses(asked);
        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(MentorLeaseLostException.class);

        MentorTurn failed = row(asked);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isEqualTo(MentorTurnCompletion.TOOK_TOO_LONG);
        assertThat(failed.getFailureCause())
                .isEqualTo("its holder did not end it within " + TurnRunsAdapter.GRACE + " after its deadline");
        assertThat(held(asked)).isFalse();
        // Its end reaches every worker, which refuses the attempt's later calls.
        worker.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                conversation.getId(),
                asked.getId(),
                MentorTurn.Status.FAILED,
                MentorTurn.Surface.APP,
                token.epoch()));
        assertThat(silent.isCancelled()).isTrue();
    }

    @Test
    void shouldRefuseEveryCallOfAnAttemptThatLostItsLeaseAndClaimItsRetry() {
        MentorTurn asked = ask("Is the deployment ready?");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call program = call(asked, token.epoch());

        // The loop is gone: its run's lease runs out, and the run reaper takes the turn back.
        claims.expireLease(row(asked));
        reaper.reap();
        worker.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                conversation.getId(),
                asked.getId(),
                MentorTurn.Status.QUEUED,
                MentorTurn.Surface.APP,
                1));

        assertThatThrownBy(() -> worker.held(token, false)).isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> worker.held(token, true)).isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(MentorLeaseLostException.class);
        assertThat(program.isCancelled())
                .as("the lost attempt's model call is cancelled")
                .isTrue();
        program.close();
        HephTurns.Assignment retry = assigned(worker, asked);
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(row(asked).getAttempt()).isEqualTo(2);
        assertThat(worker.held(token(retry), false).attempt()).isEqualTo(2);
    }

    @Test
    void shouldSealTheAttemptsModelCallsWhenItsTurnStops() {
        MentorTurn asked = ask("Walk me through the release.");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call program = call(asked, token.epoch());

        worker.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                conversation.getId(),
                asked.getId(),
                MentorTurn.Status.STOPPED,
                MentorTurn.Surface.APP,
                token.epoch()));

        assertThat(program.isCancelled()).isTrue();
        program.close();
        assertThat(call(asked, token.epoch()).isCancelled())
                .as("a later call of the attempt is refused")
                .isTrue();
    }

    @Test
    void shouldSealTheAttemptExactlyOnceWhenANotificationArrivesAfterPauseAndResume() throws Throwable {
        var notifications = new MentorLedgerNotifications(
                turnRepository,
                beans.getBean(MentorTurnProgress.class),
                beans.getBean(DataSourceProperties.class),
                beans.getBean(ObjectMapper.class));
        var listening = new CompletableFuture<Void>();
        notifications.subscribe(new MentorLedgerNotifications.Listener() {
            @Override
            public void turnChanged(MentorLedgerEvents.TurnChanged change) {}

            @Override
            public void resync() {
                listening.complete(null);
            }
        });
        var calls = spy(new ProxyCallsInFlight(12));
        HephTurns resumed = worker(
                MentorTurnStarts.admitting(beans, metrics, Integer.MAX_VALUE), issuer, PULL_WAIT, notifications, calls);
        try {
            listening.get(10, TimeUnit.SECONDS);
            assertResumes(resumed, () -> {
                MentorTurn asked = ask("Stop this reply after the worker resumes.");
                AttemptJwt token = token(assigned(resumed, asked));
                var billed = new ProxyRouting.BilledAttempt(
                        UsageSourceType.MENTOR_TURN, asked.getId(), token.epoch(), BILLED_LEASE);
                var delivered = new CompletableFuture<Void>();
                // This listener runs after every subscription of the resumed worker, so the notification is fully
                // handled.
                notifications.subscribe(new MentorLedgerNotifications.Listener() {
                    @Override
                    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
                        if (change.turnId().equals(asked.getId()) && change.status() == MentorTurn.Status.STOPPED) {
                            delivered.complete(null);
                        }
                    }
                });
                try (ProxyCallsInFlight.Call inFlight = calls.begin(billed)) {
                    turnService.stop(workspace.getId(), asked.getId());
                    delivered.get(10, TimeUnit.SECONDS);

                    assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
                    assertThat(inFlight.isCancelled()).isTrue();
                    try (ProxyCallsInFlight.Call late = calls.begin(billed)) {
                        assertThat(late.isCancelled()).isTrue();
                    }
                    verify(calls, times(1)).seal(asked.getId(), token.epoch(), true);
                }
            });
        } finally {
            resumed.stop();
            notifications.destroy();
        }
    }

    @Test
    void shouldEndTheAttemptAndRefuseItsLateWritesWhenAnotherPersonsErasureChangesItsContext() {
        MentorTurn asked = ask("Who reviewed the change?");
        AttemptJwt token = token(assigned(worker, asked));
        toolsPods.bind(attempt(token), () -> false);
        assertThat(runtime.running).hasSize(1);

        // The worker ends erased attempts on a thread of its own.
        worker.contextChanged(new MentorLedgerEvents.ContextChanged(workspace.getId()));
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(row(asked).getStatus())
                        .as("a change that erased nobody since the attempt started keeps it")
                        .isEqualTo(MentorTurn.Status.RUNNING));

        erasePersonOfTheWorkspace();
        worker.contextChanged(new MentorLedgerEvents.ContextChanged(workspace.getId()));

        await().atMost(Duration.ofSeconds(5)).until(() -> row(asked).getStatus() == MentorTurn.Status.FAILED);
        MentorTurn failed = row(asked);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isEqualTo(HephTurns.COULD_NOT_FINISH);
        assertThat(failed.getFailureCause()).isEqualTo("context_changed");
        assertThat(turnService.isHeld(failed)).isFalse();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(runtime.running)
                        .as("its tools Pod is removed")
                        .isEmpty());
        assertThatThrownBy(() -> worker.held(token, true)).isInstanceOf(MentorLeaseLostException.class);
        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), asked.getId()))
                .filteredOn(entry -> entry.getKind() == MentorEntry.Kind.HEPH)
                .isEmpty();
    }

    /** The end of an erased attempt that no worker heard of: its next lease call ends it. */
    @Test
    void shouldEndAnAttemptAtItsLeaseWhenAPersonWasErasedSinceItStarted() {
        MentorTurn asked = ask("Who reviewed the change?");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call silent = silentCall(asked, token.epoch());

        erasePersonOfTheWorkspace();

        assertThatThrownBy(() -> worker.lease(token)).isInstanceOf(MentorLeaseLostException.class);
        MentorTurn failed = row(asked);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isEqualTo(HephTurns.COULD_NOT_FINISH);
        assertThat(failed.getFailureCause()).isEqualTo("context_changed");
        assertThat(turnService.isHeld(failed)).isFalse();
        assertSettled(asked, token.epoch(), silent);
    }

    /** Long enough to tell a pull that a change woke from one that waited out its wait. */
    private static final Duration PARKED = Duration.ofSeconds(2);

    /** A pull that waits with nothing to claim. The worker hears no ledger here: the test hands it each change. */
    private CompletableFuture<Optional<HephTurns.Assignment>> parked(HephTurns pulling) {
        CompletableFuture<Optional<HephTurns.Assignment>> pull = CompletableFuture.supplyAsync(pulling::assign);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> !pull.isDone());
        return pull;
    }

    @Test
    void shouldLeaveAWaitingPullAsleepWhenARunningTurnChanges() throws Exception {
        HephTurns pulling = worker(MentorTurnStarts.admitting(beans, metrics), issuer, PARKED);
        long started = System.nanoTime();
        CompletableFuture<Optional<HephTurns.Assignment>> pull = parked(pulling);

        MentorTurn unheard = ask("A question that the pull does not hear of");
        // Each partial answer of a running turn arrives as such a change.
        pulling.turnChanged(changed(unheard, MentorTurn.Status.RUNNING));

        assertThat(pull.get(10, TimeUnit.SECONDS))
                .as("the pull waited out its wait")
                .isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(PARKED);
        assertThat(row(unheard).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }

    @Test
    void shouldWakeAWaitingPullWhenANewQuestionIsQueued() throws Exception {
        HephTurns pulling = worker(MentorTurnStarts.admitting(beans, metrics), issuer, PARKED);
        long started = System.nanoTime();
        CompletableFuture<Optional<HephTurns.Assignment>> pull = parked(pulling);

        MentorTurn asked = ask("A new question");
        pulling.turnChanged(changed(asked, MentorTurn.Status.QUEUED));

        assertThat(pull.get(10, TimeUnit.SECONDS))
                .hasValueSatisfying(
                        assignment -> assertThat(assignment.turnId()).isEqualTo(asked.getId()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(PARKED);
    }

    private MentorLedgerEvents.TurnChanged changed(MentorTurn turn, MentorTurn.Status status) {
        return new MentorLedgerEvents.TurnChanged(
                workspace.getId(), conversation.getId(), turn.getId(), status, MentorTurn.Surface.APP, 0);
    }

    /** Erases a member of this workspace, as an administrator does. */
    private void erasePersonOfTheWorkspace() {
        var accounts = beans.getBean(AccountRepository.class);
        var administrator = new Account("Erasure administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long personId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("person-memory-canary")).getId());
        jdbc.update(
                "INSERT INTO membership(workspace_id,account_id,role,hidden,review_selected,public_activity_seen,joined_at) VALUES (?,?,'MEMBER',false,false,false,CURRENT_TIMESTAMP)",
                workspace.getId(),
                personId);
        var privacy = beans.getBean(PersonDataService.class);
        UUID request =
                privacy.preview(administratorId, personId, List.of()).request().getId();
        privacy.requestErasure(request, administratorId, false);
        privacy.run(request);
    }

    @Test
    void shouldFailTheTurnItCouldNotStartAndStartTheNextQuestion() {
        AttemptTokenIssuer failingOnce = spy(issuer);
        doThrow(new IllegalStateException("the key went away"))
                .doCallRealMethod()
                .when(failingOnce)
                .issueForTurn(any(), any(), anyInt(), any(), any(), any());
        HephTurns starting = worker(MentorTurnStarts.admitting(beans, metrics, 4), failingOnce, PULL_WAIT);
        MentorTurn broken = ask("First question");

        assertThat(starting.assign()).isEmpty();
        MentorTurn next = ask("Second question");
        assigned(starting, next);

        MentorTurn failed = row(broken);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isEqualTo(MentorTurnCompletion.COULD_NOT_START);
        assertThat(turnService.isHeld(failed)).isFalse();
        assertThat(meters.counter(AgentMetrics.MENTOR_TURN_CLAIM_FAILURE).count())
                .isEqualTo(1.0);
        assertThat(call(broken, 1).isCancelled())
                .as("a call of the failed attempt is refused")
                .isTrue();
    }

    @Test
    void shouldEndATurnStoppedThatTheDeveloperStoppedWhileItsClaimCommitted() {
        MentorTurnStarter stopping = spy(MentorTurnStarts.admitting(beans, metrics));
        doAnswer(claim -> {
                    @SuppressWarnings("unchecked") // The starter's own return type.
                    var started = (Optional<MentorTurnStarter.StartedTurn>) claim.callRealMethod();
                    started.ifPresent(turn ->
                            turnService.stop(workspace.getId(), turn.turn().getId()));
                    return started;
                })
                .doCallRealMethod()
                .when(stopping)
                .startNext();
        HephTurns starting = worker(stopping, issuer, PULL_WAIT);
        MentorTurn asked = ask("Stop me at once");

        assertThat(starting.assign()).as("no loop runs a stopped turn").isEmpty();

        MentorTurn stopped = row(asked);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turnService.isHeld(stopped)).isFalse();
    }

    @Test
    void shouldBillTheLastModelCallWhenTheLoopEndsTheTurnBeforeTheProxyRecordedIt() throws Exception {
        MentorTurn asked = ask("Is the deployment ready?");
        AttemptJwt token = token(assigned(worker, asked));
        ProxyCallsInFlight.Call lastCall = call(asked, token.epoch());
        UUID lastRow = openCall(asked, token.epoch());

        // The loop read the end of the answer from the stream; the proxy has not written the call's usage yet.
        CompletableFuture<Void> ended = CompletableFuture.runAsync(() -> controller(worker)
                .end(
                        asked.getId(),
                        new HephTurnController.EndRequest(
                                HephTurnController.Outcome.DONE, List.of(answer("It is ready.")), null, null),
                        HephTurnFixtures.authenticated(token)));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !ended.isDone());
        completeCall(asked, lastRow, 1200);
        lastCall.close();
        ended.get(10, TimeUnit.SECONDS);

        MentorTurn billed = row(asked);
        assertThat(billed.getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(measuredInput(asked, token.epoch())).isEqualTo(1200);
    }

    @Test
    void shouldHandOutNoTurnAndWakeAWaitingPullWhenTheWorkerStops() throws Exception {
        HephTurns draining = worker(MentorTurnStarts.admitting(beans, metrics), issuer, Duration.ofSeconds(30));
        CompletableFuture<Optional<HephTurns.Assignment>> waiting = CompletableFuture.supplyAsync(draining::assign);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> !waiting.isDone());

        draining.stop();

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(HephTurns.DrainingException.class);
        MentorTurn asked = ask("A question after the worker stopped");
        assertThatThrownBy(draining::assign).isInstanceOf(HephTurns.DrainingException.class);
        assertThat(row(asked).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }
}
