package de.tum.cit.aet.hephaestus.practices.profile;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.DEMONSTRATED_STRENGTH;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /practice-profile/overview} against a seeded history: three runs on three pull requests, the
 * feedback the last one composed and the response the developer gave to an older one. Every assertion is on
 * rows this class wrote; the window is what makes a change a change, so a test that needs another window seeds
 * the run that opens it.
 */
class PracticeProfileOverviewIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String OVERVIEW_URI = "/workspaces/{workspaceSlug}/practice-profile/overview";

    private static final Instant FIRST_RUN_AT = NOW.minus(Duration.ofDays(10));
    private static final Instant PREVIOUS_RUN_AT = NOW.minus(Duration.ofDays(5));
    private static final Instant LATEST_RUN_AT = NOW.minus(Duration.ofDays(1));
    private static final String BODY = InAppFeedbackBody.render("A habit", "What recurs.", "One thing to try.");

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private BundledPracticeCatalogLoader catalog;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private User developer;
    private Practice describeWhatAndWhy;
    private Practice reviewableDiffSize;
    private Practice releaseNotes;
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
        releaseNotes = persistPractice(workspace, group, "release-notes", "Write the release note", null);

        AgentJob firstRun = persistPullRequestReview(workspace, 20, FIRST_RUN_AT);
        previousRun = persistPullRequestReview(workspace, 21, PREVIOUS_RUN_AT);
        latestRun = persistPullRequestReview(workspace, 22, LATEST_RUN_AT);

        // Descriptions held on every pull request; the diff slipped on #20, recovered on #21 and slipped again on #22.
        observe(describeWhatAndWhy, firstRun, 20L, developer, DEMONSTRATED_STRENGTH, null, FIRST_RUN_AT);
        olderProblem =
                observe(reviewableDiffSize, firstRun, 20L, developer, OMISSION_GAP, Severity.MINOR, FIRST_RUN_AT);
        observe(describeWhatAndWhy, previousRun, 21L, developer, DEMONSTRATED_STRENGTH, null, PREVIOUS_RUN_AT);
        observe(reviewableDiffSize, previousRun, 21L, developer, DEMONSTRATED_STRENGTH, null, PREVIOUS_RUN_AT);
        observe(describeWhatAndWhy, latestRun, 22L, developer, DEMONSTRATED_STRENGTH, null, LATEST_RUN_AT);
        UUID latestProblem =
                observe(reviewableDiffSize, latestRun, 22L, developer, OMISSION_GAP, Severity.MAJOR, LATEST_RUN_AT);

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
    @DisplayName("an instance administrator viewing as the developer reads their overview and delivers nothing")
    void shouldServeTheViewedDevelopersOverviewAndDeliverNothingWhenAnAdministratorViewsIt() {
        readAsUserView(OVERVIEW_URI, workspace, developer)
                .jsonPath("$.latestRun.jobId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].feedbackId")
                .isEqualTo(latestFeedback.getId().toString());

        assertThat(feedbackRepository.findById(latestFeedback.getId()))
                .get()
                .extracting(Feedback::getDeliveryState)
                .isEqualTo(FeedbackDeliveryState.PREPARED);
        Long viewsRecorded = jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'USER_VIEW' AND viewed_user_id = ?",
                Long.class,
                developer.getId());
        assertThat(viewsRecorded).isEqualTo(1L);
    }

    @Test
    @WithUser
    @DisplayName("the window opens at the previous run and reports what the latest one changed")
    void shouldReportWhatTheLatestRunChangedWhenTheWindowOpensAtThePreviousRun() {
        readOverview()
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
                .isEqualTo(catalog.holdsAs("describe-what-and-why").orElseThrow())
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
     * A fourth run moves the window: it opens at the third, so the third's changes are history and what the
     * fourth changed is reported. The diff recovered on #23, and the feedback #22 composed is not new again.
     */
    @Test
    @WithUser
    @DisplayName("a newer run opens the window at the run before it and reports what it changed")
    void shouldMoveTheWindowWhenANewerRunArrives() {
        Instant recoveryRunAt = NOW.minus(Duration.ofHours(12));
        AgentJob recoveryRun = persistPullRequestReview(workspace, 23, recoveryRunAt);
        observe(describeWhatAndWhy, recoveryRun, 23L, developer, DEMONSTRATED_STRENGTH, null, recoveryRunAt);
        observe(reviewableDiffSize, recoveryRun, 23L, developer, DEMONSTRATED_STRENGTH, null, recoveryRunAt);

        readOverview()
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
    void shouldReportAResolutionWhenTheWorkResolvesFeedbackInsideTheWindow() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));

        readOverview()
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
                // The developer's own response on the other practice is reported beside it.
                .jsonPath("$.changes[?(@.resolvedBy == 'DEVELOPER')].feedbackId")
                .isEqualTo(olderFeedback.getId().toString());
    }

    /**
     * The resolution is dated by the third clean piece of work, so feedback whose third clean piece came before
     * the window opened resolved then: two clean pull requests before the seeded history, both reviewed after
     * the feedback was prepared, make #20 the third and date the resolution at the first run.
     */
    @Test
    @WithUser
    @DisplayName("feedback the work resolved before the window opened is not a change inside it")
    void shouldNotReportAResolutionWhenTheWorkResolvedItBeforeTheWindow() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(4)));
        for (int number = 17; number <= 18; number++) {
            Instant reviewedAt = FIRST_RUN_AT.minus(Duration.ofDays(20 - number));
            AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
            observe(describeWhatAndWhy, run, number, developer, DEMONSTRATED_STRENGTH, null, reviewedAt);
        }

        readOverview()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * The third clean pull request falls inside the window, but the feedback was prepared before the look-back
     * ({@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview).
     */
    @Test
    @WithUser
    @DisplayName("feedback prepared before the look-back is not resolved by the work inside the window")
    void shouldNotResolveFeedbackWhenItWasPreparedBeforeTheLookBack() {
        Feedback feedback =
                describingFeedbackPreparedAt(NOW.minus(Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS + 1)));

        readOverview()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * Three clean pull requests before the look-back resolved the feedback on its card, and the developer answers
     * it inside the window ({@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview).
     */
    @Test
    @WithUser
    @DisplayName("an answer inside the window to feedback prepared before the look-back is not a change")
    void shouldNotReportAnAnswerWhenTheFeedbackWasPreparedBeforeTheLookBack() {
        Instant preparedAt = NOW.minus(Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS + 10));
        Feedback feedback = describingFeedbackPreparedAt(preparedAt);
        for (int number = 16; number <= 18; number++) {
            Instant reviewedAt = preparedAt.plus(Duration.ofDays(number - 15));
            AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
            observe(describeWhatAndWhy, run, number, developer, DEMONSTRATED_STRENGTH, null, reviewedAt);
        }
        markAddressed(feedback, developer, LATEST_RUN_AT.plus(Duration.ofHours(2)));

        readOverview()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /** Feedback resolved before the look-back adds no query and no loaded entity to the overview's read. */
    @Test
    @WithUser
    @DisplayName("feedback resolved before the look-back adds nothing to what the overview reads")
    void shouldReadNoMoreWhenFeedbackWasResolvedBeforeTheLookBack() {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            readOverview();
            statistics.clear();
            readOverview();
            long queriesWithoutHistory = statistics.getQueryExecutionCount();
            long rowsWithoutHistory = statistics.getEntityLoadCount();

            Instant longAgo = NOW.minus(Duration.ofDays(PracticeStandingService.LOOKBACK_DAYS + 30));
            for (int number = 100; number < 103; number++) {
                Feedback resolved = describingFeedbackPreparedAt(number, longAgo.plusSeconds(number));
                markAddressed(
                        resolved, developer, longAgo.plus(Duration.ofDays(1)).plusSeconds(number));
            }

            statistics.clear();
            readOverview();
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
    void shouldReportOneResolutionByTheWorkWhenTheDeveloperAddressedItLater() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));
        markAddressed(feedback, developer, LATEST_RUN_AT.plus(Duration.ofHours(3)));

        String thisFeedback = "$.changes[?(@.feedbackId == '" + feedback.getId() + "')]";
        // One match, or the assertion refuses the list: the feedback resolved once.
        readOverview()
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
    void shouldNotReportAWorkResolutionWhenTheFeedbackWasAddressedBeforeTheWindow() {
        Feedback feedback = describingFeedbackPreparedAt(FIRST_RUN_AT.minus(Duration.ofDays(1)));
        markAddressed(feedback, developer, FIRST_RUN_AT.plus(Duration.ofHours(1)));

        readOverview()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * The work's resolution is read for every piece, not only those whose practice has work in the window:
     * without it, an answer inside the window would be the only resolution known and be reported, while
     * the card already says the work resolved the feedback before the window opened.
     */
    @Test
    @WithUser
    @DisplayName("an answer inside the window to feedback the work resolved before it is not a change")
    void shouldNotReportAnAnswerWhenTheWorkResolvedTheFeedbackBeforeTheWindow() {
        Practice checkableOutcome =
                persistPractice(workspace, null, "issue-has-checkable-outcome", "Define a checkable outcome", null);
        Instant preparedAt = FIRST_RUN_AT.minus(Duration.ofDays(4));
        AgentJob problemRun = persistPullRequestReview(workspace, 11, preparedAt);
        UUID problem = observe(checkableOutcome, problemRun, 11L, developer, OMISSION_GAP, Severity.MINOR, preparedAt);
        Feedback feedback = persistInAppFeedback(
                problemRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, preparedAt.plusSeconds(30));
        bind(feedback, problem);
        // Three clean pieces, all before the window; the practice has no work inside it.
        for (int number = 12; number <= 14; number++) {
            Instant reviewedAt = FIRST_RUN_AT.minus(Duration.ofDays(15 - number));
            AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
            observe(checkableOutcome, run, number, developer, DEMONSTRATED_STRENGTH, null, reviewedAt);
        }
        markAddressed(feedback, developer, LATEST_RUN_AT.plus(Duration.ofHours(2)));

        readOverview()
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
    void shouldDateNewFeedbackByItsRunWhenTheRowIsWrittenMomentsLater() {
        // The previous run found the description thin on #21 and spoke about it moments after its observation.
        // A different practice from the latest run's feedback, so a wrongly dated piece would survive the
        // per-practice dedupe and show up beside it rather than behind it.
        UUID previousRunsProblem =
                observe(describeWhatAndWhy, previousRun, 21L, developer, OMISSION_GAP, Severity.MINOR, PREVIOUS_RUN_AT);
        Feedback previousRunsFeedback = persistInAppFeedback(
                previousRun, developer, 2, FeedbackDeliveryState.DELIVERED, BODY, PREVIOUS_RUN_AT.plusSeconds(30));
        bind(previousRunsFeedback, previousRunsProblem);

        readOverview()
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].feedbackId")
                .isEqualTo(latestFeedback.getId().toString())
                .jsonPath("$.changes[?(@.type == 'FEEDBACK_NEW')].at")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.changes[?(@.feedbackId == '" + previousRunsFeedback.getId() + "')]")
                .isEmpty();
    }

    /**
     * Every read behind the overview is tenant-scoped: the runs, the standing snapshots, when a practice was
     * first observed, the feedback and the responses. The same developer's newer history in a second workspace
     * they belong to must change nothing here — not the window, not the work, not a change, not a strength.
     */
    @Test
    @WithUser
    @DisplayName("the same developer's history in another workspace does not reach this overview")
    void shouldIgnoreTheDevelopersHistoryWhenItIsInAnotherWorkspace() {
        User otherOwner = persistUser("profile-foreign-owner");
        Workspace other =
                createWorkspace("profile-foreign-ws", "Foreign WS", "profile-foreign-org", AccountType.ORG, otherOwner);
        ensureWorkspaceMembership(other, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        PracticeGroup otherGroup = new PracticeGroup();
        otherGroup.setWorkspace(other);
        otherGroup.setSlug("review-ready-work");
        otherGroup.setName("Packaging work for review");
        otherGroup = groupRepository.save(otherGroup);
        Practice otherPractice =
                persistPractice(other, otherGroup, "reviewable-diff-size", "Keep the diff reviewable", null);

        // Newer than every run seeded here, so a leak would move the window as well as add to it.
        Instant foreignRunAt = NOW.minus(Duration.ofHours(6));
        AgentJob foreignRun = persistPullRequestReview(other, 30, foreignRunAt);
        UUID foreignProblem =
                observe(otherPractice, foreignRun, 30L, developer, OMISSION_GAP, Severity.MAJOR, foreignRunAt);
        Feedback foreignFeedback = persistInAppFeedback(
                foreignRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, foreignRunAt.plusSeconds(30));
        bind(foreignFeedback, foreignProblem);
        markAddressed(foreignFeedback, developer, foreignRunAt.plusSeconds(60));

        readOverview()
                .jsonPath("$.window.since")
                .isEqualTo(PREVIOUS_RUN_AT.toString())
                .jsonPath("$.latestRun.jobId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.reviewedWork.length()")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork[0].label")
                .isEqualTo("#22")
                .jsonPath("$.changes.length()")
                .isEqualTo(4)
                .jsonPath("$.changes[?(@.feedbackId == '" + foreignFeedback.getId() + "')]")
                .isEmpty()
                .jsonPath("$.holdingUp.length()")
                .isEqualTo(1)
                .jsonPath("$.holdingUp[0].cleanWork")
                .isEqualTo(3);
    }

    /**
     * Every answer {@link FeedbackResolution#resolves} names closes a card, not only ADDRESSED: ruling one
     * out resolves it where the developer said so.
     */
    @Test
    @WithUser
    @DisplayName("feedback the developer ruled not applicable is resolved by them")
    void shouldResolveFeedbackWhenTheDeveloperRulesItNotApplicable() {
        Instant ruledOutAt = PREVIOUS_RUN_AT.plus(Duration.ofHours(2));
        Feedback ruledOut = describingFeedbackPreparedAt(23, FIRST_RUN_AT.minus(Duration.ofDays(2)));
        respond(ruledOut, developer, FeedbackResolution.NOT_APPLICABLE, ruledOutAt);

        String change = "$.changes[?(@.feedbackId == '" + ruledOut.getId() + "')]";
        readOverview()
                .jsonPath(change + ".type")
                .isEqualTo("FEEDBACK_RESOLVED")
                .jsonPath(change + ".resolvedBy")
                .isEqualTo("DEVELOPER")
                .jsonPath(change + ".at")
                .isEqualTo(ruledOutAt.toString());
    }

    /**
     * Feedback its practice's rule change closed was not resolved, and an answer the developer gives afterwards
     * does not make it so: the summary reports what the card reports.
     */
    @Test
    @WithUser
    @DisplayName("feedback its practice's change closed is not reported resolved by a later answer")
    void shouldNotReportAResolutionWhenThePracticeChangedBeforeTheDeveloperAnswered() {
        Instant preparedAt = FIRST_RUN_AT.minus(Duration.ofDays(2));
        AgentJob run = persistPullRequestReview(workspace, 50, preparedAt);
        UUID problem = observe(releaseNotes, run, 50L, developer, OMISSION_GAP, Severity.MINOR, preparedAt);
        Feedback feedback = persistInAppFeedback(
                run, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, preparedAt.plusSeconds(30));
        bind(feedback, problem);
        Instant rulesChangedAt = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Practice practice =
                    practiceRepository.findById(releaseNotes.getId()).orElseThrow();
            practice.setCriteria("A rewritten rubric, measuring something else");
            PracticeRevision changed = practiceRevisionRepository.save(new PracticeRevision(practice, 2));
            practice.setCurrentRevision(changed);
            practiceRepository.save(practice);
            return changed.getCreatedAt();
        }));
        markAddressed(feedback, developer, rulesChangedAt.plusMillis(1));

        readOverview()
                .jsonPath("$.changes[?(@.feedbackId == '" + feedback.getId() + "' && @.type == 'FEEDBACK_RESOLVED')]")
                .isEmpty();
    }

    /** A dispute asks for a reply: it closes nothing, so the card stays open for the work to resolve. */
    @Test
    @WithUser
    @DisplayName("feedback the developer disputed is not resolved by the dispute")
    void shouldNotResolveFeedbackWhenTheDeveloperDisputesIt() {
        Feedback disputed = describingFeedbackPreparedAt(24, FIRST_RUN_AT.minus(Duration.ofDays(3)));
        respond(disputed, developer, FeedbackResolution.DISPUTED, PREVIOUS_RUN_AT.plus(Duration.ofHours(3)));

        String change = "$.changes[?(@.feedbackId == '" + disputed.getId() + "')]";
        readOverview()
                .jsonPath(change + ".resolvedBy")
                .isEqualTo("WORK")
                .jsonPath(change + ".at")
                .isEqualTo(LATEST_RUN_AT.toString());
    }

    /**
     * Open feedback whose clean run had started and whose practice slipped again inside the window falls back:
     * two clean pull requests before the window put the run at two of three, and the problem on #22 puts it
     * back to nothing.
     */
    @Test
    @WithUser
    @DisplayName("feedback the work fell back on inside the window is reported with the work that reset it")
    void shouldReportAResetWhenTheWorkFallsBackInsideTheWindow() {
        Feedback feedback = releaseNotesFeedbackWithTwoCleanPieces();
        // The latest run found the practice missing again, inside the window.
        observe(releaseNotes, latestRun, 22L, developer, OMISSION_GAP, Severity.MAJOR, LATEST_RUN_AT);

        String change = "$.changes[?(@.type == 'FEEDBACK_RESET')]";
        readOverview()
                .jsonPath(change + ".feedbackId")
                .isEqualTo(feedback.getId().toString())
                .jsonPath(change + ".practiceSlug")
                .isEqualTo("release-notes")
                .jsonPath(change + ".groupSlug")
                .isEqualTo("review-ready-work")
                .jsonPath(change + ".cleanNeeded")
                .isEqualTo(3)
                .jsonPath(change + ".at")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath(change + ".evidence[*].label")
                .isEqualTo(List.of("#22"));
    }

    /**
     * The fall back has to happen inside the window: a clean run the work put back to nothing before the
     * window opened is where the feedback already stood when it opened, and standing still is no change.
     */
    @Test
    @WithUser
    @DisplayName("feedback the work fell back on before the window is not a change inside it")
    void shouldNotReportAResetWhenTheWorkFellBackBeforeTheWindow() {
        Feedback feedback = releaseNotesFeedbackWithTwoCleanPieces();
        // The problem is older than the previous run, so the window opens on a clean run already at nothing.
        Instant slipAt = FIRST_RUN_AT.minus(Duration.ofDays(1));
        AgentJob slipRun = persistPullRequestReview(workspace, 43, slipAt);
        observe(releaseNotes, slipRun, 43L, developer, OMISSION_GAP, Severity.MAJOR, slipAt);

        readOverview().jsonPath("$.changes[?(@.type == 'FEEDBACK_RESET')]").isEmpty();
    }

    /**
     * Delivered feedback about release notes, prepared before the seeded history and answered by two clean
     * pull requests before the window opens: a clean run at two of the three it needs.
     */
    private Feedback releaseNotesFeedbackWithTwoCleanPieces() {
        Instant preparedAt = FIRST_RUN_AT.minus(Duration.ofDays(6));
        AgentJob problemRun = persistPullRequestReview(workspace, 40, preparedAt);
        UUID problem = observe(releaseNotes, problemRun, 40L, developer, OMISSION_GAP, Severity.MINOR, preparedAt);
        Feedback feedback = persistInAppFeedback(
                problemRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, preparedAt.plusSeconds(30));
        bind(feedback, problem);
        for (int number = 41; number <= 42; number++) {
            Instant reviewedAt = FIRST_RUN_AT.minus(Duration.ofDays(45 - number));
            AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
            observe(releaseNotes, run, number, developer, DEMONSTRATED_STRENGTH, null, reviewedAt);
        }
        return feedback;
    }

    @Test
    @WithUser
    @DisplayName("a developer who is not a member of the workspace is refused")
    void shouldRefuseTheOverviewWhenTheDeveloperIsNotAMember() {
        User outsider = persistUser("outsider-owner");
        Workspace other =
                createWorkspace("profile-other-ws", "Other WS", "profile-other-org", AccountType.ORG, outsider);

        webTestClient
                .get()
                .uri(OVERVIEW_URI, other.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    /** The overview as the signed-in developer reads it. */
    private WebTestClient.BodyContentSpec readOverview() {
        return webTestClient
                .get()
                .uri(OVERVIEW_URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    /** Delivered feedback about descriptions, written from a slip on pull request #19 reviewed at {@code preparedAt}. */
    private Feedback describingFeedbackPreparedAt(Instant preparedAt) {
        return describingFeedbackPreparedAt(19, preparedAt);
    }

    private Feedback describingFeedbackPreparedAt(int number, Instant preparedAt) {
        AgentJob run = persistPullRequestReview(workspace, number, preparedAt);
        UUID problem = observe(describeWhatAndWhy, run, number, developer, OMISSION_GAP, Severity.MINOR, preparedAt);
        Feedback feedback = persistInAppFeedback(
                run, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, preparedAt.plusSeconds(30));
        bind(feedback, problem);
        return feedback;
    }
}
