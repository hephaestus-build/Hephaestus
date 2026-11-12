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
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ProxyRoutingServiceTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final JsonMapper mapper = new JsonMapper();
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final ProxyRoutingService routing = new ProxyRoutingService(jobs, turns, mapper, reviews, runs);
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
        lenient().when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
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

        assertThat(route.principalDescription()).isEqualTo("mentor-turn:" + jwt.id());
        assertThat(route.baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(route.workspaceId()).isEqualTo(7L);
        assertThat(route.attempt().sourceType()).isEqualTo(UsageSourceType.MENTOR_TURN);
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
        verify(jobs).findById(job.getId());
        verify(runs)
                .isCurrent(
                        new AgentRunRef(Objects.requireNonNull(identity.job()).runId(), 7L), ReviewRunKind.epoch(2));
        verifyNoMoreInteractions(jobs);
    }
}
