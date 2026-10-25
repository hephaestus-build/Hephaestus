package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewNarrativeHistoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
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
    JdbcClient jdbc;

    @Autowired
    ProblemRepository problems;

    @Autowired
    ResultRepository results;

    @Test
    void problemGuidanceSurvivesRuntimeJobExpiry() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var job = data.job(r);
        jdbc.sql("DELETE FROM review_job WHERE workspace_id=:ws AND id=:id")
                .param("ws", r.getWorkspaceId())
                .param("id", job)
                .update();
        assertThat(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(r.getWorkspaceId(), r.getId()))
                .singleElement()
                .extracting(Problem::getNextStep)
                .isEqualTo("Adjust the changed branch.");
        assertThat(results.findByIdAndWorkspaceId(r.getId(), r.getWorkspaceId()))
                .isPresent();
    }
}
