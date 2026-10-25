package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ConversationFeedbackErasureIntegrationTest extends BaseIntegrationTest {
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
    ConversationFeedbackErasureAdapter erasure;

    @Autowired
    ResultRepository results;

    @Test
    void emptyThreadSelectionDeletesNothing() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        assertThat(erasure.eraseForThreads(r.getWorkspaceId(), List.of())).isZero();
        assertThat(results.findById(r.getId())).isPresent();
    }

    @Test
    void conversationErasureDoesNotEraseScmResults() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        assertThat(erasure.eraseAllConversationForWorkspace(r.getWorkspaceId())).isZero();
        assertThat(results.findById(r.getId())).isPresent();
    }

    @Test
    void developerSelectionRetainsOtherWorkKinds() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        assertThat(erasure.eraseConversationFeedbackAboutUser(r.getWorkspaceId(), scenarioAccountId))
                .isZero();
        assertThat(results.findById(r.getId())).isPresent();
    }
}
