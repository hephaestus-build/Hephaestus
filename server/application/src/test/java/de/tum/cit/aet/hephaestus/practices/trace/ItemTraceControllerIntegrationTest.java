package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ItemTraceControllerIntegrationTest extends BaseIntegrationTest {
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

    @Test
    void traceIsRootedInTheItemLedger() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/items/" + r.getItemId() + "/trace")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(r.getItemId()));
    }

    @Test
    void foreignItemIsNotFound() {
        var other = data.scenario();
        get("/practices/items/" + other.item().getId() + "/trace")
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void ownTraceNeedsAReviewFromTheSelectedItem() {
        var r = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        get("/practices/items/" + scenario.item().getId() + "/own-trace?reviewId=" + r.getReviewId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }
}
