package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeGroupReviewHistoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

    @BeforeEach
    void permitStoredEvidence() {
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenAnswer(i -> {
                    long ws = i.getArgument(0);
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream()
                            .filter(r -> r.getWorkspaceId() == ws)
                            .map(Result::getId)
                            .collect(Collectors.toSet());
                });
    }

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

    @Autowired
    PracticeRepository practices;

    @Test
    void reviewsAreFilteredByTheirStoredPracticeGroup() {
        var group = new PracticeGroup();
        group.setWorkspace(scenario.workspace());
        group.setSlug("testing");
        group.setName("Testing");
        groups.saveAndFlush(group);
        scenario.practice().setGroup(group);
        practices.saveAndFlush(scenario.practice());
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practice-groups/testing/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(r.getReviewId().toString());
    }

    @Test
    void anotherWorkspaceGroupDoesNotExposeReviews() {
        var other = data.scenario();
        var group = new PracticeGroup();
        group.setWorkspace(other.workspace());
        group.setSlug("foreign");
        group.setName("Foreign");
        groups.saveAndFlush(group);
        data.result(other, ResultOutcome.MET, Instant.now());
        get("/practice-groups/foreign/reviews").expectStatus().isNotFound().expectBody(Void.class);
    }
}
