package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ProxyRoutingServiceTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final JsonMapper mapper = new JsonMapper();
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final LlmUsageEventRepository usage = mock(LlmUsageEventRepository.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final ProxyRoutingService routing = new ProxyRoutingService(jobs, turns, mapper, usage, reviews, runs);
    private final ReviewJob job = new ReviewJob();
    private final Instant workDeadline = Instant.now().plusSeconds(30);
    private ProxyIdentity identity;

    @BeforeEach
    void setup() {
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, 7L);
        job.setStatus(ReviewJobStatus.RUNNING);
        RuntimeJobTestData.attempt(job, 2);
        job.setRunId(LEASE);
        var snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                model(LlmApiProtocol.OPENAI_RESPONSES, UsageFundingSource.INSTANCE),
                16000,
                1000,
                600,
                Map.of(ModelKind.EMBEDDING, model(LlmApiProtocol.OPENAI_EMBEDDINGS, UsageFundingSource.WORKSPACE)));
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
        lenient().when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        lenient()
                .when(runs.isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2)))
                .thenReturn(true);
    }

    @Test
    void shouldGiveEveryModelRouteOfTheAttemptItsOneSignedWorkDeadline() {
        when(usage.sumDirectModelUsage(7L, LEASE, ReviewRunKind.epoch(2))).thenReturn(directModelUsage(0, 0));

        var review = routing.resolve(identity, request("/internal/llm/responses"));
        var script = routing.resolve(identity, request("/internal/llm/practice-script/embedding/embeddings"));

        assertThat(review.workDeadline()).isEqualTo(workDeadline);
        assertThat(script.workDeadline()).isEqualTo(workDeadline);
    }

    @Test
    void reviewRoutingNeedsNoScriptUsageQueries() {
        var route = routing.resolve(identity, request("/internal/llm/responses"));
        assertThat(route.apiProtocol()).isEqualTo("openai-responses");
        assertThat(route.sourceId()).isEqualTo(job.getId());
        verify(jobs).findById(job.getId());
        verify(runs)
                .isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2));
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void scriptRoutingCountsTheTokensOfAllSlots() {
        when(usage.sumDirectModelUsage(7L, LEASE, ReviewRunKind.epoch(2))).thenReturn(directModelUsage(1100, 0));
        var route = routing.resolve(identity, request("/internal/llm/practice-script/embedding/embeddings"));
        assertThat(route.apiProtocol()).isEqualTo("openai-embeddings");
        assertThat(route.connectionScope()).isEqualTo(UsageFundingSource.WORKSPACE);
        var attempt = Objects.requireNonNull(route.attempt());
        assertThat(attempt.sourceType()).isEqualTo(UsageSourceType.PRACTICE_SCRIPT_EMBEDDING);
        assertThat(attempt.directModelTokens()).isEqualTo(1100);
    }

    /** Each practice pins its own decision model; the call's output limit is that of the practice that makes it. */
    @Test
    void shouldGiveADecisionCallTheOutputLimitThatItsOwnPracticePinned() {
        when(usage.sumDirectModelUsage(7L, LEASE, ReviewRunKind.epoch(2))).thenReturn(directModelUsage(0, 0));
        job.setConfigSnapshot(ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper)
                .withPracticeScript(
                        Map.of(ModelKind.DECISION, model(LlmApiProtocol.OPENAI_DECISIONS, UsageFundingSource.INSTANCE)))
                .toJson(mapper));
        job.setInput(new ReviewJobInput(
                1,
                List.of(
                        new ReviewAssignmentId(job.getReviewId(), 1, 1),
                        new ReviewAssignmentId(job.getReviewId(), 2, 1))));
        when(reviews.find(7L, job.getReviewId()))
                .thenReturn(Optional.of(new ReviewSnapshot(
                        job.getReviewId(),
                        7L,
                        RuntimeReviewTestData.ITEM,
                        ReviewKind.ITEM,
                        null,
                        ReviewOrigin.REQUEST,
                        1,
                        List.of(pinnedDecision(1, 64), pinnedDecision(2, 256)))));
        when(jobs.findPracticeId(7L, "second-practice")).thenReturn(Optional.of(2L));
        String path = "/internal/llm/practice-script/decision/decisions";
        var named = request(path);
        named.addHeader(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PRACTICE_HEADER, "second-practice");

        assertThat(routing.resolve(identity, named).outputLimit()).isEqualTo(256);
        assertThat(routing.resolve(identity, request(path)).outputLimit())
                .as("a call that names no practice takes the first assigned practice")
                .isEqualTo(64);
    }

    @Test
    void refusesAnAttemptThatLostItsLeaseSinceAuthentication() {
        when(runs.isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2)))
                .thenReturn(false);
        assertThatThrownBy(() -> routing.resolve(identity, request("/internal/llm/responses")))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).isEqualTo(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
                });
    }

    @Test
    void missingSlotIsA404AndDoesNotQueryUsage() {
        assertThatThrownBy(() -> routing.resolve(identity, request("/internal/llm/practice-script/reranking/rerank")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(jobs).findById(job.getId());
        verify(runs)
                .isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2));
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void turnCredentialsCannotAcquireScriptModelRouting() {
        var turn = ProxyIdentity.of(turnJwt(3));
        assertThatThrownBy(() -> routing.resolve(turn, request("/internal/llm/practice-script/chat/responses")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jobs, turns, usage);
    }

    @Test
    void turnRoutingBillsTheLeasedAttempt() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, true);

        var route = routing.resolve(ProxyIdentity.of(jwt), request("/internal/llm/responses"));

        assertThat(route.principalDescription()).isEqualTo("mentor-turn:" + jwt.id());
        assertThat(route.baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(route.workspaceId()).isEqualTo(7L);
        assertThat(route.attempt().sourceType()).isEqualTo(UsageSourceType.MENTOR_TURN);
        assertThat(route.attempt().sourceId()).isEqualTo(jwt.id());
        assertThat(route.attempt().number()).isEqualTo(3);
        assertThat(route.attempt().runId()).isEqualTo(jwt.runId());
        verifyNoInteractions(jobs, usage);
    }

    @Test
    void turnRoutingOfAnAttemptThatNoLongerHoldsItsLeaseIsAConflict() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, false);
        assertThatThrownBy(() -> routing.resolve(ProxyIdentity.of(jwt), request("/internal/llm/responses")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
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

    private MentorTurn leasedTurn(AttemptJwt jwt, boolean live) {
        var row = mock(MentorTurn.class);
        // A turn that lost its lease is refused before its rates are read.
        lenient().when(row.getId()).thenReturn(jwt.id());
        lenient().when(row.getWorkspaceId()).thenReturn(jwt.workspaceId());
        lenient().when(row.getAttempt()).thenReturn(jwt.epoch());
        lenient()
                .when(turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.epoch(), jwt.runId()))
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
        lenient()
                .when(row.getAdmission())
                .thenReturn(new MentorTurnAdmission(
                                "model-1",
                                price,
                                new MentorTurnAdmission.Route(
                                        "openai-responses",
                                        "https://models.example/v1",
                                        UsageFundingSource.WORKSPACE,
                                        1L,
                                        2L),
                                null,
                                null,
                                null)
                        .toJson());
        when(turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())).thenReturn(Optional.of(row));
        return row;
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

    private PinnedPractice pinnedDecision(long practiceId, int maxOutputTokens) {
        var definition = RuntimeReviewTestData.assignment(null, null).practice().definition();
        var model = new de.tum.cit.aet.hephaestus.practices.spi.FrozenModel(
                "instance:1",
                "openai-decisions",
                "model-1",
                null,
                Map.of(
                        "contextWindow",
                        mapper.valueToTree(16000),
                        "maxOutputTokens",
                        mapper.valueToTree(maxOutputTokens)));
        return new PinnedPractice(
                practiceId,
                practiceId + 100,
                definition,
                "a".repeat(64),
                new ReviewMethod(
                        1, "b".repeat(64), List.of(new BoundModelSlot("decision", ModelKind.DECISION, model))));
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    private static LlmUsageEventRepository.DirectModelUsage directModelUsage(long tokens, long searchUnits) {
        return new LlmUsageEventRepository.DirectModelUsage() {
            @Override
            public long getTokens() {
                return tokens;
            }

            @Override
            public long getSearchUnits() {
                return searchUnits;
            }
        };
    }
}
