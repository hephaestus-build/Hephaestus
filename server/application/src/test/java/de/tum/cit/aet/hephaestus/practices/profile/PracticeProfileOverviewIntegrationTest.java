package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /practice-profile/overview} against a seeded history: three runs on three pull requests, the
 * feedback the last one composed and the response the developer gave to an older one. Every assertion is on
 * rows this class wrote; the window is what makes a change a change, so a test that needs another window seeds
 * the run that opens it.
 */
class PracticeProfileOverviewIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String OVERVIEW_URI = "/workspaces/{workspaceSlug}/practice-profile/overview";

    /** Whole seconds, so what Postgres stores is what the JSON says. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static final Instant FIRST_RUN_AT = NOW.minus(Duration.ofDays(10));
    private static final Instant PREVIOUS_RUN_AT = NOW.minus(Duration.ofDays(5));
    private static final Instant LATEST_RUN_AT = NOW.minus(Duration.ofDays(1));
    private static final String BODY = InAppFeedbackBody.render("A habit", "What recurs.", "One thing to try.");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Workspace workspace;
    private User developer;
    private Practice describeWhatAndWhy;
    private Practice reviewableDiffSize;
    private AgentJob previousRun;
    private AgentJob latestRun;
    private UUID olderProblem;
    private Feedback olderFeedback;
    private Feedback latestFeedback;

    @BeforeEach
    void seedHistory() {
        User owner = persistUser("profile-overview-owner");
        workspace = createWorkspace(
                "profile-overview-ws", "Profile Overview WS", "profile-overview-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug("review-ready-work");
        group.setName("Packaging work for review");
        group = groupRepository.save(group);

        // Renamed in the workspace; the catalog phrase still follows the source slug.
        describeWhatAndWhy =
                persistPractice(workspace, group, "explain-changes", "Explain each change", "describe-what-and-why");
        reviewableDiffSize =
                persistPractice(workspace, group, "reviewable-diff-size", "Keep the diff reviewable", null);

        AgentJob firstRun = persistPullRequestReview(workspace, 20, FIRST_RUN_AT);
        previousRun = persistPullRequestReview(workspace, 21, PREVIOUS_RUN_AT);
        latestRun = persistPullRequestReview(workspace, 22, LATEST_RUN_AT);

        // Descriptions held on every pull request; the diff slipped on #20, recovered on #21 and slipped again on #22.
        observe(describeWhatAndWhy, firstRun, 20L, developer, "PRESENT", "GOOD", null, FIRST_RUN_AT);
        olderProblem = observe(reviewableDiffSize, firstRun, 20L, developer, "ABSENT", "GOOD", "MINOR", FIRST_RUN_AT);
        observe(describeWhatAndWhy, previousRun, 21L, developer, "PRESENT", "GOOD", null, PREVIOUS_RUN_AT);
        observe(reviewableDiffSize, previousRun, 21L, developer, "PRESENT", "GOOD", null, PREVIOUS_RUN_AT);
        observe(describeWhatAndWhy, latestRun, 22L, developer, "PRESENT", "GOOD", null, LATEST_RUN_AT);
        UUID latestProblem =
                observe(reviewableDiffSize, latestRun, 22L, developer, "ABSENT", "GOOD", "MAJOR", LATEST_RUN_AT);

        olderFeedback = persistInAppFeedback(
                firstRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, FIRST_RUN_AT.plusSeconds(30));
        bind(olderFeedback, olderProblem);
        latestFeedback = persistInAppFeedback(
                latestRun, developer, 1, FeedbackDeliveryState.PREPARED, BODY, LATEST_RUN_AT.plusSeconds(30));
        bind(latestFeedback, latestProblem);

        // The developer addressed the older feedback after the latest run had already spoken.
        markAddressed(olderFeedback, developer, LATEST_RUN_AT.plus(Duration.ofHours(2)));
    }

    @Test
    @WithUser
    @DisplayName("the window opens at the previous run and reports what the latest one changed")
    void shouldReportTheLatestRunsChanges() {
        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.window.since")
                .isEqualTo(PREVIOUS_RUN_AT.toString())
                .jsonPath("$.latestRun.jobId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.latestRun.at")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.latestRun.reviewedWork.label")
                .isEqualTo("#22")
                .jsonPath("$.latestRun.reviewedWork.url")
                .isEqualTo("https://github.com/acme/api/pull/22")
                .jsonPath("$.reviewedWork.length()")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork[0].label")
                .isEqualTo("#22")
                .jsonPath("$.reviewedWork[0].kind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$.reviewedWork[0].repositoryName")
                .isEqualTo("acme/api")
                // Holding: clean on all three pull requests, and named by the catalog's phrase.
                .jsonPath("$.holdingUp.length()")
                .isEqualTo(1)
                .jsonPath("$.holdingUp[0].practiceSlug")
                .isEqualTo("explain-changes")
                .jsonPath("$.holdingUp[0].holdsAs")
                .isEqualTo("Every description says what changed and why")
                .jsonPath("$.holdingUp[0].cleanWork")
                .isEqualTo(3)
                .jsonPath("$.holdingUp[0].workKind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$.holdingUp[0].workProvider")
                .isEqualTo("GITHUB")
                .jsonPath("$.holdingUp[0].since")
                .isEqualTo(FIRST_RUN_AT.toString())
                // The second slip on #22 pulled the practice down and the group with it.
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].from")
                .isEqualTo("MIXED")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].to")
                .isEqualTo("DEVELOPING")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].evidence[0].label")
                .isEqualTo("#22")
                .jsonPath("$.changes[?(@.type == 'GROUP_MOVED')].groupSlug")
                .isEqualTo("review-ready-work")
                .jsonPath("$.changes[?(@.type == 'GROUP_MOVED')].from")
                .isEqualTo("STRENGTH")
                .jsonPath("$.changes[?(@.type == 'GROUP_MOVED')].to")
                .isEqualTo("MIXED")
                // The feedback the latest run composed is new; the older one the developer addressed is resolved.
                // Both are about the same practice and both stay: they are different types.
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].feedbackId")
                .isEqualTo(latestFeedback.getId().toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].feedbackId")
                .isEqualTo(olderFeedback.getId().toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].resolvedBy")
                .isEqualTo("DEVELOPER")
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].at")
                .isEqualTo(LATEST_RUN_AT.plus(Duration.ofHours(2)).toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].evidence[0].label")
                .isEqualTo("#20")
                .jsonPath("$.changes[?(@.type == 'FIRST_OBSERVED')]")
                .isEmpty()
                .jsonPath("$.changes.length()")
                .isEqualTo(4)
                // Newest first: the response came two hours after the run.
                .jsonPath("$.changes[0].type")
                .isEqualTo("FEEDBACK_RESOLVED");
    }

    /**
     * A fourth run moves the window: it now opens at the third, so the third's changes are history and what the
     * fourth changed is reported. The diff recovered on #23, and the feedback #22 composed is not new again.
     */
    @Test
    @WithUser
    @DisplayName("a newer run opens the window at the run before it and reports what it changed")
    void shouldMoveTheWindowWithANewerRun() {
        Instant recoveryRunAt = NOW.minus(Duration.ofHours(12));
        AgentJob recoveryRun = persistPullRequestReview(workspace, 23, recoveryRunAt);
        observe(describeWhatAndWhy, recoveryRun, 23L, developer, "PRESENT", "GOOD", null, recoveryRunAt);
        observe(reviewableDiffSize, recoveryRun, 23L, developer, "PRESENT", "GOOD", null, recoveryRunAt);

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.window.since")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.latestRun.jobId")
                .isEqualTo(recoveryRun.getId().toString())
                .jsonPath("$.reviewedWork.length()")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork[0].label")
                .isEqualTo("#23")
                // #23 lifted the diff practice out of the slip on #22, and the group with it.
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].from")
                .isEqualTo("DEVELOPING")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].to")
                .isEqualTo("MIXED")
                .jsonPath("$.changes[?(@.type == 'STANDING_MOVED')].evidence[0].label")
                .isEqualTo("#23")
                .jsonPath("$.changes[?(@.type == 'GROUP_MOVED')].from")
                .isEqualTo("MIXED")
                .jsonPath("$.changes[?(@.type == 'GROUP_MOVED')].to")
                .isEqualTo("STRENGTH")
                // The feedback #22 composed was new in the window before this one, not in this one.
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')]")
                .isEmpty()
                // The developer's response came two hours after #22, inside this window.
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_RESOLVED')].feedbackId")
                .isEqualTo(olderFeedback.getId().toString())
                .jsonPath("$.changes.length()")
                .isEqualTo(3)
                // Descriptions are clean on all four pull requests; the diff is not.
                .jsonPath("$.holdingUp.length()")
                .isEqualTo(1)
                .jsonPath("$.holdingUp[0].practiceSlug")
                .isEqualTo("explain-changes")
                .jsonPath("$.holdingUp[0].cleanWork")
                .isEqualTo(4);
    }

    /**
     * Feedback about descriptions, prepared before the seeded history, is resolved by the three clean pull
     * requests: the change is dated by the third and names the three.
     */
    @Test
    @WithUser
    @DisplayName(
            "feedback the developer's work resolved is a change inside the window the third clean piece of work falls in")
    void shouldReportFeedbackResolvedByTheWork() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].feedbackId")
                .isEqualTo(feedback.getId().toString())
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].type")
                .isEqualTo("FEEDBACK_RESOLVED")
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].practiceSlug")
                .isEqualTo("explain-changes")
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].at")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].evidence[*].label")
                .isEqualTo(List.of("#22", "#21", "#20"))
                .jsonPath("$.changes[?(@.resolvedBy == 'WORK')].evidence[0].url")
                .isEqualTo("https://github.com/acme/api/pull/22")
                // The developer's own response on the other practice is reported as before.
                .jsonPath("$.changes[?(@.resolvedBy == 'DEVELOPER')].feedbackId")
                .isEqualTo(olderFeedback.getId().toString());
    }

    /**
     * The resolution is dated by the third clean piece of work, so feedback whose third clean piece came before
     * the window opened resolved then: two clean pull requests before the seeded history make #20 the third.
     */
    @Test
    @WithUser
    @DisplayName("feedback the work resolved before the window opened is not a change inside it")
    void shouldNotReportFeedbackResolvedBeforeTheWindow() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(3)));
        for (int number = 17; number <= 18; number++) {
            Instant reviewedAt = FIRST_RUN_AT.minus(Duration.ofDays(20 - number));
            AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
            observe(describeWhatAndWhy, run, number, developer, "PRESENT", "GOOD", null, reviewedAt);
        }

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * The work that could resolve feedback is read off the standing's look-back, so feedback prepared before
     * it cannot resolve through the profile: the third clean pull request falls inside the window, but the
     * run of three would have to be counted from before the look-back began.
     */
    @Test
    @WithUser
    @DisplayName("feedback prepared before the look-back is not resolved by the work inside the window")
    void shouldNotResolveFeedbackPreparedBeforeTheLookBack() {
        Feedback feedback =
                describingFeedbackPreparedAt(NOW.minus(Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS + 1)));

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * The read is bounded by the window and the look-back, not by the developer's history: two hundred cards
     * resolved long ago add no query and no loaded row to the request.
     */
    @Test
    @WithUser
    @DisplayName("feedback resolved before the look-back costs the overview nothing to read past")
    void shouldNotReadFeedbackResolvedBeforeTheLookBack() {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            getOverviewOk();
            statistics.clear();
            getOverviewOk();
            long queriesWithoutHistory = statistics.getQueryExecutionCount();
            long rowsWithoutHistory = statistics.getEntityLoadCount();

            Instant longAgo = NOW.minus(Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS + 30));
            for (int number = 100; number < 300; number++) {
                Feedback resolved = describingFeedbackPreparedAt(number, longAgo.plusSeconds(number));
                markAddressed(
                        resolved, developer, longAgo.plus(Duration.ofDays(1)).plusSeconds(number));
            }

            statistics.clear();
            getOverviewOk();
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(queriesWithoutHistory);
            assertThat(statistics.getEntityLoadCount()).isEqualTo(rowsWithoutHistory);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    /**
     * A piece of feedback resolved both ways resolved once, the earlier way: the work resolved this one with
     * the latest run, and the developer marking it addressed three hours later is not a second resolution.
     */
    @Test
    @WithUser
    @DisplayName("feedback the work resolved and the developer then marked addressed is reported once, by the work")
    void shouldReportTheEarlierOfTheTwoResolutions() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));
        markAddressed(feedback, developer, LATEST_RUN_AT.plus(Duration.ofHours(3)));

        String thisFeedback = "$.changes[?(@.feedbackId == '" + feedback.getId() + "')]";
        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // One match, or the assertion refuses the list: the feedback resolved once.
                .jsonPath(thisFeedback + ".resolvedBy")
                .isEqualTo("WORK")
                .jsonPath(thisFeedback + ".at")
                .isEqualTo(LATEST_RUN_AT.toString());
    }

    /**
     * The earlier way may lie before the window: feedback the developer marked addressed before the window
     * opened resolved then, and the work completing a clean run inside the window changes nothing.
     */
    @Test
    @WithUser
    @DisplayName("feedback marked addressed before the window is not resolved again by the work inside it")
    void shouldNotReportAWorkResolutionOfFeedbackAddressedBeforeTheWindow() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));
        markAddressed(feedback, developer, FIRST_RUN_AT.plus(Duration.ofHours(1)));

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * The feedback row is written moments after the run's newest observation, so feedback the previous run
     * composed would otherwise fall inside a window that opens on that run.
     */
    @Test
    @WithUser
    @DisplayName("new feedback is dated by the run that composed it, so the previous run's is not new in the window")
    void shouldDateNewFeedbackByItsRun() {
        // The previous run spoke about the slip on #20 too, moments after its own observation.
        Feedback previousRunsFeedback = persistInAppFeedback(
                previousRun, developer, 2, FeedbackDeliveryState.DELIVERED, BODY, PREVIOUS_RUN_AT.plusSeconds(30));
        bind(previousRunsFeedback, olderProblem);

        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].feedbackId")
                .isEqualTo(latestFeedback.getId().toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].at")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.changes[?(@.feedbackId == '" + previousRunsFeedback.getId() + "')]")
                .isEmpty();
    }

    @Test
    @WithUser
    @DisplayName("a developer who is not a member of the workspace is refused")
    void shouldRefuseANonMember() {
        User outsider = persistUser("outsider-owner");
        Workspace other =
                createWorkspace("profile-other-ws", "Other WS", "profile-other-org", AccountType.ORG, outsider);

        webTestClient
                .get()
                .uri(OVERVIEW_URI, other.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private void getOverviewOk() {
        webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk();
    }

    /** Delivered feedback about descriptions, written from a slip on pull request #19 reviewed at {@code preparedAt}. */
    private Feedback describingFeedbackPreparedAt(Instant preparedAt) {
        return describingFeedbackPreparedAt(19, preparedAt);
    }

    private Feedback describingFeedbackPreparedAt(int number, Instant preparedAt) {
        AgentJob run = persistPullRequestReview(workspace, number, preparedAt);
        UUID problem = observe(describeWhatAndWhy, run, number, developer, "ABSENT", "GOOD", "MINOR", preparedAt);
        Feedback feedback = persistInAppFeedback(
                run, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, preparedAt.plusSeconds(30));
        bind(feedback, problem);
        return feedback;
    }
}
