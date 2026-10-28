package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptKindTotal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
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
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final JsonMapper mapper = new JsonMapper();
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final ReviewAttemptUsageService attemptUsage = mock(ReviewAttemptUsageService.class);
    private final ProxyRoutingService routing = new ProxyRoutingService(jobs, turns, mapper, attemptUsage);
    private final ReviewJob job = new ReviewJob();
    private final Instant workDeadline = Instant.now().plusSeconds(30);
    private ProxyIdentity identity;

    @BeforeEach
    void setup() {
        lenient()
                .when(attemptUsage.registerCall(any(), anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(true);
        job.setId(UUID.randomUUID());
        RuntimeJobTestData.initialize(job, 7L);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setAttempt(2);
        job.setWorkerId("worker");
        job.setLlmInputTokens(100);
        var snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                model(LlmApiProtocol.OPENAI_RESPONSES, UsageFundingSource.INSTANCE),
                16000,
                1000,
                600,
                false,
                Map.of(ModelKind.EMBEDDING, model(LlmApiProtocol.OPENAI_EMBEDDINGS, UsageFundingSource.WORKSPACE)));
        job.setConfigSnapshot(snapshot.toJson(mapper));
        var now = Instant.now();
        identity = ProxyIdentity.of(
                new AttemptJwt(
                        AttemptJwt.Kind.REVIEW,
                        job.getId(),
                        7L,
                        2,
                        Set.of("llm_proxy"),
                        "id",
                        now,
                        now.plusSeconds(60),
                        workDeadline),
                "worker");
        lenient().when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
    }

    @Test
    void shouldGiveEveryModelRouteOfTheAttemptItsOneSignedWorkDeadline() {
        when(jobs.sumPracticeScriptUsageByKind(7L, job.getId(), 2)).thenReturn(List.of());

        var review = routing.resolve(identity, request("/internal/llm/responses"));
        var script = routing.resolve(identity, request("/internal/llm/practice-script/embedding/embeddings"));

        assertThat(review.workDeadline()).isEqualTo(workDeadline);
        assertThat(script.workDeadline()).isEqualTo(workDeadline);
    }

    @Test
    void reviewRoutingPricesOnlyItsOwnModelWithoutScriptUsageQueries() {
        var route = routing.resolve(identity, request("/internal/llm/responses"));
        assertThat(route.apiProtocol()).isEqualTo("openai-responses");
        assertThat(route.sourceId()).isEqualTo(job.getId());
        assertThat(route.inFlightSpendUsd()).isEqualByComparingTo("0.000100");
        verify(jobs).findById(job.getId());
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void scriptRoutingPricesItsPurseWithoutChargingTheReviewPurseAndCountsAllSlotTokens() {
        when(jobs.sumPracticeScriptUsageByKind(7L, job.getId(), 2))
                .thenReturn(List.of(
                        new PracticeScriptKindTotal(ModelKind.EMBEDDING, 1, 200, 0, 0, 0, 0, 1),
                        new PracticeScriptKindTotal(ModelKind.CHAT, 1, 900, 0, 0, 0, 0, 1)));
        var route = routing.resolve(identity, request("/internal/llm/practice-script/embedding/embeddings"));
        assertThat(route.apiProtocol()).isEqualTo("openai-embeddings");
        assertThat(route.connectionScope()).isEqualTo(UsageFundingSource.WORKSPACE);
        var attempt = Objects.requireNonNull(route.attempt());
        assertThat(attempt.sourceType()).isEqualTo(UsageSourceType.PRACTICE_SCRIPT_EMBEDDING);
        assertThat(attempt.spentUsd()).isEqualByComparingTo("0.000200");
        assertThat(attempt.directModelTokens()).isEqualTo(1100);
    }

    @Test
    void refusesAnAttemptReplacedSinceAuthentication() {
        job.setAttempt(3);
        assertThatThrownBy(() -> routing.resolve(identity, request("/internal/llm/responses")))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).isEqualTo(JobTokenAuthenticationFilter.ATTEMPT_ENDED);
                });
    }

    @Test
    void refusesAReviewCallItsAttemptCannotRegister() {
        when(attemptUsage.registerCall(any(), anyLong(), anyInt(), any(), any(), any()))
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
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void turnCredentialsCannotAcquireScriptModelRouting() {
        var turn = ProxyIdentity.of(turnJwt(3), "worker");
        assertThatThrownBy(() -> routing.resolve(turn, request("/internal/llm/practice-script/chat/responses")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jobs, turns, attemptUsage);
    }

    @Test
    void turnRoutingBillsTheLeasedAttemptAtTheRatesItWasAdmittedWith() {
        var jwt = turnJwt(3);
        var row = leasedTurn(jwt, 3, "worker");
        when(row.getInputTokens()).thenReturn(2_000_000L);

        var route = routing.resolve(ProxyIdentity.of(jwt, "worker"), request("/internal/llm/responses"));

        assertThat(route.principalDescription()).isEqualTo("mentor-turn:" + jwt.id());
        assertThat(route.baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(route.workspaceId()).isEqualTo(7L);
        assertThat(route.attempt().sourceType()).isEqualTo(UsageSourceType.MENTOR_TURN);
        assertThat(route.attempt().sourceId()).isEqualTo(jwt.id());
        assertThat(route.attempt().number()).isEqualTo(3);
        assertThat(route.attempt().workerId()).isEqualTo("worker");
        assertThat(route.attempt().spentUsd()).isEqualByComparingTo("2");
        verifyNoInteractions(jobs, attemptUsage);
    }

    @Test
    void turnRoutingOfAnAttemptThatNoLongerHoldsItsLeaseIsAConflict() {
        var jwt = turnJwt(3);
        leasedTurn(jwt, 4, "worker");
        assertThatThrownBy(() -> routing.resolve(ProxyIdentity.of(jwt, "worker"), request("/internal/llm/responses")))
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
                Set.of("llm_proxy"),
                "id",
                now,
                now.plusSeconds(60),
                null);
    }

    private MentorTurn leasedTurn(AttemptJwt jwt, int attempt, String worker) {
        var row = mock(MentorTurn.class);
        // A turn that lost its lease is refused before its rates are read.
        lenient().when(row.getId()).thenReturn(jwt.id());
        lenient().when(row.getWorkspaceId()).thenReturn(jwt.workspaceId());
        lenient().when(row.isLeased()).thenReturn(true);
        lenient().when(row.getAttempt()).thenReturn(attempt);
        lenient().when(row.getWorkerId()).thenReturn(worker);
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
                price);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("POST", path);
    }
}
