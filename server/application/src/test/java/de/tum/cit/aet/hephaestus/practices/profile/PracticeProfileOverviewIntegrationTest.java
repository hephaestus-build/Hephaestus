package de.tum.cit.aet.hephaestus.practices.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeProfileOverviewIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccountRepository accounts;

    // The scenario's work has no pull request rows behind it; the real source check is proven elsewhere.
    @MockitoBean
    ScmItemSourceAccess source;

    Scenario scenario;

    long reader;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        reader = Objects.requireNonNull(scenario.account().getId());
        when(source.kinds()).thenReturn(Set.of(new ItemKind("scm.pull_request")));
        when(source.permits(any(), any())).thenReturn(true);
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

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldReportEachPersistedStateInTheReadersOverviewListAndDetail(ReviewStatus status) {
        var review = data.result(scenario, ResultOutcome.MET, Instant.now(), status);
        if (!status.isTerminal()) data.activeJob(review, status.name(), null);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview.status")
                .isEqualTo(status.name());
        get("/practice-profile/reviews?status=" + status)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].review.id")
                .isEqualTo(review.getReviewId().toString())
                .jsonPath("$.content[0].review.status")
                .isEqualTo(status.name());
        get("/practice-profile/reviews/" + review.getReviewId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.review.status")
                .isEqualTo(status.name());
        var excluded = status == ReviewStatus.QUEUED ? ReviewStatus.RUNNING : ReviewStatus.QUEUED;
        get("/practice-profile/reviews?status=" + excluded)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
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

    @Test
    void shouldReportTheReadersNewestReviewInFlightWhenItHasNoResultYet() {
        data.activeReview(scenario, reader, ReviewStatus.QUEUED, Instant.now().minusSeconds(120), null);
        var newest = data.activeReview(
                scenario, reader, ReviewStatus.RUNNING, Instant.now().minusSeconds(60), null);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview.reviewId")
                .isEqualTo(newest.getId().toString())
                .jsonPath("$.activeReview.status")
                .isEqualTo("RUNNING")
                .jsonPath("$.activeReview.item.id")
                .isEqualTo(Long.toString(scenario.item().getId()))
                .jsonPath("$.latestReview")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewStatus.class,
            names = {"QUEUED", "RUNNING"})
    void shouldReportThePersistedStateOfAReviewInFlight(ReviewStatus status) {
        var review = data.activeReview(scenario, reader, status, Instant.now(), null);
        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview.reviewId")
                .isEqualTo(review.getId().toString())
                .jsonPath("$.activeReview.status")
                .isEqualTo(status.name());
    }

    @Test
    void shouldShowTheReaderThatTheirReviewContinuesWithHowManyPracticesItEnded() {
        var review = data.continuingReview(scenario, reader, 2, 3);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview.reviewId")
                .isEqualTo(review.getId().toString())
                .jsonPath("$.activeReview.status")
                .isEqualTo("QUEUED")
                .jsonPath("$.activeReview.progress.practicesEnded")
                .isEqualTo(2)
                .jsonPath("$.activeReview.progress.practicesTotal")
                .isEqualTo(3)
                .jsonPath("$.activeReview.progress.continuing")
                .isEqualTo(true);
    }

    @Test
    void shouldNotReportAnotherMembersReviewInFlightEvenWhenTheReaderRequestedIt() {
        long other =
                Objects.requireNonNull(data.member(scenario.workspace().getId()).getId());
        data.activeReview(scenario, other, ReviewStatus.RUNNING, Instant.now(), reader);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview")
                .isEmpty();
    }

    @Test
    void shouldNotReportTheReadersReviewInFlightFromAnotherWorkspace() {
        data.activeReview(data.scenario(), reader, ReviewStatus.RUNNING, Instant.now(), null);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview")
                .isEmpty();
    }

    @Test
    void shouldNotReportAReviewInFlightThatAlreadyRecordedAResultAboutTheReader() {
        var recorded = data.result(scenario, ResultOutcome.MET, Instant.now(), ReviewStatus.RUNNING);
        // Another practice of the same review still waits: its listed item alone does not make the review new.
        jdbc.sql("UPDATE review_item SET state='LISTED',result_id=NULL WHERE review_id=:id")
                .param("id", recorded.getReviewId())
                .update();

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.latestReview.reviewId")
                .isEqualTo(recorded.getReviewId().toString())
                .jsonPath("$.activeReview")
                .isEmpty();
    }

    @Test
    void shouldNotReportTheReviewInFlightWhenTheReaderMayNotSeeTheWork() {
        data.activeReview(scenario, reader, ReviewStatus.QUEUED, Instant.now(), null);
        when(source.permits(any(), any())).thenReturn(false);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activeReview")
                .isEmpty();
    }

    @Test
    void shouldReportNoModelAndThePracticesEventsWhenNoReviewModelIsSetUp() {
        jdbc.sql("UPDATE practice SET feedback_mode='APPROVE_FIRST' WHERE id=:id")
                .param("id", scenario.practice().getId())
                .update();

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewReadiness.blocker")
                .isEqualTo("NO_MODEL")
                .jsonPath("$.reviewReadiness.events[0]")
                .isEqualTo("scm.pull_request.opened")
                .jsonPath("$.reviewReadiness.approvalBeforePosting")
                .isEqualTo(true);
    }

    @Test
    void shouldReportNoAiRatherThanTheReviewScopeWhenTheReaderChoseNoAi() {
        var account = accounts.findById(reader).orElseThrow();
        account.setAiChoice(AiChoice.NO_AI);
        account.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(account);

        get("/practice-profile/overview")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewReadiness.blocker")
                .isEqualTo("NO_AI");
    }
}
