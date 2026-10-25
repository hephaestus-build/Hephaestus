package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeGroupStandingIntegrationTest extends BaseIntegrationTest {
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
    PracticeGroupRepository groups;

    @Test
    void emptyShownGroupHasAnExplicitNotObservedStanding() {
        var group = new PracticeGroup();
        group.setWorkspace(scenario.workspace());
        group.setSlug("testing");
        group.setName("Testing");
        groups.saveAndFlush(group);
        get("/practice-groups/standings")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].groupSlug")
                .isEqualTo("testing")
                .jsonPath("$[0].standing")
                .isEqualTo("NOT_OBSERVED");
    }

    @Test
    void hiddenGroupIsNotShownOnTheDevelopersPage() {
        var group = new PracticeGroup();
        group.setWorkspace(scenario.workspace());
        group.setSlug("hidden");
        group.setName("Hidden");
        group.setShownToDevelopers(false);
        groups.saveAndFlush(group);
        get("/practice-groups/standings")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    void shownGroupCarriesItsAuthoredDescriptionIconAndColorBesideAHiddenOne() {
        var shown = new PracticeGroup();
        shown.setWorkspace(scenario.workspace());
        shown.setSlug("testing");
        shown.setName("Testing");
        shown.setDescription("How we test what we ship.");
        shown.setIcon("flask-conical");
        shown.setColor("teal");
        groups.saveAndFlush(shown);
        var hidden = new PracticeGroup();
        hidden.setWorkspace(scenario.workspace());
        hidden.setSlug("hidden");
        hidden.setName("Hidden");
        hidden.setShownToDevelopers(false);
        groups.saveAndFlush(hidden);
        get("/practice-groups/standings")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].groupSlug")
                .isEqualTo("testing")
                .jsonPath("$[0].groupDescription")
                .isEqualTo("How we test what we ship.")
                .jsonPath("$[0].groupIcon")
                .isEqualTo("flask-conical")
                .jsonPath("$[0].groupColor")
                .isEqualTo("teal")
                .jsonPath("$[0].standing")
                .isEqualTo("NOT_OBSERVED");
    }

    @Test
    void groupWithoutAuthoredMetadataSendsNulls() {
        var group = new PracticeGroup();
        group.setWorkspace(scenario.workspace());
        group.setSlug("testing");
        group.setName("Testing");
        groups.saveAndFlush(group);
        get("/practice-groups/standings")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].groupDescription")
                .isEmpty()
                .jsonPath("$[0].groupIcon")
                .isEmpty()
                .jsonPath("$[0].groupColor")
                .isEmpty();
    }
}
