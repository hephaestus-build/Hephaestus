package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticesAcrossWorkspaceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcTemplate jdbc;

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
        Mockito.when(evidenceAuthorization.asOneRead(ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
        Mockito.when(sources.permitsHumanStanding(ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection()))
                .thenAnswer(i -> sameWorkspace(i.getArgument(0), i.getArgument(1)));
    }

    static Set<UUID> sameWorkspace(long workspaceId, Collection<ResultFacts> rows) {
        return rows.stream()
                .filter(r -> r.workspaceId() == workspaceId)
                .map(ResultFacts::id)
                .collect(Collectors.toSet());
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri("/workspaces/" + scenario.workspace().getWorkspaceSlug() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    /**
     * Staff choices about one named person leave the page unchanged, so comparing two reads tells nothing about where
     * that person stands. Each lever is applied, read and taken back.
     */
    @Test
    void shouldShowTheSamePageWhenStaffChangeOneNamedDevelopersStatus() {
        long workspaceId = scenario.workspace().getId();
        List<Long> developers = new ArrayList<>();
        for (int developer = 0; developer < 6; developer++) {
            var account = data.member(workspaceId);
            var work = data.onOtherWork(scenario);
            data.result(
                    new Scenario(scenario.workspace(), account, scenario.practice(), work.item()),
                    developer < 3 ? ResultOutcome.MET : ResultOutcome.NOT_MET,
                    Instant.now().minus(Duration.ofDays(2)));
            developers.add(account.getId());
        }
        long named = developers.getFirst();
        String overview = body("/practices/workspace-overview");
        String tiles = body("/practices/tiles?window=DAYS_30");
        get("/practices/tiles?window=DAYS_30")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.peopleWithAStandingInWindow")
                .isEqualTo(6);

        Map<String, List<String>> levers = Map.of(
                "hide the member", List.of("UPDATE membership SET hidden = (account_id = ?) WHERE workspace_id = ?"),
                "select everyone else for review",
                        List.of(
                                "UPDATE membership SET review_selected = (account_id <> ?) WHERE workspace_id = ?",
                                "UPDATE workspace SET review_scope_people = 'SELECTED' WHERE ? > 0 AND id = ?"),
                "end their membership",
                        List.of("UPDATE membership SET left_at = CASE WHEN account_id = ? THEN now() END"
                                + " WHERE workspace_id = ?"));
        List<String> restore = List.of(
                "UPDATE membership SET hidden = false, review_selected = false, left_at = NULL"
                        + " WHERE ? > 0 AND workspace_id = ?",
                "UPDATE workspace SET review_scope_people = 'ALL' WHERE ? > 0 AND id = ?");
        // Not a lever here: suspending an account stops processing of that person's data, a privacy control that
        // the evidence policy honours. NP-05's release ledger closes the comparison it allows.
        levers.forEach((lever, change) -> {
            change.forEach(statement -> jdbc.update(statement, named, workspaceId));
            assertThat(body("/practices/workspace-overview")).as(lever).isEqualTo(overview);
            assertThat(body("/practices/tiles?window=DAYS_30")).as(lever).isEqualTo(tiles);
            restore.forEach(statement -> jdbc.update(statement, named, workspaceId));
        });
    }

    String body(String path) {
        return new String(
                Objects.requireNonNull(get(path)
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult()
                        .getResponseBody()),
                StandardCharsets.UTF_8);
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
    void shouldCountOnlyTheLastSevenDaysWhenTheTilesAskForThatWindow() {
        data.result(scenario, ResultOutcome.MET, Instant.now().minus(Duration.ofDays(10)));
        data.result(data.onOtherWork(scenario), ResultOutcome.MET, Instant.now().minus(Duration.ofDays(2)));
        get("/practices/tiles?window=DAYS_7")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.window")
                .isEqualTo("DAYS_7")
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1);
        get("/practices/tiles?window=DAYS_30")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(2);
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
                .jsonPath("$.peopleWithAStandingInWindow")
                .isEqualTo(0);
    }
}
