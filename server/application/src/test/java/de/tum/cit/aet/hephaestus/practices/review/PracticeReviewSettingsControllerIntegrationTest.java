package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeReviewSettingsControllerIntegrationTest extends BaseIntegrationTest {
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
    void settingsExposeFinalModesAndDecimalRevision() {
        get("/practices/review-settings")
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("ETag")
                .expectBody()
                .jsonPath("$.revision")
                .isEqualTo("0")
                .jsonPath("$.defaultFeedbackMode")
                .isEqualTo("APPROVE_FIRST")
                .jsonPath("$.reviewScope.people")
                .isEqualTo("ALL");
    }

    @Test
    void updateWithoutIfMatchIsRejected() {
        http.patch()
                .uri(root() + "/practices/review-settings")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cooldownMinutes", 30))
                .exchange()
                .expectStatus()
                .isEqualTo(428);
    }

    @Test
    void staleEtagIsRejected() {
        http.patch()
                .uri(root() + "/practices/review-settings")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .header("If-Match", "\"100\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cooldownMinutes", 30))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    void selectedEmptyScopeHasAReadOnlyPreview() {
        http.post()
                .uri(root() + "/practices/review-settings/scope-preview")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "repositories",
                        "SELECTED",
                        "people",
                        "SELECTED",
                        "selectedRepositories",
                        List.of(),
                        "accountIds",
                        List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        get("/practices/review-settings")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewScope.people")
                .isEqualTo("ALL");
    }
}
