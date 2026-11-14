package de.tum.cit.aet.hephaestus.agent.conversation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.gateway.AgentNextController;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.gateway.testing.AgentReviewDrainFixtures;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.ConversationSandboxRevocations;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private ConversationTurnService turns;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private final MutableClock clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
    private AgentSandboxes sandboxes;
    private ConversationTurns held;
    private TurnSandboxRunKind kind;
    private Workspace workspace;
    private Conversation conversation;

    @BeforeEach
    void prepareConversation() {
        String suffix = UUID.randomUUID().toString();
        var developer = persistUser("sandbox-turn-" + suffix);
        workspace = createWorkspace("sandbox-turn-" + suffix, "Turn", "turn-org", AccountType.ORG, developer);
        conversation = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        sandboxes = SandboxOwnerFixtures.owner(
                beans, new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())));
        held = new ConversationTurns(
                turnRows,
                beans.getBean(ConversationRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                clock,
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
                turnRows,
                turns,
                beans.getBean(ConversationTurnCompletion.class),
                clock,
                beans.getBean(ObjectMapper.class),
                meters);
    }

    private ConversationTurn ask(String question) {
        return turns.submit(
                        workspace.getId(),
                        conversation.getId(),
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP,
                                question,
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn();
    }

    private AgentSandboxes.LiveSandbox sandbox() {
        var sandbox = sandboxes.ensure(
                workspace.getId(), TurnRunsAdapter.subject(conversation.getId()), AgentSandboxKind.CONVERSATION);
        return sandboxes
                .authenticate(new SandboxJwt(
                        sandbox.id(),
                        workspace.getId(),
                        sandbox.subjectKey(),
                        "test",
                        Instant.now().plusSeconds(300)))
                .orElseThrow();
    }

    private SandboxRun claim() {
        var sandbox = sandbox();
        var lease = sandboxes
                .lease(
                        sandbox,
                        Set.of(AgentRunKind.TURN),
                        () -> kind.beforeWrite(sandbox),
                        claimed -> kind.claimed(
                                new SandboxRun(sandbox, claimed.fence(), claimed.kind(), claimed.attempt())))
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
                                Duration.ofHours(1))))
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
                        claimed -> digestKind.claimed(
                                new SandboxRun(sandbox, claimed.fence(), claimed.kind(), claimed.attempt())))
                .orElseThrow();
        var run = new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt());
        assertThat(lease.kind()).isEqualTo(AgentRunKind.DIGEST);
        assertThat(runs.find(lease.fence().run()).orElseThrow().getBand()).isEqualTo(AgentRunBand.BACKGROUND);
        assertThat(held.held(run, false).turnId()).isEqualTo(digest.getId());
        assertThat(digestKind.attempt(run)).isPresent();
        var copies = mock(ConversationClaims.class);
        var contexts = ConversationTurnFixtures.contexts(beans, mock(ResultHistoryQuery.class), copies);
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
        SandboxRun run = claim();
        assertThat(run.fence().runId()).isEqualTo(first.getRunId());
        assertThat(held.held(run, false).turnId()).isEqualTo(first.getId());
        assertThat(turnRows.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(held.workDeadline(run)).isAfter(clock.instant());
        assertThat(runs.find(run.fence().run()).orElseThrow().getDeadline())
                .isEqualTo(held.workDeadline(run).plus(TurnRunsAdapter.GRACE));
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
                turnRows,
                turns,
                beans.getBean(ConversationTurnCompletion.class),
                clock,
                beans.getBean(ObjectMapper.class),
                failures);
        var controller =
                new AgentNextController(sandboxes, List.of(failingKind), AgentReviewDrainFixtures.started(beans));
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
        clock.advance(Duration.between(clock.instant(), held.workDeadline(run)).plusMillis(1));
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
        assertThat(Boolean.TRUE.equals(transactions.execute(status -> kind.end(run, SandboxRunKind.Outcome.HANDED_ON))))
                .isTrue();
        assertThat(turnRows.findById(turn.getId()).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(runs.find(run.fence().run()).orElseThrow().getState()).isEqualTo(AgentRunState.QUEUED);
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
                turnRows,
                beans.getBean(ConversationRepository.class),
                personFence,
                copyFence,
                clock,
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
                turnRows,
                beans.getBean(ConversationRepository.class),
                observedFence,
                beans.getBean(PersonDataCopyFence.class),
                clock,
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
                        lease -> kind.claimed(new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt()))));
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
