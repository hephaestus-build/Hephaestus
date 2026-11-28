package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    @Autowired
    JdbcClient jdbc;

    /** ADR 0125: no staff read names the developer of a result, and none selects a person. */
    @Test
    void adminsSeeNoDeveloperAndCannotSelectAPerson() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());

        get("/practices/reviews/results/" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.person")
                .doesNotExist();
        // A person filter does not exist, so a person who wrote none of the results still sees this one listed.
        get("/practices/reviews/results?personId=" + (scenarioAccountId + 1_000_000))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(r.getId().toString())
                .jsonPath("$.content[0].person")
                .doesNotExist();
        get("/practices/reviews/problems?resultId=" + r.getId() + "&openOnly=false")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].person")
                .doesNotExist();
        for (var path : List.of(
                "/practices/reviews",
                "/practices/reviews/problems",
                "/practices/reviews/feedback",
                "/practices/reviews/feedback/locations",
                "/practices/reviews/feedback/reasons")) {
            get(path + "?personId=" + scenarioAccountId).expectStatus().isOk().expectBody(Void.class);
        }
    }

    @Test
    void adminSeesResultAndProblemLedger() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/reviews/results/" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewId")
                .isEqualTo(r.getReviewId().toString())
                .jsonPath("$.problems.length()")
                .isEqualTo(1)
                .jsonPath("$.currentness")
                .isEqualTo("UNVERIFIABLE")
                .jsonPath("$.unverifiableReason")
                .isEqualTo("RECORD_MISSING")
                .jsonPath("$.capturedAt")
                .isEmpty();
        get("/practices/reviews/problems?resultId=" + r.getId() + "&openOnly=false")
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

    /** ADR 0125 decision 3: an admin reads a developer's explanation of a disagreement only for 30 days. */
    @Test
    void shouldShowADisagreementsExplanationOnlyAsACorrectionOf30Days() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var fresh = data.result(scenario, ResultOutcome.NOT_MET, now);
        var old = data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, now);
        disagree(fresh, "Fresh explanation.", now.minus(Duration.ofDays(29)));
        disagree(old, "Old explanation.", now.minus(Duration.ofDays(31)));

        get("/practices/reviews/results/" + fresh.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.disagreements[0].comment")
                .isEqualTo("Fresh explanation.");
        get("/practices/reviews/results/" + old.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.disagreements[0].respondedAt")
                .exists()
                .jsonPath("$.disagreements[0].comment")
                .isEmpty();
    }

    private void disagree(Result result, String explanation, Instant at) {
        var feedback = UUID.randomUUID();
        long ws = scenario.workspace().getId();
        jdbc.sql("INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,"
                        + "created_at,delivered_at) VALUES(:id,:ws,:review,:item,:developer,'PRACTICE_PAGE','DELIVERED',"
                        + "'Private page text.',:at,:at)")
                .param("id", feedback)
                .param("ws", ws)
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", result.getDeveloperId())
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,person_account_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:result,:developer,0)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("result", result.getId())
                .param("developer", result.getDeveloperId())
                .update();
        jdbc.sql("INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,comment,created_at)"
                        + " VALUES(:id,:ws,:feedback,'DISAGREE',:comment,:at)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("comment", explanation)
                .param("at", Timestamp.from(at))
                .update();
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
