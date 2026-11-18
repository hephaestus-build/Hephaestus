package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.practices.profile.ProfileOverviewRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRetentionService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeTrialControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    WebTestClient http;

    @Autowired
    ProfileOverviewRepository profiles;

    @Autowired
    ResultRetentionService retention;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PracticeTrialService trials;

    @Autowired
    AccountRepository accounts;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practice-trials";
    }

    WebTestClient.ResponseSpec start(String token, Map<String, String> body) {
        return http.post()
                .uri(root())
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    Map<String, String> trialOfScenario() {
        return Map.of(
                "practiceSlug",
                scenario.practice().getSlug(),
                "itemId",
                scenario.item().getId().toString());
    }

    @Test
    void shouldRefuseATrialToAMemberWhoIsNoAdmin() {
        var member = data.member(scenario.workspace().getId());
        start("mock-jwt-member-" + member.getId(), trialOfScenario())
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseATrialThatNamesTheItemTwiceOrNotAtAll() {
        start(
                        ReviewIntegrationData.memberToken(scenario),
                        Map.of(
                                "practiceSlug", scenario.practice().getSlug(),
                                "itemId", scenario.item().getId().toString(),
                                "itemUrl", "https://github.com/acme/app/pull/1"))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        start(
                        ReviewIntegrationData.memberToken(scenario),
                        Map.of("practiceSlug", scenario.practice().getSlug()))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldAnswerNotFoundForAPracticeThatTheWorkspaceLacks() {
        start(
                        ReviewIntegrationData.memberToken(scenario),
                        Map.of(
                                "practiceSlug",
                                "no-such-practice",
                                "itemId",
                                scenario.item().getId().toString()))
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAnotherTrialWhileOneIsActive() {
        admissions.pending(scenario, Instant.now(), null, "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        start(ReviewIntegrationData.memberToken(scenario), trialOfScenario())
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Another trial is still running in this workspace. Try again when it ends.");
    }

    @Test
    void shouldNotShowARunningTrialOnTheDevelopersPracticePage() {
        long developer = Objects.requireNonNull(scenario.account().getId());
        var trial = admissions.pending(
                scenario, Instant.now(), developer, "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        assertThat(profiles.activeReview(scenario.workspace().getId(), developer))
                .isEmpty();
        var request = admissions.pending(scenario, Instant.now(), developer);
        assertThat(profiles.activeReview(scenario.workspace().getId(), developer))
                .hasValueSatisfying(active -> assertThat(active.getReviewId())
                        .isEqualTo(request.review().getId())
                        .isNotEqualTo(trial.review().getId()));
    }

    @Test
    void shouldClearTheReportOfATrialThatEndedBeforeItsRetention() {
        var expired = endedTrial(Instant.now().minus(Duration.ofDays(31)));
        var recent = endedTrial(Instant.now().minus(Duration.ofDays(1)));

        retention.purge();

        assertThat(read(expired).outcomeRemoved()).isTrue();
        assertThat(jdbc.queryForObject("SELECT report IS NULL FROM review WHERE id=?", Boolean.class, expired))
                .isTrue();
        assertThat(read(recent).outcomeRemoved()).isFalse();
        assertThat(jdbc.queryForObject("SELECT report IS NULL FROM review WHERE id=?", Boolean.class, recent))
                .isFalse();
    }

    private UUID endedTrial(Instant endedAt) {
        var trial = admissions.pending(
                scenario, Instant.now(), null, "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        UUID id = trial.review().getId();
        jdbc.update("UPDATE review_item SET state='TRIED' WHERE review_id=?", id);
        jdbc.update(
                "UPDATE review SET status='COMPLETE',completed_at=?,report=CAST(? AS jsonb) WHERE id=?",
                Timestamp.from(endedAt),
                "{\"schemaVersion\":1,\"candidates\":[]}",
                id);
        return id;
    }

    private TrialDTO read(UUID id) {
        return Objects.requireNonNull(http.get()
                .uri(root() + "/" + id)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TrialDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    void shouldReadATrialAndNotAReviewOfAnotherOrigin() {
        var trial = admissions.pending(
                scenario, Instant.now(), null, "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        var read = http.get()
                .uri(root() + "/" + trial.review().getId())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TrialDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(read).isNotNull();
        assertThat(read.status()).isEqualTo(ReviewStatus.RUNNING);
        assertThat(read.practiceSlug()).isEqualTo(scenario.practice().getSlug());
        assertThat(read.itemId()).isEqualTo(scenario.item().getId().toString());
        assertThat(read.outcome()).isNull();

        var request = admissions.pending(scenario, Instant.now(), null);
        http.get()
                .uri(root() + "/" + request.review().getId())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    /** ADR 0110, update of 2026-11-16: an admin's MCP client reads the findings only of work whose people chose Cloud. */
    @Test
    void shouldLetAnMcpClientReadTheFindingsOnlyOfWorkWhosePeopleChoseCloud() {
        var developer = scenario.account();
        var unattributed = admissions.pending(
                scenario, Instant.now(), null, "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        var attributed = admissions.pending(
                scenario, Instant.now(), developer.getId(), "item-version-" + UUID.randomUUID(), ReviewOrigin.TRIAL);
        long workspaceId = scenario.workspace().getId();

        developer.setAiChoice(AiChoice.IN_HOUSE_ONLY);
        accounts.saveAndFlush(developer);
        assertThat(trials.readsOnlyCloudWork(workspaceId, attributed.review().getId()))
                .isFalse();

        developer.setAiChoice(AiChoice.CLOUD);
        accounts.saveAndFlush(developer);
        assertThat(trials.readsOnlyCloudWork(workspaceId, attributed.review().getId()))
                .isTrue();
        // Work that the review attributes to nobody has no one who chose Cloud.
        assertThat(trials.readsOnlyCloudWork(workspaceId, unattributed.review().getId()))
                .isFalse();
    }
}
