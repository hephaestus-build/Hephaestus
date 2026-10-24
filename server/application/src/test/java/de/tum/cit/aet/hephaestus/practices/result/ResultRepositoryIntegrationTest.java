package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ResultRepositoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Autowired
    ResultRepository results;

    @Autowired
    ProblemRepository problems;

    @Test
    void resultKeepsItsScalarIdentityAndProofWithoutAJob() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var saved = results.findByIdAndWorkspaceId(
                        r.getId(), scenario.workspace().getId())
                .orElseThrow();
        assertThat(saved.getReviewId()).isEqualTo(r.getReviewId());
        assertThat(saved.getDeveloperId()).isEqualTo(scenarioAccountId);
        assertThat(saved.getEvidenceVersion()).isEqualTo((short) 1);
        assertThat(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(saved.getWorkspaceId(), r.getId()))
                .hasSize(1);
    }

    @Test
    void foreignWorkspaceCannotReadOrEndResult() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        var other = data.scenario();
        assertThat(results.findByIdAndWorkspaceId(r.getId(), other.workspace().getId()))
                .isEmpty();
        assertThat(results.endCurrent(
                        other.workspace().getId(), r.getItemId(), ResultEndReason.WORK_CHANGED, Instant.now()))
                .isZero();
        assertThat(results.findById(r.getId()).orElseThrow().getEndedAt()).isNull();
    }

    @Test
    void markingIncorrectDoesNotFreeCurrentClaim() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        data.mark(r, ResultMarkKind.INCORRECT, scenarioAccountId);
        assertThat(results.findCurrentClaim(r.getWorkspaceId(), r.getPracticeId(), r.getItemId(), r.getDeveloperId()))
                .get()
                .extracting(Result::getId)
                .isEqualTo(r.getId());
    }

    @Test
    void acceptedDoesNotChangeNotMetOutcome() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        data.mark(r, ResultMarkKind.ACCEPTED, scenarioAccountId);
        assertThat(results.findById(r.getId()).orElseThrow().getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
        assertThat(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(r.getWorkspaceId(), r.getId()))
                .hasSize(1);
    }

    @Test
    void shouldKeepResultEndTimeAtOrAfterRecordingWhenTheChangeEventArrivesLate() {
        var at = Instant.now();
        var result = data.result(scenario, ResultOutcome.MET, at.minusSeconds(2), at);
        results.endCurrent(
                result.getWorkspaceId(), result.getItemId(), ResultEndReason.WORK_CHANGED, at.minusSeconds(1));
        var ended = results.findById(result.getId()).orElseThrow();
        assertThat(ended.getEndedAt()).isEqualTo(ended.getRecordedAt());
        assertThat(ended.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
    }

    @Test
    void endIsIdempotentAndHistoryRemains() {
        var at = Instant.now();
        var r = data.result(scenario, ResultOutcome.MET, at);
        assertThat(results.endCurrent(
                        r.getWorkspaceId(), r.getItemId(), ResultEndReason.WORK_CHANGED, at.plusSeconds(1)))
                .isOne();
        assertThat(results.endCurrent(r.getWorkspaceId(), r.getItemId(), ResultEndReason.REPLACED, at.plusSeconds(2)))
                .isZero();
        assertThat(results.findById(r.getId()).orElseThrow().getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(r.getWorkspaceId(), r.getReviewId()))
                .hasSize(1);
    }
}
