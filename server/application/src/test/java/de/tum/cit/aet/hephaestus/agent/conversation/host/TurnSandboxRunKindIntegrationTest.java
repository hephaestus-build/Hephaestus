package de.tum.cit.aet.hephaestus.agent.conversation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnStarter;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnStarts;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.conversation.web.ConversationReplicaStreams;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentNextController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentNextWaitsFixtures;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentReviewDrainFixtures;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunsFixtures;
import de.tum.cit.aet.hephaestus.agent.run.RunLockProbe;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.ConversationSandboxRevocations;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLeaseLostException;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationPartialUpdates;
import de.tum.cit.aet.hephaestus.conversation.ConversationReplica;
import de.tum.cit.aet.hephaestus.conversation.ConversationRetentionSweeper;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A conversation sandbox claims only its subject's turn, and the server fences every proposal on that claim. */
class TurnSandboxRunKindIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private final DigestAdmission digests = mock(DigestAdmission.class);

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private ConversationTurnRepository turnRows;

    @Autowired
    private ConversationTurnAccess turnAccess;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private AgentSandboxes sandboxes;
    private ConversationTurns held;
    private TurnSandboxRunKind kind;
    private Workspace workspace;
    private Conversation conversation;
    private long developerAccountId;

    @BeforeEach
    void prepareConversation() {
        String suffix = UUID.randomUUID().toString();
        var developer = persistUser("sandbox-turn-" + suffix);
        developerAccountId = accountId(developer);
        workspace = createWorkspace("sandbox-turn-" + suffix, "Turn", "turn-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        sandboxes = SandboxOwnerFixtures.owner(
                beans,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())),
                AgentRunsFixtures.withoutTurnCap(beans));
        held = new ConversationTurns(
                turnAccess,
                beans.getBean(ConversationAccess.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                runs,
                turns,
                sandboxes,
                transactions);
        kind = new TurnSandboxRunKind(
                ConversationTurnStarts.admitting(beans, meters, digests),
                beans.getBean(TurnRunsAdapter.class),
                runs,
                held,
                ConversationTurnFixtures.contexts(beans),
                turnAccess,
                turns,
                beans.getBean(ConversationTurnCompletion.class),
                beans.getBean(ObjectMapper.class),
                meters);
    }

    private ConversationTurn ask(String question) {
        return ask(conversation, question).turn();
    }

    private ConversationTurnService.Submission ask(Conversation in, String question) {
        return turns.submit(
                workspace.getId(),
                in.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        question,
                        UUID.randomUUID().toString(),
                        null,
                        null));
    }

    private AgentSandboxes.LiveSandbox sandbox(Conversation in) {
        var sandbox =
                sandboxes.ensure(workspace.getId(), TurnRunsAdapter.subject(in.getId()), AgentSandboxKind.CONVERSATION);
        return sandboxes
                .authenticate(new SandboxJwt(
                        sandbox.id(),
                        workspace.getId(),
                        sandbox.subjectKey(),
                        "test",
                        Instant.now().plusSeconds(300)))
                .orElseThrow();
    }

    private AgentSandboxes.LiveSandbox sandbox() {
        return sandbox(conversation);
    }

    private SandboxRun claim() {
        return claim(conversation, () -> {});
    }

    private SandboxRun claim(Conversation in) {
        return claim(in, () -> {});
    }

    private SandboxRun claim(Runnable beforeLease) {
        return claim(conversation, beforeLease);
    }

    private SandboxRun claim(Conversation in, Runnable beforeLease) {
        var sandbox = sandbox(in);
        var lease = sandboxes
                .lease(
                        sandbox,
                        Set.of(AgentRunKind.TURN),
                        () -> {
                            kind.beforeWrite(sandbox);
                            beforeLease.run();
                        },
                        claimed -> kind.claimed(sandbox, claimed))
                .orElseThrow();
        return new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt());
    }

    private ConversationTurn queueDigest() {
        var mapper = beans.getBean(ObjectMapper.class);
        String request = "digest-gateway-" + UUID.randomUUID();
        assertThat(turns.submitDigest(
                        workspace.getId(),
                        conversation.getId(),
                        new ConversationTurnService.DigestRequest(
                                request,
                                "Write the opted-in weekly recap.",
                                mapper.createObjectNode(),
                                Instant.now().plus(Duration.ofHours(1)))))
                .isTrue();
        return turnRows.findByWorkspaceIdAndRequestId(workspace.getId(), request)
                .orElseThrow();
    }

    @Test
    void shouldStartAQuestionAheadOfAnOlderQueuedDigest() {
        var digest = queueDigest();
        var question = ask("Answer my question before the weekly recap");
        assertThat(turnRows.findClaimableIds(workspace.getId(), List.of(question.getId())))
                .containsExactly(question.getId());
        var run = claim();
        assertThat(run.fence().runId()).isEqualTo(question.getRunId());
        assertThat(turnRows.findById(question.getId()))
                .get()
                .extracting(ConversationTurn::getStatus)
                .isEqualTo(ConversationTurn.Status.RUNNING);
        assertThat(turnRows.findById(digest.getId()))
                .get()
                .extracting(ConversationTurn::getStatus)
                .isEqualTo(ConversationTurn.Status.QUEUED);
    }

    @Test
    void shouldRunARealDigestStimulusThroughTheConversationClaimAndEnd() {
        var digest = queueDigest();
        when(digests.refuses(workspace.getId(), digest.getId())).thenReturn(false);
        var digestKind = new DigestSandboxRunKind(kind);
        var sandbox = sandbox();
        var lease = sandboxes
                .lease(
                        sandbox,
                        Set.of(AgentRunKind.TURN, AgentRunKind.DIGEST),
                        () -> digestKind.beforeWrite(sandbox),
                        claimed -> digestKind.claimed(sandbox, claimed))
                .orElseThrow();
        var run = new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt());
        assertThat(lease.kind()).isEqualTo(AgentRunKind.DIGEST);
        assertThat(runs.find(lease.fence().run()).orElseThrow().getBand()).isEqualTo(AgentRunBand.BACKGROUND);
        assertThat(held.held(run, false).turnId()).isEqualTo(digest.getId());
        assertThat(digestKind.attempt(run)).isPresent();
        var copies = mock(ConversationClaims.class);
        var contexts = ConversationTurnFixtures.contexts(beans, mock(AssessmentPort.class), copies);
        assertThat(contexts.showFeedback(held.held(run, false), UUID.randomUUID()))
                .isFalse();
        verifyNoInteractions(copies);
        assertThat(beans.getBean(ConversationEntryRepository.class)
                        .findByWorkspaceIdAndTurnIdAndKind(
                                workspace.getId(), digest.getId(), ConversationEntry.Kind.USER))
                .isEmpty();
        assertThat(beans.getBean(ConversationEntryRepository.class)
                        .findByWorkspaceIdAndTurnIdAndKind(
                                workspace.getId(), digest.getId(), ConversationEntry.Kind.DIGEST))
                .isPresent();
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> {
                    digestKind.beforeWrite(sandbox);
                    return digestKind.end(run, SandboxRunKind.Outcome.HANDED_ON);
                })))
                .isTrue();
        assertThat(turnRows.findById(digest.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.QUEUED);
        assertThatThrownBy(() -> held.held(run, false)).isInstanceOf(StaleRunException.class);
    }

    @Test
    void shouldStartOnlyTheSubjectsNextQuestionAndBoundItsWork() {
        ConversationTurn first = ask("First");
        ConversationTurn second = ask("Second");
        SandboxRun run = claim(() -> jdbc.execute("SELECT pg_sleep(2)"));
        assertThat(run.fence().runId()).isEqualTo(first.getRunId());
        assertThat(held.held(run, false).turnId()).isEqualTo(first.getId());
        assertThat(turnRows.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.QUEUED);
        Instant startedAt = Objects.requireNonNull(
                turnRows.findById(first.getId()).orElseThrow().getStartedAt());
        var leased = runs.find(run.fence().run()).orElseThrow();
        Instant leasedAt = Objects.requireNonNull(leased.getStartedAt());
        assertThat(leasedAt).isAfterOrEqualTo(startedAt.plusSeconds(2));
        assertThat(held.workDeadline(run))
                .as("the wait before leasing leaves the full model timeout")
                .isEqualTo(leasedAt.plusSeconds(
                        LlmCatalogTestFixtures.admittedConversationConfig().timeoutSeconds()))
                .isAfter(runs.conversationDeadlineClock());
        assertThat(leased.getDeadline()).isEqualTo(held.workDeadline(run).plus(TurnRunsAdapter.GRACE));
        assertThat(sandboxes.lease(
                        run.sandbox(),
                        Set.of(AgentRunKind.TURN),
                        () -> kind.beforeWrite(run.sandbox()),
                        ignored -> true))
                .isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldCountAStartFailureAndLetOnlyCommonAdviceLogIt(CapturedOutput output) throws Exception {
        ConversationTurn turn = ask("Question");
        var sandbox = sandbox();
        var starter = mock(ConversationTurnStarter.class);
        when(starter.startClaimed(any())).thenThrow(new UnsupportedOperationException("start failed"));
        var failures = new SimpleMeterRegistry();
        var failingKind = new TurnSandboxRunKind(
                starter,
                beans.getBean(TurnRunsAdapter.class),
                runs,
                held,
                ConversationTurnFixtures.contexts(beans),
                turnAccess,
                turns,
                beans.getBean(ConversationTurnCompletion.class),
                beans.getBean(ObjectMapper.class),
                failures);
        var controller = new AgentNextController(
                sandboxes,
                List.of(failingKind),
                AgentReviewDrainFixtures.started(beans),
                AgentNextWaitsFixtures.immediate(beans));
        var keys = AttemptKeyFixtures.keyring(AttemptSigningKey.generate());
        var issuer = new AttemptTokenIssuer(keys);
        var verifier = new AttemptTokenVerifier(keys, failures);
        var gateway = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalControllerAdvice())
                .addFilters(SandboxGatewayFixtures.sandboxCredentialFilter(verifier, sandboxes))
                .build();
        var token = issuer.issueForSandbox(
                sandbox.id(),
                sandbox.workspaceId(),
                sandbox.subjectKey(),
                Instant.now().plusSeconds(300));

        gateway.perform(post("/internal/agent/next").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError());

        assertThat(failures.get(AgentMetrics.CONVERSATION_TURN_CLAIM_FAILURE)
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(output.getAll())
                .containsOnlyOnce("Caught unhandled exception: exceptionType=UnsupportedOperationException")
                .doesNotContain("Could not claim a conversation turn");
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(runs.find(new AgentRunRef(turn.getRunId(), workspace.getId()))
                        .orElseThrow()
                        .getState())
                .isEqualTo(AgentRunState.QUEUED);
    }

    @Test
    void shouldMeasureTheFirstTurnColdAndASecondTurnInTheSameSandboxWarm() {
        ConversationTurn first = ask("First");
        SandboxRun cold = claim();
        assertThat(held.start(cold).cold()).isTrue();
        assertThat(held.start(cold).startedAt())
                .isEqualTo(runs.find(cold.fence().run()).orElseThrow().getStartedAt());
        transactions.executeWithoutResult(status -> kind.end(cold, SandboxRunKind.Outcome.COMPLETED));
        ConversationTurn second = ask("Second");
        SandboxRun warm = claim();
        assertThat(warm.sandbox().id()).isEqualTo(cold.sandbox().id());
        assertThat(warm.fence().runId()).isEqualTo(second.getRunId());
        assertThat(held.start(warm).cold()).isFalse();
        assertThat(turnRows.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(turnRows.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldMeasureARetryWarmOnlyWhileTheSamePhysicalSandboxSurvives() {
        ConversationTurn first = ask("First");
        SandboxRun cold = claim();
        transactions.executeWithoutResult(status -> kind.end(cold, SandboxRunKind.Outcome.HANDED_ON));
        SandboxRun warmRetry = claim();
        assertThat(warmRetry.fence().runId()).isEqualTo(first.getRunId());
        assertThat(warmRetry.fence().epoch()).isGreaterThan(cold.fence().epoch());
        assertThat(held.start(warmRetry).cold()).isFalse();
        transactions.executeWithoutResult(status -> kind.end(warmRetry, SandboxRunKind.Outcome.COMPLETED));
        sandboxes.release(workspace.getId(), cold.sandbox().subjectKey());
        ConversationTurn second = ask("Second");
        SandboxRun replacement = claim();
        assertThat(replacement.sandbox().id()).isNotEqualTo(cold.sandbox().id());
        assertThat(replacement.fence().runId()).isEqualTo(second.getRunId());
        assertThat(held.start(replacement).cold()).isTrue();
        assertThat(turnRows.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
    }

    /**
     * The turn's context build can take longer than the run's lease on a real workspace. The lease renewal and the
     * reaper lock the run's row, so the context read must hold no sandbox, turn or run row while it builds.
     */
    @Test
    void shouldRenewTheLeaseWhileTheTurnsContextIsBuilt() throws Exception {
        ConversationTurn turn = ask("Question");
        SandboxRun run = claim();
        var probe = RunLockProbe.of(runs, transactions, jdbc, run.fence())
                .alsoLocks("conversation_turn", "run_id = ?", run.fence().runId())
                .alsoLocks("agent_sandbox", "id = ?", run.sandbox().id());
        var contexts = spy(ConversationTurnFixtures.contexts(beans));
        doAnswer(call -> {
                    probe.hold();
                    return call.callRealMethod();
                })
                .when(contexts)
                .context(any());
        var slowKind = new TurnSandboxRunKind(
                ConversationTurnStarts.admitting(beans, meters, digests),
                beans.getBean(TurnRunsAdapter.class),
                runs,
                held,
                contexts,
                turnAccess,
                turns,
                beans.getBean(ConversationTurnCompletion.class),
                beans.getBean(ObjectMapper.class),
                meters);
        assertThat(probe.during(() -> slowKind.context(run)).isObject()).isTrue();
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldAnswerNoContextThatAPersonErasureOvertook() {
        ask("Question");
        SandboxRun run = claim();
        var copyFence = mock(PersonDataCopyFence.class);
        when(copyFence.tryHoldForCapture()).thenReturn(true);
        var observed = new ConversationTurns(
                turnAccess,
                beans.getBean(ConversationAccess.class),
                beans.getBean(PersonDataWriteFence.class),
                copyFence,
                runs,
                turns,
                sandboxes,
                transactions);
        var read = new AtomicBoolean();
        doAnswer(call -> read.get()).when(copyFence).erasedSince(any(), any());
        assertThatThrownBy(() -> observed.read(run, turn -> {
                    // The erasure completes while the context is built.
                    read.set(true);
                    return turn.turnId();
                }))
                .isInstanceOf(StaleRunException.class);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.LEASED);
    }

    @Test
    void shouldRefuseEveryWriteOfAnEarlierEpoch() {
        ask("Question");
        SandboxRun run = claim();
        var stale = new SandboxRun(
                run.sandbox(),
                new AgentRunFence(
                        run.fence().runId(), workspace.getId(), run.fence().epoch() + 1),
                run.kind(),
                run.attempt());
        assertThatThrownBy(() -> held.write(stale, false, turn -> turn.turnId()))
                .isInstanceOf(StaleRunException.class);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.LEASED);
    }

    @Test
    void shouldAllowOnlyTheEndAfterTheWorkDeadline() {
        ask("Question");
        SandboxRun run = claim();
        transactions.executeWithoutResult(status -> assertThat(runs.limit(
                        run.fence(),
                        runs.conversationDeadlineClock()
                                .plus(TurnRunsAdapter.GRACE)
                                .minusSeconds(1)))
                .isTrue());
        assertThatThrownBy(() -> held.held(run, false)).isInstanceOf(ConversationTurns.StopException.class);
        assertThat(held.held(run, true).runId()).isEqualTo(run.fence().runId());
    }

    @Test
    void shouldFailTheTurnWhenItsSandboxEndsOnlyTheRun() {
        ConversationTurn turn = ask("Question");
        SandboxRun run = claim();
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> kind.end(run, SandboxRunKind.Outcome.COMPLETED))))
                .isTrue();
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.ENDED);
    }

    @Test
    void shouldRequeueTheTurnWhenItsSandboxHandsTheRunOn() {
        ConversationTurn turn = ask("Question");
        SandboxRun run = claim();
        transactions.executeWithoutResult(status -> {
            // The work window has closed. A handoff can still settle during the run's closing grace.
            assertThat(runs.limit(run.fence(), runs.conversationDeadlineClock().plusSeconds(10)))
                    .isTrue();
            assertThat(kind.end(run, SandboxRunKind.Outcome.HANDED_ON)).isTrue();
        });
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        var queued = runs.find(run.fence().run()).orElseThrow();
        assertThat(queued.getState()).isEqualTo(AgentRunState.QUEUED);
        assertThat(queued.getDeadline()).isNull();
        assertThat(queued.getAttempt()).isOne();
    }

    @Test
    void shouldLeaveTheRunningTurnForTheReaperWhenItsSandboxHandsItOnAfterTheDeadline() {
        var turn = ask("Question");
        var run = claim();
        var fence = run.fence();
        transactions.executeWithoutResult(status -> {
            var elapsed = runs.conversationDeadlineClock().minusSeconds(1);
            assertThat(runs.limit(fence, elapsed)).isTrue();
            var live = runs.find(fence.run()).orElseThrow();
            assertThat(runs.isCurrent(fence.run(), fence.epoch())).isTrue();

            assertThat(kind.end(run, SandboxRunKind.Outcome.HANDED_ON)).isFalse();

            assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus())
                    .isEqualTo(ConversationTurn.Status.RUNNING);
            var untouched = runs.find(fence.run()).orElseThrow();
            assertThat(untouched.getState()).isEqualTo(AgentRunState.LEASED);
            assertThat(untouched.getDeadline()).isEqualTo(elapsed);
            assertThat(untouched.getLeasedUntil()).isEqualTo(live.getLeasedUntil());
            assertThat(untouched.getAttempt()).isEqualTo(live.getAttempt());
        });

        beans.getBean(AgentRunReaper.class).reap();

        assertThat(runs.find(fence.run()).orElseThrow().getStopReason()).isEqualTo(AgentRunStopReason.DEADLINE);
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
    }

    @Test
    void shouldAcceptTheHandoffOfALiveLegacyTurnWithoutADeadline() {
        var turn = ask("Question");
        var sandbox = sandbox();
        transactions.executeWithoutResult(status -> {
            kind.beforeWrite(sandbox);
            var lease = runs.claimSubject(workspace.getId(), sandbox.subjectKey(), Set.of(AgentRunKind.TURN))
                    .orElseThrow();
            assertThat(turns.start(workspace.getId(), lease.runId(), lease.epoch()))
                    .isPresent();
            assertThat(runs.find(lease.fence().run()).orElseThrow().getDeadline())
                    .isNull();

            assertThat(kind.end(
                            new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt()),
                            SandboxRunKind.Outcome.HANDED_ON))
                    .isTrue();
        });
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldFenceBothReplicasWhenConversationHistoryIsRemoved(boolean retention) throws Exception {
        String secret = "words held by the sibling search";
        var entries = beans.getBean(ConversationEntryService.class);
        long workspaceId = workspace.getId();
        var parent =
                entries.append(workspaceId, conversation.getId(), ConversationLedgerFixtures.hephaestus("First fork"));
        var sibling =
                entries.append(workspaceId, conversation.getId(), ConversationLedgerFixtures.hephaestus("Second fork"));
        var source = conversations.openThread(workspaceId, parent.getId());
        var consumer = conversations.openThread(workspaceId, sibling.getId());
        var removed = ask(source, secret).question();

        var colleague = persistUser("unrelated-" + UUID.randomUUID());
        ensureMembership(workspace, colleague, Membership.WorkspaceRole.MEMBER);
        var other = conversations.findOrCreateMain(workspaceId, accountId(colleague));
        var otherTurn = ask(other, "Unrelated question").turn();
        var otherRun = claim(other);
        var active = ask(consumer, "Search the other fork").turn();
        var activeRun = claim(consumer);
        var idleSourceCopy = sandbox(source);
        var query = new ConversationEntryService.SearchQuery(
                null, ConversationEntryService.SearchScope.ALL, null, null, 20);
        var hits = held.read(activeRun, turn -> entries.search(workspaceId, turn.conversationId(), query));
        assertThat(hits).anyMatch(hit -> hit.entryId().equals(removed.getId()));

        var mapper = beans.getBean(ObjectMapper.class);
        var update = ConversationPartialUpdates.whole(partialAnswer(mapper, secret));
        var control = ConversationPartialUpdates.whole(partialAnswer(mapper, "unrelated partial"));
        var published = beans.getBean(SharedEventRecorder.class);
        var replicas = List.of(new ConversationReplica(beans), new ConversationReplica(beans));
        for (var reply : List.of(activeRun, otherRun)) {
            var id = reply.equals(activeRun) ? active.getId() : otherTurn.getId();
            turns.updatePartial(
                    workspaceId,
                    id,
                    reply.fence().epoch(),
                    reply.fence().runId(),
                    reply.equals(activeRun) ? update : control);
            var frames = published.of(
                    ConversationLedgerEvents.ProgressChanged.class,
                    ConversationLedgerEvents.ProgressChanged::turnId,
                    id);
            assertThat(frames).isNotEmpty();
            replicas.forEach(replica -> frames.forEach(replica::deliver));
        }
        var delayed = published.of(
                ConversationLedgerEvents.ProgressChanged.class,
                ConversationLedgerEvents.ProgressChanged::turnId,
                active.getId());
        for (var replica : replicas) {
            assertThat(ConversationReplicaStreams.reconnect(beans, replica, workspaceId, developerAccountId))
                    .contains(secret);
        }

        ConversationReplicaStreams.removeWhileSending(
                beans, replicas.getFirst(), workspaceId, developerAccountId, () -> {
                    if (retention) {
                        jdbc.update(
                                "UPDATE conversation_entry SET created_at = '2001-01-01' WHERE id = ?",
                                removed.getId());
                        beans.getBean(ConversationRetentionSweeper.class).sweepNow();
                    } else {
                        entries.deleteMessage(workspaceId, removed.getId());
                    }
                });

        assertThat(sandboxes.hasLive(workspaceId, activeRun.sandbox().subjectKey()))
                .isFalse();
        assertThat(sandboxes.hasLive(workspaceId, idleSourceCopy.subjectKey())).isFalse();
        assertThat(sandboxes.hasLive(workspaceId, otherRun.sandbox().subjectKey()))
                .isTrue();
        assertThat(turnAccess.hasLiveLease(
                        workspaceId,
                        active.getId(),
                        activeRun.fence().epoch(),
                        activeRun.fence().runId()))
                .isFalse();
        assertThatThrownBy(() -> kind.context(activeRun)).isInstanceOf(StaleRunException.class);
        assertThatThrownBy(() -> turns.updatePartial(
                        workspaceId,
                        active.getId(),
                        activeRun.fence().epoch(),
                        activeRun.fence().runId(),
                        update))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThat(published.of(
                        ConversationLedgerEvents.ProgressChanged.class,
                        ConversationLedgerEvents.ProgressChanged::turnId,
                        active.getId()))
                .isEqualTo(delayed);

        for (var replica : replicas) {
            // Neither replica has heard the removal. Both the replay and the reconnect use the database fence.
            delayed.forEach(replica::deliver);
            assertThat(ConversationReplicaStreams.reconnect(beans, replica, workspaceId, developerAccountId))
                    .contains("\"scopeEpoch\":1")
                    .doesNotContain(secret, active.getId().toString());
            assertThat(ConversationReplicaStreams.reconnect(beans, replica, workspaceId, other.getAccountId()))
                    .contains("unrelated partial");
        }
        var ending = new ConversationTurnService.Ending(
                ConversationTurn.Status.DONE,
                List.of(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), secret)),
                partialAnswer(mapper, secret),
                null);
        assertThatThrownBy(() -> turns.end(
                        workspaceId,
                        active.getId(),
                        activeRun.fence().epoch(),
                        activeRun.fence().runId(),
                        ending))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThat(beans.getBean(ConversationAccess.class).answered(workspaceId, active.getId()))
                .isFalse();

        // A current lease cannot make an old-epoch frame current. It must leave the fresh partial intact.
        var fresh = ask(consumer, "A new reply").turn();
        var freshRun = claim(consumer);
        turns.updatePartial(
                workspaceId,
                fresh.getId(),
                freshRun.fence().epoch(),
                freshRun.fence().runId(),
                ConversationPartialUpdates.whole(partialAnswer(mapper, "new partial")));
        var freshFrames = published.of(
                ConversationLedgerEvents.ProgressChanged.class,
                ConversationLedgerEvents.ProgressChanged::turnId,
                fresh.getId());
        for (var replica : replicas) {
            freshFrames.forEach(replica::deliver);
            for (var frame : delayed) {
                replica.deliver(new ConversationLedgerEvents.ProgressChanged(
                        frame.workspaceId(),
                        frame.conversationId(),
                        frame.accountId(),
                        fresh.getId(),
                        frame.surface(),
                        freshRun.fence().epoch(),
                        frame.scopeEpoch(),
                        frame.sequence(),
                        frame.index(),
                        frame.count(),
                        frame.data()));
            }
            assertThat(ConversationReplicaStreams.reconnect(beans, replica, workspaceId, developerAccountId))
                    .contains("new partial")
                    .doesNotContain(secret);
        }
    }

    private static ObjectNode partialAnswer(ObjectMapper mapper, String text) {
        var answer = mapper.createObjectNode().put("activity", "writing");
        answer.putArray("messages")
                .addObject()
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", text);
        return answer;
    }

    /**
     * An access change that a cause announces reaches the sandbox through the application's own listeners: the
     * revocation commits with it, and the running attempt's next write is refused.
     */
    @Test
    void shouldRevokeTheSandboxAndRefuseTheRunningAttemptWhenAnAccessChangeIsAnnounced() {
        ask("Question");
        SandboxRun run = claim();

        transactions.executeWithoutResult(
                status -> beans.publishEvent(new AgentContextChangedEvent(workspace.getId())));

        assertThat(sandboxes.hasLive(workspace.getId(), run.sandbox().subjectKey()))
                .isFalse();
        assertThatThrownBy(() -> held.write(run, true, turn -> turn.turnId())).isInstanceOf(StaleRunException.class);
    }

    /**
     * A developer's AI-choice change reaches the sandboxes of their workspaces through the application's own listeners,
     * entered through the account directory.
     */
    @Test
    void shouldRevokeTheSandboxAndRefuseTheRunningAttemptWhenTheDeveloperChangesTheirAiChoice() {
        ask("Question");
        SandboxRun run = claim();

        // Only a developer who signed in can choose: the fixture's account starts unclaimed.
        jdbc.update("UPDATE account SET status = 'ACTIVE' WHERE id = ?", developerAccountId);
        beans.getBean(AccountDirectory.class).setAiChoice(developerAccountId, AiChoice.NO_AI);

        assertThat(sandboxes.hasLive(workspace.getId(), run.sandbox().subjectKey()))
                .isFalse();
        assertThatThrownBy(() -> held.write(run, true, turn -> turn.turnId())).isInstanceOf(StaleRunException.class);
    }

    @Test
    void shouldRevokeTheWorkspacesConversationSandboxInTheAccessChangeTransaction() {
        ask("Question");
        SandboxRun run = claim();
        var review = sandboxes.ensure(workspace.getId(), "review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var revocations = beans.getBean(ConversationSandboxRevocations.class);
        transactions.executeWithoutResult(
                status -> revocations.contextChanged(new ConversationLedgerEvents.ContextChanged(workspace.getId())));
        assertThat(sandboxes.hasLive(workspace.getId(), run.sandbox().subjectKey()))
                .isFalse();
        assertThat(sandboxes.hasLive(workspace.getId(), review.subjectKey())).isTrue();
        assertThatThrownBy(() -> held.write(run, false, turn -> turn.turnId())).isInstanceOf(StaleRunException.class);
        assertThat(Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT state FROM agent_sandbox WHERE id = ?",
                        String.class,
                        run.sandbox().id())))
                .isEqualTo("DELETING");
    }

    @Test
    void shouldRefuseContextCaptureBeforePersonAndRowLocksWhileErasureOwnsTheCopyFence() {
        ConversationTurn turn = ask("Question");
        SandboxRun run = claim();
        var personFence = mock(PersonDataWriteFence.class);
        var copyFence = beans.getBean(PersonDataCopyFence.class);
        var observed = new ConversationTurns(
                turnAccess,
                beans.getBean(ConversationAccess.class),
                personFence,
                copyFence,
                runs,
                turns,
                sandboxes,
                transactions);
        var captured = new AtomicBoolean();
        try (var erasure = copyFence.erase()) {
            assertThat(erasure).isNotNull();
            assertThatThrownBy(() -> observed.write(run, false, held -> {
                        captured.set(true);
                        return held.turnId();
                    }))
                    .isInstanceOfSatisfying(
                            ErrorResponseException.class,
                            failure ->
                                    assertThat(failure.getStatusCode().value()).isEqualTo(503));
        }
        verifyNoInteractions(personFence);
        assertThat(captured).isFalse();
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.LEASED);
        assertThat(sandboxes.hasLive(workspace.getId(), run.sandbox().subjectKey()))
                .isTrue();
    }

    @Test
    void shouldLetAccessRevocationCommitWhileAClaimWaitsForThePersonDataFence() throws Exception {
        ConversationTurn turn = ask("Question");
        var sandbox = sandbox();
        var fence = beans.getBean(PersonDataWriteFence.class);
        var identities = jdbc.query(
                "SELECT provider_id,provider_user_key,NULLIF(team_key,'') FROM identity WHERE account_id=?",
                (row, index) ->
                        new PersonIdentity(row.getLong(1), Objects.requireNonNull(row.getString(2)), row.getString(3)),
                conversation.getAccountId());
        assertThat(identities).isNotEmpty();
        var entered = new CountDownLatch(1);
        var observedFence = mock(PersonDataWriteFence.class);
        Mockito.doAnswer(call -> {
                    entered.countDown();
                    return fence.holdForAccountWrite(call.getArgument(0));
                })
                .when(observedFence)
                .holdForAccountWrite(conversation.getAccountId());
        var observed = new ConversationTurns(
                turnAccess,
                beans.getBean(ConversationAccess.class),
                observedFence,
                beans.getBean(PersonDataCopyFence.class),
                runs,
                turns,
                sandboxes,
                transactions);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var claimed = Objects.requireNonNull(transactions.execute(status -> {
                fence.holdForErasure(identities);
                var future = executor.submit(() -> sandboxes.lease(
                        sandbox,
                        Set.of(AgentRunKind.TURN),
                        () -> observed.beforeWrite(sandbox),
                        lease -> kind.claimed(sandbox, lease)));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                beans.getBean(ConversationSandboxRevocations.class)
                        .contextChanged(new ConversationLedgerEvents.ContextChanged(workspace.getId()));
                return future;
            }));
            assertThat(claimed.get(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(sandboxes.hasLive(workspace.getId(), sandbox.subjectKey())).isFalse();
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
    }
}
