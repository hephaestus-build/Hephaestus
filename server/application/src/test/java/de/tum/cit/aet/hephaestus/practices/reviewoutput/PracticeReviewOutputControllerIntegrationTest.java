package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeReviewOutputControllerIntegrationTest extends BaseIntegrationTest {
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
    ResultStore resultStore;

    @Test
    void adminSeesResultAndProblemLedger() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/reviews/results/" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewId")
                .isEqualTo(r.getReviewId().toString())
                .jsonPath("$.developer.accountId")
                .isEqualTo(Long.toString(scenarioAccountId))
                .jsonPath("$.problems.length()")
                .isEqualTo(1);
        get("/practices/reviews/problems?resultId=" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    void marksRecordActorWithoutEndingTheResult() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        http.post()
                .uri(root() + "/practices/reviews/results/" + r.getId() + "/marks")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "ACCEPTED", "reason", "Intentional tradeoff"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("ACCEPTED")
                .jsonPath("$.removedAt")
                .isEmpty();
        assertThat(results.findById(r.getId()).orElseThrow().getEndedAt()).isNull();
        get("/practices/reviews/problems?openOnly=true")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
        get("/practices/reviews/problems?openOnly=false&accepted=true")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    void recordedProblemLedgerIncludesEndedResultsWhileOpenProblemsExcludeThem() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        assertThat(resultStore.endCurrent(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        ResultEndReason.WORK_CHANGED,
                        Instant.now()))
                .isEqualTo(1);

        get("/practices/reviews/problems?openOnly=false&resultId=" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
        get("/practices/reviews/problems?openOnly=true&resultId=" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    void shouldListResultsAndProblemsOldestFirstWhenSortIsOldest() {
        var older = data.result(scenario, ResultOutcome.NOT_MET, Instant.parse("2026-10-12T00:00:00Z"));
        data.end(older, Instant.parse("2026-10-13T00:00:00Z"));
        var newer = data.result(scenario, ResultOutcome.NOT_MET, Instant.parse("2026-10-14T00:00:00Z"));

        get("/practices/reviews/results")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(newer.getId().toString());
        get("/practices/reviews/results?sort=OLDEST")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(older.getId().toString())
                .jsonPath("$.content[1].id")
                .isEqualTo(newer.getId().toString());
        get("/practices/reviews/problems?openOnly=false")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].resultId")
                .isEqualTo(newer.getId().toString());
        get("/practices/reviews/problems?openOnly=false&sort=OLDEST")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].resultId")
                .isEqualTo(older.getId().toString())
                .jsonPath("$.content[1].resultId")
                .isEqualTo(newer.getId().toString());
    }

    @Test
    void foreignResultIsNotFound() {
        var r = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        get("/practices/reviews/results/" + r.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }
}
