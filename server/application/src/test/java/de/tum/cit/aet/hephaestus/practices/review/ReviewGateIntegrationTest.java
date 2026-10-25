package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewGateIntegrationTest extends BaseIntegrationTest {
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
    ReviewGate gate;

    @Autowired
    ReviewRequestPolicy requests;

    @Autowired
    WorkspaceRepository workspaces;

    @Test
    void shouldRefuseRequestedReviewsWhenTheirWorkspaceSwitchIsOff() {
        long ws = scenario.workspace().getId();
        long account = Objects.requireNonNull(scenario.account().getId());
        assertThat(requests.refusal(ws, scenario.item().getId(), account)).isEmpty();
        scenario.workspace().getFeatures().setPracticeReviewManualTriggerEnabled(false);
        workspaces.saveAndFlush(scenario.workspace());
        assertThat(requests.refusal(ws, scenario.item().getId(), account)).contains(ReviewRequestReason.GATE_WITHHELD);
    }

    @Autowired
    PracticeRepository practices;

    @Test
    void storedOffModeStillAllowsAResultReview() {
        scenario.practice().setFeedbackMode(FeedbackMode.OFF);
        practices.saveAndFlush(scenario.practice());
        assertThat(gate.evaluate(scenario.workspace(), snapshot(), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void shouldSelectStoredPracticesBeforeRecipientSpecificModelBinding() {
        assertThat(gate.evaluate(scenario.workspace(), snapshot(), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void anExplicitReviewDoesNotInventAnEvent() {
        assertThat(gate.evaluate(scenario.workspace(), snapshot(), ReviewOrigin.BACKFILL, null, List.of()))
                .isInstanceOfSatisfying(
                        GateDecision.Run.class, r -> assertThat(r.origin()).isEqualTo(ReviewOrigin.BACKFILL));
    }

    ItemSnapshot snapshot() {
        return ItemFixtures.snapshot(
                        new ItemRef(
                                scenario.workspace().getId(),
                                scenario.item().getId(),
                                scenario.item().getKind(),
                                scenario.item().getSourceId(),
                                null,
                                null),
                        Map.of())
                .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, "Test", null))
                .label(ItemFixtures.label()
                        .itemId(scenario.item().getId())
                        .title("Item")
                        .noun("Pull request")
                        .number(1)
                        .container("Container")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
    }
}
