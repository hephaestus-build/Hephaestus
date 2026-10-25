package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeProfileOverviewIntegrationTest extends BaseIntegrationTest {
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
    void emptyProfileReportsNoLatestReview() {
        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview")
                .isEmpty()
                .jsonPath("$.goingWell")
                .doesNotExist()
                .jsonPath("$.reviewedItems.length()")
                .isEqualTo(0);
    }

    @Test
    void oneMetResultIsTheLatestReview() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview.reviewId")
                .isEqualTo(result.getReviewId().toString());
    }

    @Test
    void anotherWorkspaceDoesNotPopulateProfile() {
        data.result(data.scenario(), ResultOutcome.NOT_MET, Instant.now());
        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview")
                .isEmpty();
    }

    @Test
    void shouldKeepTheLatestReviewEvenWhenItsEvidenceIsUnavailableOrOutsideTheStandingWindow() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now().minusSeconds(120L * 86400));
        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview.reviewId")
                .isEqualTo(result.getReviewId().toString());
    }
}
