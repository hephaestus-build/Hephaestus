package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ProxyRoutingServiceTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final JsonMapper mapper = new JsonMapper();
    private final ConversationTurnAccess turns = mock(ConversationTurnAccess.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final AgentRunAuthority authority = mock(AgentRunAuthority.class);
    private final ProxyRoutingService routing = new ProxyRoutingService(
            jobs,
            turns,
            mock(ConversationScopeFence.class),
            mapper,
            reviews,
            runs,
            Mockito.mock(ProductFeedbackDraftCalls.class),
            Mockito.mock(LlmModelResolver.class),
            authority);
    private final ReviewJob job = new ReviewJob();
    private final Instant workDeadline = Instant.now().plusSeconds(30);
    private ProxyIdentity identity;

    @BeforeEach
    void setup() {
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, 7L);
        ReviewJobFixtures.withStatus(job, ReviewJobStatus.RUNNING);
        RuntimeJobTestData.attempt(job, 2);
        job.setRunId(LEASE);
        var snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                model(LlmApiProtocol.OPENAI_RESPONSES, UsageFundingSource.INSTANCE),
                16000,
                1000,
                600);
        job.setConfigSnapshot(snapshot.toJson(mapper));
        var now = Instant.now();
        identity = ProxyIdentity.of(new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                job.getId(),
                7L,
                ReviewRunKind.epoch(2),
                LEASE,
                Set.of("llm_proxy"),
                "id",
                now,
                now.plusSeconds(60),
                workDeadline));
        lenient().when(jobs.findCall(job.getId(), 7L)).thenAnswer(read -> Optional.of(RuntimeJobTestData.call(job)));
        lenient()
                .when(runs.isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2)))
                .thenReturn(true);
    }

    @Test
    void refusesAnAttemptThatLostItsLeaseSinceAuthentication() {
        when(runs.isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2)))
                .thenReturn(false);
        assertThatThrownBy(() -> routing.resolve(identity))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).isEqualTo(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
                });
    }

    @Test
    void turnRoutingBillsTheLeasedAttempt() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, true);

        var route = routing.resolve(ProxyIdentity.of(jwt));

        assertThat(route.principalDescription()).isEqualTo("conversation-turn:" + jwt.id());
        assertThat(route.baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(route.workspaceId()).isEqualTo(7L);
        assertThat(route.attempt().sourceType()).isEqualTo(UsageSourceType.CONVERSATION_TURN);
        assertThat(route.attempt().sourceId()).isEqualTo(jwt.id());
        assertThat(route.attempt().number()).isEqualTo(3);
        assertThat(route.attempt().runId()).isEqualTo(jwt.runId());
        verifyNoInteractions(jobs);
    }

    @Test
    void turnRoutingOfAnAttemptThatNoLongerHoldsItsLeaseIsAConflict() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, false);
        assertThatThrownBy(() -> routing.resolve(ProxyIdentity.of(jwt)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void shouldKeepAProactiveTurnInItsRunBandInsteadOfTheInteractiveReserve() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, true);
        var run = mock(AgentRun.class);
        when(run.getBand()).thenReturn(AgentRunBand.BACKGROUND);
        when(runs.find(new AgentRunRef(jwt.runId(), jwt.workspaceId()))).thenReturn(Optional.of(run));
        var route = routing.resolve(ProxyIdentity.of(jwt));

        assertThat(routing.band(route)).isEqualTo(AgentRunBand.BACKGROUND);
    }

    private static AttemptJwt turnJwt(int attempt) {
        var now = Instant.now();
        return new AttemptJwt(
                AttemptJwt.Kind.TURN,
                UUID.randomUUID(),
                7L,
                attempt,
                UUID.randomUUID(),
                Set.of("llm_proxy"),
                "id",
                now,
                now.plusSeconds(60),
                now.plusSeconds(30));
    }

    private void leasedTurn(AttemptJwt jwt, boolean live) {
        // A turn that lost its lease is refused before its rates are read.
        lenient()
                .when(turns.hasLiveLease(jwt.workspaceId(), jwt.id(), jwt.epoch(), jwt.runId()))
                .thenReturn(live);
        var price = new LlmPriceSnapshot(
                UsageFundingSource.WORKSPACE,
                UsagePricingState.PRICED,
                1L,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null);
        var admission = new ConversationTurnAdmission(
                        "model-1",
                        price,
                        new ConversationTurnAdmission.Route(
                                "openai-responses", "https://models.example/v1", UsageFundingSource.WORKSPACE, 1L, 2L),
                        null,
                        null,
                        null)
                .toJson();
        var row = new ConversationTurnAccess.TurnState(
                jwt.id(),
                UUID.randomUUID(),
                jwt.epoch(),
                ConversationTurn.Status.RUNNING,
                ConversationTurn.Surface.APP,
                Instant.EPOCH,
                admission);
        when(turns.find(jwt.workspaceId(), jwt.id())).thenReturn(Optional.of(row));
    }

    private static FrozenModel model(LlmApiProtocol protocol, UsageFundingSource purse) {
        var price = new LlmPriceSnapshot(
                purse, UsagePricingState.PRICED, 1L, null, BigDecimal.ONE, BigDecimal.ONE, null, null);
        return new FrozenModel(
                protocol,
                "https://models.example/v1",
                "model-1",
                purse,
                1L,
                2L,
                7L,
                DataHandlingTier.IN_HOUSE,
                null,
                price,
                false);
    }

    @Test
    void shouldGiveTheReviewRouteItsAttemptsSignedWorkDeadline() {
        var review = routing.resolve(identity);

        assertThat(review.workDeadline()).isEqualTo(workDeadline);
    }

    @Test
    void reviewRoutingReadsOnlyTheJobRow() {
        var route = routing.resolve(identity);
        assertThat(route.apiProtocol()).isEqualTo("openai-responses");
        assertThat(route.sourceId()).isEqualTo(job.getId());
        assertThat(route.attempt().sourceType()).isEqualTo(UsageSourceType.REVIEW_JOB);
        assertThat(route.attempt().workspaceId()).isEqualTo(7L);
        verify(jobs).findCall(job.getId(), 7L);
        verify(runs)
                .isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2));
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void shouldRecheckTheAuthorityOfAReviewAttemptsRunAtModelDispatch() {
        assertThat(routing.dispatchRefusal(routed(
                        new ProxyRouting.BilledAttempt(UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 0, LEASE, 1L))))
                .isNull();

        verify(authority).require(new AgentRunFence(LEASE, 1, 1), AuthorityBoundary.MODEL_DISPATCH);
    }

    @Test
    void shouldRefuseATurnsModelCallWithTheChangedAuthorityOrTheEndedAttempt() {
        var turn =
                routed(new ProxyRouting.BilledAttempt(UsageSourceType.CONVERSATION_TURN, UUID.randomUUID(), 2, LEASE));
        var fence = new AgentRunFence(LEASE, 1, 2);
        doThrow(new AuthorityRefusedException(new AuthorityRefusal(
                        AuthorityBoundary.MODEL_DISPATCH, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED)))
                .doThrow(new StaleRunException(fence))
                .when(authority)
                .require(fence, AuthorityBoundary.MODEL_DISPATCH);

        assertThat(routing.dispatchRefusal(turn)).isEqualTo(ProxyRefusal.AUTHORITY_CHANGED);
        assertThat(routing.dispatchRefusal(turn)).isEqualTo(ProxyRefusal.ATTEMPT_ENDED);
    }

    @Test
    void shouldRecheckNoAuthorityForAProbeOrAReportDraft() {
        assertThat(routing.dispatchRefusal(
                        routed(new ProxyRouting.BilledAttempt(UsageSourceType.PROBE, UUID.randomUUID(), 0, null))))
                .isNull();
        assertThat(routing.dispatchRefusal(routed(new ProxyRouting.BilledAttempt(
                        UsageSourceType.PRODUCT_FEEDBACK_DRAFT, UUID.randomUUID(), 1, LEASE, null, 1))))
                .isNull();

        verifyNoInteractions(authority);
    }

    private static ProxyRouting routed(ProxyRouting.BilledAttempt attempt) {
        return new ProxyRouting(
                "test",
                "openai-completions",
                "https://model.invalid/v1",
                UsageFundingSource.WORKSPACE,
                1L,
                1L,
                1L,
                attempt,
                Instant.now().plusSeconds(60),
                null);
    }
}
