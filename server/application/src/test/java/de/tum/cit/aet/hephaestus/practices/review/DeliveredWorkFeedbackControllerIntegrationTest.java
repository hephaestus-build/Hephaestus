package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class DeliveredWorkFeedbackControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ProductReferenceQuery references;

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
    void shouldResolveTheReviewedItemWhenTheFeedbackAddressShowsAGitHubTab() {
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        var reference = Objects.requireNonNull(
                references.items(item.workspaceId(), List.of(item.itemId())).get(item.itemId()));
        http.get()
                .uri(b -> b.path(root() + "/practices/feedback/on-work")
                        .queryParam("url", reference.url() + "/files?tab=checks")
                        .build())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(item.itemId()))
                .jsonPath("$.feedback.length()")
                .isEqualTo(0);
    }

    @Test
    void unmatchedPageReturnsAnExplicitNullItemAndEmptyFeedback() {
        http.get()
                .uri(b -> b.path(root() + "/practices/feedback/on-work")
                        .queryParam("url", "https://github.com/unknown/project/pull/1")
                        .build())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item")
                .isEmpty()
                .jsonPath("$.feedback.length()")
                .isEqualTo(0);
    }

    @Test
    void lookupRequiresAuthentication() {
        http.get()
                .uri(b -> b.path(root() + "/practices/feedback/on-work")
                        .queryParam("url", "https://github.com/owner/project/pull/1")
                        .build())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }
}
