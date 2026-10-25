package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticesAcrossWorkspaceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

    // The fixture records no capture custody; the standing fences themselves are covered by
    // ResultSourceAuthorizationTest and ResultVisibilityPolicyTest.
    @MockitoBean
    ResultSourceAuthorization sources;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenAnswer(i -> sameWorkspace(i.getArgument(0), i.getArgument(1)));
        Mockito.when(evidenceAuthorization.asOneRead(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(2).get());
        Mockito.when(sources.permitsHumanStanding(ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection()))
                .thenAnswer(i -> sameWorkspace(i.getArgument(0), i.getArgument(1)));
    }

    static Set<UUID> sameWorkspace(long workspaceId, Collection<Result> rows) {
        return rows.stream()
                .filter(r -> r.getWorkspaceId() == workspaceId)
                .map(Result::getId)
                .collect(Collectors.toSet());
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri("/workspaces/" + scenario.workspace().getWorkspaceSlug() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldReturnCountsAndTheOpenFeedbackTileWhenAMemberReadsTheOverview() {
        data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/workspace-overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.groups")
                .isArray()
                .jsonPath("$.openFeedback.yours")
                .isEqualTo(0)
                .jsonPath("$.includesEarlierReviews")
                .isEqualTo(false)
                .jsonPath("$.practices")
                .doesNotExist();
    }

    @Test
    void shouldReadTheTilesOverTheRequestedWindowWhenWorkIsOlderThanThirtyDays() {
        data.result(scenario, ResultOutcome.MET, Instant.now().minus(Duration.ofDays(60)));
        get("/practices/tiles?window=DAYS_30")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.window")
                .isEqualTo("DAYS_30")
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(0);
        get("/practices/tiles?window=DAYS_90")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1);
        get("/practices/tiles?window=ALL_TIME")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1);
    }

    @Test
    void shouldNotCountAnotherWorkspacesWorkWhenTheReadersWorkspaceHasNone() {
        data.result(data.scenario(), ResultOutcome.NOT_MET, Instant.now());
        get("/practices/tiles?window=ALL_TIME")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(0)
                .jsonPath("$.developersWithAStandingInWindow")
                .isEqualTo(0);
    }
}
