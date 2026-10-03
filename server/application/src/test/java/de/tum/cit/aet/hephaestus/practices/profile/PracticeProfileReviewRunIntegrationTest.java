package de.tum.cit.aet.hephaestus.practices.profile;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.MET;
import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_APPLICABLE;
import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettings;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /practice-profile/review-runs} and its detail against a seeded history: three runs on the
 * signed-in developer's work and one run over a pull request they share with somebody else. Every assertion
 * is on rows this class wrote.
 */
class PracticeProfileReviewRunIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String RUNS_URI = "/workspaces/{workspaceSlug}/practice-profile/review-runs";
    private static final String RUN_URI = RUNS_URI + "/{reviewId}";

    private static final Instant OLDEST_RUN_AT = NOW.minus(Duration.ofDays(9));
    private static final Instant MIDDLE_RUN_AT = NOW.minus(Duration.ofDays(4));
    private static final Instant LATEST_RUN_AT = NOW.minus(Duration.ofDays(1));

    /** An id no mirrored pull request takes, so the work stays unmirrored whatever ids the sequence hands out. */
    private static long unmirrored(int number) {
        return 1_000_000_000L + number;
    }

    private static final String BODY =
            InAppFeedbackBody.render("A way of working", "What recurs.", "One thing to try.");
    /** Evidence that cites nothing, which the evidence authorization withholds from every reader. */
    private static final String WITHHELD_EVIDENCE_JSON = "{\"citations\":[]}";

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private WorkspaceMembershipRepository workspaceMemberships;

    @Autowired
    private ObservationInvalidationRepository invalidationRepository;

    @Autowired
    private FeedbackWithdrawalRepository withdrawalRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;

    @Autowired
    private IdentityLinkRepository identityLinks;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Workspace workspace;
    private User developer;
    private User colleague;
    private Practice explainChanges;
    private Practice reviewableDiffSize;
    private AgentJob oldestRun;
    private AgentJob middleRun;
    private AgentJob latestRun;
    private AgentJob colleaguesRun;
    private UUID colleaguesStrength;

    @BeforeEach
    void seedRuns() {
        User owner = persistUser("profile-runs-owner");
        workspace = createWorkspace("profile-runs-ws", "Profile Runs WS", "profile-runs-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        // "mentor" is the only identity other than testuser the mock JWT decoder resolves, so a case
        // that must read this page as somebody else has to be seeded as them.
        colleague = persistUser("mentor");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, colleague, WorkspaceMembership.WorkspaceRole.MEMBER);

        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug("review-ready-work");
        group.setName("Packaging work for review");
        group = groupRepository.save(group);

        explainChanges = persistPractice(workspace, group, "explain-changes", "Explain each change", null);
        reviewableDiffSize =
                persistPractice(workspace, group, "reviewable-diff-size", "Keep the diff reviewable", null);

        oldestRun = persistPullRequestReview(workspace, 30, unmirrored(30), OLDEST_RUN_AT);
        middleRun = persistPullRequestReview(workspace, 31, unmirrored(31), MIDDLE_RUN_AT);
        latestRun = persistPullRequestReview(workspace, 32, unmirrored(32), LATEST_RUN_AT);
        latestRun.setStatus(AgentJobStatus.COMPLETED);
        latestRun.setStartedAt(LATEST_RUN_AT.minusSeconds(41));
        latestRun = agentJobRepository.save(latestRun);
        // A run over a pull request the two of them wrote together: it observed both of them.
        colleaguesRun = persistPullRequestReview(workspace, 33, MIDDLE_RUN_AT.plusSeconds(60));

        observe(explainChanges, oldestRun, unmirrored(30), developer, MET, null, OLDEST_RUN_AT);
        observe(reviewableDiffSize, middleRun, unmirrored(31), developer, NOT_APPLICABLE, null, MIDDLE_RUN_AT);
        observe(explainChanges, latestRun, unmirrored(32), developer, MET, null, LATEST_RUN_AT);
        UUID latestProblem = observe(
                reviewableDiffSize, latestRun, unmirrored(32), developer, NOT_MET, Severity.MAJOR, LATEST_RUN_AT);

        observe(explainChanges, colleaguesRun, 33L, developer, MET, null, MIDDLE_RUN_AT.plusSeconds(60));
        // The colleague's two strengths on the same run must not be counted on this developer's page.
        colleaguesStrength =
                observe(explainChanges, colleaguesRun, 33L, colleague, MET, null, MIDDLE_RUN_AT.plusSeconds(60));
        observe(reviewableDiffSize, colleaguesRun, 33L, colleague, MET, null, MIDDLE_RUN_AT.plusSeconds(60));

        Feedback delivered = persistInAppFeedback(
                latestRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, LATEST_RUN_AT.plusSeconds(30));
        bind(delivered, latestProblem);
        // The colleague heard about their own work from the shared run; that is not a number about this reader.
        Feedback theirs = persistInAppFeedback(
                colleaguesRun, colleague, 1, FeedbackDeliveryState.DELIVERED, BODY, MIDDLE_RUN_AT.plusSeconds(90));
        bind(theirs, colleaguesStrength);
    }

    /** A run writes its observations partway through, so neither its start nor its end dates it. */
    @Test
    @WithUser
    @DisplayName("a run is dated by its newest observation about the developer, listed or opened")
    void shouldDateARunByItsNewestObservationWhenItIsListedOrOpened() {
        Instant finishedObservedAt = LATEST_RUN_AT.plusSeconds(600);
        AgentJob finished = persistPullRequestReview(workspace, 50, LATEST_RUN_AT.plusSeconds(900));
        finished.setStatus(AgentJobStatus.COMPLETED);
        finished.setStartedAt(LATEST_RUN_AT.plusSeconds(300));
        agentJobRepository.save(finished);
        observe(explainChanges, finished, 50L, developer, MET, null, finishedObservedAt);

        // Started before the other run finished and observed after it: newer by the date the rows show.
        Instant runningObservedAt = LATEST_RUN_AT.plusSeconds(700);
        AgentJob running = persistPullRequestReview(workspace, 51, null);
        running.setStatus(AgentJobStatus.RUNNING);
        running.setStartedAt(LATEST_RUN_AT.plusSeconds(400));
        agentJobRepository.save(running);
        observe(explainChanges, running, 51L, developer, MET, null, runningObservedAt);

        readRuns()
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(running.getId().toString())
                .jsonPath("$.content[0].reviewedAt")
                .isEqualTo(runningObservedAt.toString())
                .jsonPath("$.content[0].status")
                .isEqualTo("IN_PROGRESS")
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(finished.getId().toString())
                .jsonPath("$.content[1].reviewedAt")
                .isEqualTo(finishedObservedAt.toString());
        readRun(finished.getId()).jsonPath("$.run.reviewedAt").isEqualTo(finishedObservedAt.toString());
    }

    @Test
    @WithUser
    @DisplayName(
            "every run on the developer's own work, newest first, counting and naming only what it said about them")
    void shouldListTheDevelopersOwnRunsNewestFirstWhenTheyReadTheirProfile() {
        readRuns()
                .jsonPath("$.content.length()")
                .isEqualTo(4)
                .jsonPath("$.hasNext")
                .isEqualTo(false)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.content[0].reviewedAt")
                .isEqualTo(LATEST_RUN_AT.toString())
                .jsonPath("$.content[0].reviewedWork.label")
                .isEqualTo("#32")
                .jsonPath("$.content[0].reviewedWork.container")
                .isEqualTo("acme/api")
                .jsonPath("$.content[0].status")
                .isEqualTo("COMPLETED")
                .jsonPath("$.content[0].triggerMode")
                .isEqualTo("MANUAL")
                .jsonPath("$.content[0].practices.met")
                .isEqualTo(1)
                .jsonPath("$.content[0].practices.notMet")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedbackDelivered")
                .isEqualTo(1)
                .jsonPath("$.content[0].slippedPractices[*].practiceName")
                .isEqualTo(List.of("Keep the diff reviewable"))
                // The shared run: one strength about this reader, none of the colleague's, and no feedback
                // of theirs counted here.
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(colleaguesRun.getId().toString())
                .jsonPath("$.content[1].practices.met")
                .isEqualTo(1)
                .jsonPath("$.content[1].practices.notMet")
                .isEqualTo(0)
                .jsonPath("$.content[1].feedbackDelivered")
                .isEqualTo(0)
                .jsonPath("$.content[1].slippedPractices.length()")
                .isEqualTo(0)
                // A run that found nothing to judge still ran, and still appears.
                .jsonPath("$.content[2].reviewId")
                .isEqualTo(middleRun.getId().toString())
                .jsonPath("$.content[2].practices.notApplicable")
                .isEqualTo(1)
                .jsonPath("$.content[3].reviewId")
                .isEqualTo(oldestRun.getId().toString());
    }

    @Test
    @WithUser
    @DisplayName("a run links to its summary comment at the address the provider returned for it")
    void shouldLinkTheFeedbackToTheRecordedAddressWhenTheRunPostedASummary() {
        recordSummary(latestRun, "https://github.com/acme/api/pull/32#issuecomment-4711");

        readRuns()
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.content[0].feedbackUrl")
                .isEqualTo("https://github.com/acme/api/pull/32#issuecomment-4711")
                .jsonPath("$.content[1].feedbackUrl")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("a recorded address that is not a comment on the reviewed work is not linked")
    void shouldCarryNoFeedbackAddressWhenTheRecordedAddressIsNotOnTheWork() {
        recordSummary(latestRun, "https://github.com/acme/api/pull/33#issuecomment-4711");

        readRuns().jsonPath("$.content[0].feedbackUrl").doesNotExist();
    }

    /** The run's delivered summary, as the dispatch records the comment the provider created. */
    private void recordSummary(AgentJob run, String url) {
        String commentId = "IC_" + run.getId();
        run.setIntegrationKind(IntegrationKind.GITHUB);
        run.setDeliveryCommentId(commentId);
        agentJobRepository.save(run);
        jdbcTemplate.update(
                """
                INSERT INTO feedback_dispatch (id, destination_key, workspace_id, agent_job_id, destination, state, body,
                    practice_slugs, package_content, delivered_placements, write_started, delivered_external_ref,
                    delivered_external_url, next_attempt_at, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AUTOMATIC_REVIEW_PACKAGE', 'SENT', 'body', '[]'::jsonb,
                    '{"mrNote":"body","diffNotes":[],"withheld":[]}'::jsonb, '[]'::jsonb, true, ?, ?, now(), 1, now(),
                    now())
                """, UUID.randomUUID(), "summary-" + run.getId(), workspace.getId(), run.getId(), commentId, url);
    }

    @Test
    @WithUser
    @DisplayName("a page says whether an earlier one follows it")
    void shouldSayAnotherPageFollowsWhenEarlierRunsRemain() {
        readRuns(0, 2, null)
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                .jsonPath("$.hasNext")
                .isEqualTo(true)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());

        readRuns(1, 2, null)
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                .jsonPath("$.hasNext")
                .isEqualTo(false)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(middleRun.getId().toString())
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(oldestRun.getId().toString());
    }

    @Test
    @WithUser
    @DisplayName("one run reads back with the observations it made about this developer and no others")
    void shouldServeOnlyTheReadersOwnObservationsWhenOneRunIsOpened() {
        readRun(colleaguesRun.getId())
                .jsonPath("$.run.reviewId")
                .isEqualTo(colleaguesRun.getId().toString())
                .jsonPath("$.run.reviewedWork.label")
                .isEqualTo("#33")
                .jsonPath("$.observations.length()")
                .isEqualTo(1)
                .jsonPath("$.observations[0].practiceSlug")
                .isEqualTo("explain-changes");
    }

    /**
     * The header counts and the list below it come from the same rows: an observation whose evidence the
     * authorization withholds is in neither. Citing nothing is what makes an observation withheld.
     */
    @Test
    @WithUser
    @DisplayName("a run counts only the observations it lists when one of them is withheld")
    void shouldCountOnlyTheObservationsItListsWhenOneIsWithheld() {
        observe(
                reviewableDiffSize,
                colleaguesRun,
                33L,
                developer,
                NOT_MET,
                Severity.MAJOR,
                MIDDLE_RUN_AT.plusSeconds(60),
                WITHHELD_EVIDENCE_JSON);

        readRun(colleaguesRun.getId())
                .jsonPath("$.run.practices.met")
                .isEqualTo(1)
                .jsonPath("$.run.practices.notMet")
                .isEqualTo(0)
                .jsonPath("$.observations.length()")
                .isEqualTo(1)
                .jsonPath("$.observations[0].practiceSlug")
                .isEqualTo("explain-changes");
        readRuns()
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(colleaguesRun.getId().toString())
                .jsonPath("$.content[1].practices.notMet")
                .isEqualTo(0);
    }

    @Test
    @WithUser
    @DisplayName("a run whose every observation about the developer is withheld is not on their list")
    void shouldLeaveOutARunWhenEverythingItObservedAboutTheReaderIsWithheld() {
        AgentJob withheld = persistPullRequestReview(workspace, 35, LATEST_RUN_AT.plusSeconds(600));
        observe(
                explainChanges,
                withheld,
                35L,
                developer,
                NOT_MET,
                Severity.MAJOR,
                LATEST_RUN_AT.plusSeconds(600),
                WITHHELD_EVIDENCE_JSON);

        readRuns()
                .jsonPath("$.content.length()")
                .isEqualTo(4)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());
        expectRunNotFound(withheld.getId());
    }

    @Test
    @WithUser
    @DisplayName("a run that observed nothing about this developer is not theirs to read")
    void shouldAnswerNotFoundWhenTheRunObservedNothingAboutTheReader() {
        AgentJob somebodyElses = persistPullRequestReview(workspace, 34, MIDDLE_RUN_AT);
        observe(explainChanges, somebodyElses, 34L, colleague, MET, null, MIDDLE_RUN_AT);

        expectRunNotFound(somebodyElses.getId());
    }

    @Test
    @WithUser
    @DisplayName("a run in another workspace is not found through this one")
    void shouldAnswerNotFoundWhenTheRunBelongsToAnotherWorkspace() {
        User otherOwner = persistUser("profile-runs-other-owner");
        Workspace other = createWorkspace(
                "profile-runs-other", "Other WS", "profile-runs-other-org", AccountType.ORG, otherOwner);
        ensureWorkspaceMembership(other, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        Practice elsewhere = persistPractice(other, null, "explain-changes", "Explain each change", null);
        AgentJob otherRun = persistPullRequestReview(other, 60, LATEST_RUN_AT);
        observe(elsewhere, otherRun, 60L, developer, MET, null, LATEST_RUN_AT);

        expectRunNotFound(otherRun.getId());
    }

    @Test
    @WithUser
    @DisplayName("a run that only observed work in a hidden repository is neither listed nor readable")
    void shouldAnswerNotFoundWhenTheRunOnlyObservedWorkInAHiddenRepository() {
        long pullRequestId = persistPullRequest(persistHiddenRepository(), developer, 81);
        AgentJob hiddenRun = persistPullRequestReview(workspace, 81, pullRequestId, LATEST_RUN_AT.plusSeconds(700));
        observe(explainChanges, hiddenRun, pullRequestId, developer, MET, null, LATEST_RUN_AT.plusSeconds(700));

        readRuns().jsonPath("$.content[0].reviewId").isEqualTo(latestRun.getId().toString());
        expectRunNotFound(hiddenRun.getId());
    }

    @Test
    @WithUser
    @DisplayName("an invalidated observation is listed in its run but neither counted nor named as slipped")
    void shouldLeaveAnInvalidatedObservationOutOfTheCountsWhenAnAdminInvalidatedIt() {
        UUID wrong = observe(
                explainChanges,
                latestRun,
                unmirrored(32),
                developer,
                NOT_MET,
                Severity.MAJOR,
                LATEST_RUN_AT.minusSeconds(1));
        invalidationRepository.save(new ObservationInvalidation(
                observationRepository
                        .findByIdAndWorkspaceId(wrong, workspace.getId())
                        .orElseThrow(),
                1L,
                "Wrong when made",
                NOW));

        readRun(latestRun.getId())
                .jsonPath("$.run.practices.notMet")
                .isEqualTo(1)
                .jsonPath("$.run.practices.met")
                .isEqualTo(1)
                .jsonPath("$.run.slippedPractices.length()")
                .isEqualTo(1)
                .jsonPath("$.run.slippedPractices[0].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.observations[?(@.id=='" + wrong + "')].invalidationReason")
                .isEqualTo("Wrong when made");
    }

    @Test
    @WithUser
    @DisplayName("a run counts each practice once, and a practice with any problem as one to improve only")
    void shouldCountEachPracticeOnceWhenARunObservedItSeveralTimes() {
        AgentJob run = persistPullRequestReview(workspace, 37, LATEST_RUN_AT.plusSeconds(60));
        Instant at = LATEST_RUN_AT.plusSeconds(60);
        observe(explainChanges, run, 37L, developer, MET, null, at);
        observe(explainChanges, run, 37L, developer, MET, null, at);
        observe(reviewableDiffSize, run, 37L, developer, MET, null, at);
        observe(reviewableDiffSize, run, 37L, developer, NOT_MET, Severity.MAJOR, at);

        readRun(run.getId())
                .jsonPath("$.run.practices.met")
                .isEqualTo(1)
                .jsonPath("$.run.practices.notMet")
                .isEqualTo(1)
                .jsonPath("$.run.practices.notApplicable")
                .isEqualTo(0)
                .jsonPath("$.run.practices.undetermined")
                .isEqualTo(0)
                .jsonPath("$.run.slippedPractices[*].practiceSlug")
                .isEqualTo(List.of("reviewable-diff-size"));
    }

    @Test
    @WithUser
    @DisplayName("a run counts an earlier-standard verdict as undecided and a changed practice's verdict as measured")
    void shouldCountAnEarlierStandardVerdictAsUndecidedWhenItsRevisionPredatesTheCurrentScheme() {
        Instant at = LATEST_RUN_AT.plusSeconds(60);
        AgentJob run = persistPullRequestReview(workspace, 38, at);
        // A MET verdict whose practice revision carries an earlier fingerprint scheme.
        UUID migratedMet = observeUnder(
                historicalRevision(explainChanges, 2, "v4:" + "a".repeat(64)),
                explainChanges,
                run,
                38L,
                developer,
                MET,
                null,
                at);
        UUID unfingerprinted = observeUnder(
                historicalRevision(reviewableDiffSize, 2, null),
                reviewableDiffSize,
                run,
                38L,
                developer,
                NOT_MET,
                Severity.MAJOR,
                at);
        Practice earlierNotApplicable = persistPractice(workspace, null, "earlier-not-applicable", "No occasion", null);
        observeUnder(
                historicalRevision(earlierNotApplicable, 2, "v4:" + "b".repeat(64)),
                earlierNotApplicable,
                run,
                38L,
                developer,
                NOT_APPLICABLE,
                null,
                at);
        Practice changedSince = persistPractice(workspace, null, "changed-since", "Changed since", null);
        observe(changedSince, run, 38L, developer, NOT_MET, Severity.MINOR, at);
        changedSince.setCriteria("Criteria the workspace changed after this review");
        changedSince.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(changedSince, 2)));
        practiceRepository.saveAndFlush(changedSince);

        readRun(run.getId())
                .jsonPath("$.run.practices.met")
                .isEqualTo(0)
                .jsonPath("$.run.practices.notMet")
                .isEqualTo(1)
                .jsonPath("$.run.practices.notApplicable")
                .isEqualTo(1)
                .jsonPath("$.run.practices.undetermined")
                .isEqualTo(2)
                .jsonPath("$.run.slippedPractices[*].practiceSlug")
                .isEqualTo(List.of("changed-since"))
                .jsonPath("$.observations.length()")
                .isEqualTo(4)
                .jsonPath("$.observations[?(@.id=='" + migratedMet + "')].outcome")
                .isEqualTo("MET")
                .jsonPath("$.observations[?(@.id=='" + migratedMet + "')].claimCurrentness")
                .isEqualTo("UNVERIFIABLE")
                .jsonPath("$.observations[?(@.id=='" + unfingerprinted + "')].outcome")
                .isEqualTo("NOT_MET");
        readRuns()
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(run.getId().toString())
                .jsonPath("$.content[0].practices.undetermined")
                .isEqualTo(2)
                .jsonPath("$.content[0].slippedPractices[*].practiceSlug")
                .isEqualTo(List.of("changed-since"));
    }

    /** A copy of the practice's current revision recorded under {@code fingerprint}, as a released build wrote it. */
    private long historicalRevision(Practice practice, int revisionNumber, @Nullable String fingerprint) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                """
                INSERT INTO practice_revision (
                    practice_id, revision_number, slug, name, applies_to, signals, evidence_requirements, review_when,
                    subject, precondition, criteria, precompute_script, automated_review_policy, delivery_behavior,
                    why_it_matters, what_good_looks_like, group_slug, review_rule_fingerprint, created_at
                )
                SELECT practice_id, ?, slug, name, applies_to, signals, evidence_requirements, review_when,
                       subject, precondition, criteria, precompute_script, automated_review_policy, delivery_behavior,
                       why_it_matters, what_good_looks_like, group_slug, CAST(? AS varchar), created_at
                FROM practice_revision WHERE id = ?
                RETURNING id
                """,
                Long.class,
                revisionNumber,
                fingerprint,
                practice.getCurrentRevision().getId()));
    }

    @Test
    @WithUser
    @DisplayName("withdrawn feedback is not counted as feedback that reached the developer")
    void shouldNotCountWithdrawnFeedbackWhenAnAdminWithdrewIt() {
        Feedback withdrawn = persistInAppFeedback(
                latestRun, developer, 2, FeedbackDeliveryState.DELIVERED, BODY, LATEST_RUN_AT.plusSeconds(40));
        withdrawalRepository.save(new FeedbackWithdrawal(withdrawn, 1L, "Wrong words", NOW));

        readRuns()
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString())
                .jsonPath("$.content[0].feedbackDelivered")
                .isEqualTo(1);
    }

    @Test
    @WithUser
    @DisplayName(
            "a run names every practice it recorded a problem about, however many, in name order whatever the case")
    void shouldNameEverySlippedPracticeInNameOrderWhenARunRecordedSeveral() {
        AgentJob run = persistPullRequestReview(workspace, 36, LATEST_RUN_AT.plusSeconds(60));
        observe(explainChanges, run, 36L, developer, NOT_MET, Severity.MAJOR, LATEST_RUN_AT.plusSeconds(60));
        observe(reviewableDiffSize, run, 36L, developer, NOT_MET, Severity.MAJOR, LATEST_RUN_AT.plusSeconds(60));
        for (String slug : List.of("third-practice", "fourth-practice")) {
            Practice practice = persistPractice(workspace, null, slug, slug, null);
            observe(practice, run, 36L, developer, NOT_MET, Severity.MAJOR, LATEST_RUN_AT.plusSeconds(60));
        }

        readRun(run.getId())
                .jsonPath("$.run.slippedPractices[*].practiceSlug")
                .isEqualTo(List.of("explain-changes", "fourth-practice", "reviewable-diff-size", "third-practice"));
    }

    @Test
    @WithUser
    @DisplayName("the list narrows to one kind of work when the reader asks for one")
    void shouldListOnlyThatKindWhenTheReaderFiltersByKindOfWork() {
        AgentJob issueRun = persistIssueReview(workspace, 40, LATEST_RUN_AT.plusSeconds(120));
        observe(
                explainChanges,
                issueRun,
                ArtifactKinds.ISSUE.value(),
                40L,
                developer,
                null,
                MET,
                null,
                LATEST_RUN_AT.plusSeconds(120),
                DIFF_EVIDENCE_JSON,
                null);

        readRuns(null, null, ArtifactKinds.ISSUE.value())
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(issueRun.getId().toString())
                .jsonPath("$.content[0].reviewedWork.label")
                .isEqualTo("#40")
                .jsonPath("$.hasNext")
                .isEqualTo(false);

        // The pull-request runs are still all there under their own kind, the issue run excluded.
        readRuns(null, null, ArtifactKinds.PULL_REQUEST.value())
                .jsonPath("$.content.length()")
                .isEqualTo(4)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());
    }

    @Test
    @WithUser
    @DisplayName("the list narrows to the runs since one moment when the reader picks a timeframe")
    void shouldListOnlyRunsSinceThatMomentWhenTheReaderPicksATimeframe() {
        // A week back leaves the three recent runs and drops the one nine days old.
        readRuns(null, null, null, NOW.minus(Duration.ofDays(7)))
                .jsonPath("$.content.length()")
                .isEqualTo(3)
                .jsonPath("$.hasNext")
                .isEqualTo(false)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());

        // Narrower still leaves only the latest; the bound is exclusive, as the query documents.
        readRuns(null, null, null, LATEST_RUN_AT.minusSeconds(1))
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());
    }

    /** A run names its work by what the mirror calls it now, not by the snapshot the run took. */
    @Nested
    @DisplayName("The name the reviewed work is listed under")
    class CurrentTitle {

        private AgentJob runOnMirroredWork;
        private Repository repository;

        @BeforeEach
        void seedRenamedWork() {
            repository = persistMonitoredRepository();
            long pullRequestId = persistPullRequest(repository, developer, 78);
            // The run recorded "Pull request 78"; the mirror now holds the name the author gave it.
            runOnMirroredWork = persistPullRequestReview(workspace, 78, pullRequestId, LATEST_RUN_AT.plusSeconds(600));
            observe(
                    explainChanges,
                    runOnMirroredWork,
                    pullRequestId,
                    developer,
                    MET,
                    null,
                    LATEST_RUN_AT.plusSeconds(600));
        }

        @Test
        @WithUser
        @DisplayName("a pull request renamed after its review is listed under the name it has now")
        void shouldNameTheWorkByItsCurrentTitleWhenItWasRenamedAfterTheReview() {
            readRuns()
                    .jsonPath("$.content[?(@.reviewId=='" + runOnMirroredWork.getId() + "')].reviewedWork.title")
                    .isEqualTo("A change worth reviewing");
            readRun(runOnMirroredWork.getId())
                    .jsonPath("$.run.reviewedWork.title")
                    .isEqualTo("A change worth reviewing");
        }

        @Test
        @WithUser
        @DisplayName("work in a repository monitored twice is still named once")
        void shouldNameTheWorkWhenTwoMonitorsShareItsRepositoryName() {
            monitor(repository);

            readRun(runOnMirroredWork.getId())
                    .jsonPath("$.run.reviewedWork.title")
                    .isEqualTo("A change worth reviewing");
        }

        @Test
        @WithUser
        @DisplayName("work this workspace no longer mirrors keeps the name the run recorded")
        void shouldKeepTheRecordedTitleWhenTheWorkIsNoLongerOnRecord() {
            readRuns()
                    .jsonPath("$.content[?(@.reviewId=='" + latestRun.getId() + "')].reviewedWork.title")
                    .isEqualTo("Pull request 32");
        }
    }

    /** The same answer {@code ReviewRequestAuthority} gives the request endpoint. */
    @Nested
    @DisplayName("Whether the reader may ask for another review")
    class MayRequest {

        private long sharedPullRequestId;
        private AgentJob sharedRun;

        @BeforeEach
        void seedRealWork() {
            Repository repository = persistMonitoredRepository();
            sharedPullRequestId = persistPullRequest(repository, developer, 77);
            sharedRun = persistPullRequestReview(workspace, 77, LATEST_RUN_AT.plusSeconds(300));
            // One run over work the developer wrote and the colleague was observed on, so both read it.
            observe(
                    explainChanges,
                    sharedRun,
                    sharedPullRequestId,
                    developer,
                    MET,
                    null,
                    LATEST_RUN_AT.plusSeconds(300));
            observe(
                    explainChanges,
                    sharedRun,
                    sharedPullRequestId,
                    colleague,
                    MET,
                    null,
                    LATEST_RUN_AT.plusSeconds(300));
        }

        @Test
        @WithUser
        @DisplayName("the author of the work may ask for another review of it")
        void shouldSayTheAuthorMayAskWhenTheyWroteTheWork() {
            assertMayRequest(sharedRun.getId(), 0, true);
        }

        @Test
        @WithMentorUser
        @DisplayName("a member who neither wrote the work nor administers the workspace may not")
        void shouldSayAMemberMayNotAskWhenTheWorkIsSomebodyElses() {
            assertMayRequest(sharedRun.getId(), 0, false);
        }

        @Test
        @WithMentorUser
        @DisplayName("a workspace admin may ask about work that is not theirs")
        void shouldSayAnAdminMayAskWhenTheWorkIsSomebodyElses() {
            makeColleagueAnAdmin();

            assertMayRequest(sharedRun.getId(), 0, true);
        }

        @Test
        @WithMentorUser
        @DisplayName("work this workspace no longer mirrors cannot be asked about, even by an admin")
        void shouldSayAnAdminMayNotAskWhenTheWorkIsNotInTheMirror() {
            makeColleagueAnAdmin();
            long unmirroredId = 790_079L;
            AgentJob unmirrored = persistPullRequestReview(workspace, 79, unmirroredId, LATEST_RUN_AT.plusSeconds(400));
            observe(explainChanges, unmirrored, unmirroredId, colleague, MET, null, LATEST_RUN_AT.plusSeconds(400));

            assertMayRequest(unmirrored.getId(), 0, false);
        }

        @Test
        @WithUser
        @DisplayName("work this workspace no longer mirrors cannot be asked about, even by its author")
        void shouldSayNobodyMayAskWhenTheWorkIsNotInTheMirror() {
            // The seeded runs above carry artifact ids with no pull request behind them.
            assertMayRequest(latestRun.getId(), 1, false);
        }

        /**
         * The repository is in the mirror, but only another workspace monitors it: its current title and its
         * standing belong to that workspace, never to this one.
         */
        @Test
        @WithMentorUser
        @DisplayName(
                "work only another workspace monitors keeps the recorded name and offers no request, even to an admin")
        void shouldKeepTheRecordedTitleAndOfferNoRequestWhenOnlyAnotherWorkspaceMonitorsTheWork() {
            makeColleagueAnAdmin();
            User otherOwner = persistUser("profile-runs-monitor-owner");
            Workspace other = createWorkspace(
                    "profile-runs-monitor", "Monitor WS", "profile-runs-monitor-org", AccountType.ORG, otherOwner);
            Repository elsewhere = persistRepository(9103L, "elsewhere");
            monitor(other, elsewhere);
            long pullRequestId = persistPullRequest(elsewhere, colleague, 82);
            Instant at = LATEST_RUN_AT.plusSeconds(800);
            AgentJob run = persistPullRequestReview(workspace, 82, pullRequestId, at);
            observe(explainChanges, run, pullRequestId, colleague, MET, null, at);

            String listed = "$.content[?(@.reviewId=='" + run.getId() + "')]";
            readRuns()
                    .jsonPath(listed + ".reviewedWork.title")
                    .isEqualTo("Pull request 82")
                    .jsonPath(listed + ".mayRequest")
                    .isEqualTo(false);
            readRun(run.getId())
                    .jsonPath("$.run.reviewedWork.title")
                    .isEqualTo("Pull request 82")
                    .jsonPath("$.run.mayRequest")
                    .isEqualTo(false);
        }

        /**
         * A user view resolves the administrator's own identities, which would answer for their standing, not the
         * developer's: here that is a workspace admin's, who could ask about the developer's work.
         */
        @Test
        @DisplayName("an administrator viewing as the developer is offered no request, even as a workspace admin")
        void shouldSayNobodyMayAskWhenAnAdministratorViewsAsTheDeveloper() {
            User adminIdentity = persistUser("profile-runs-viewing-admin");
            ensureWorkspaceMembership(workspace, adminIdentity, WorkspaceRole.ADMIN);
            Account administrator = persistInstanceAdmin("Viewing administrator");
            IdentityLink link = new IdentityLink();
            link.setAccount(administrator);
            link.setProviderId(Objects.requireNonNull(ensureGitHubProvider().getId()));
            link.setSubject(String.valueOf(adminIdentity.getNativeId()));
            link.setExternalActorId(adminIdentity.getId());
            identityLinks.save(link);

            readAsViewingAdministrator(RUNS_URI, administrator)
                    .jsonPath("$.content[0].reviewId")
                    .isEqualTo(sharedRun.getId().toString())
                    .jsonPath("$.content[0].mayRequest")
                    .isEqualTo(false);
            readAsViewingAdministrator(RUNS_URI + "/" + sharedRun.getId(), administrator)
                    .jsonPath("$.run.mayRequest")
                    .isEqualTo(false);
        }

        private WebTestClient.BodyContentSpec readAsViewingAdministrator(String uri, Account administrator) {
            return webTestClient
                    .get()
                    .uri(uri, workspace.getWorkspaceSlug())
                    .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + administrator.getId()))
                    .header(UserViewContextHolder.WORKSPACE_HEADER, workspace.getWorkspaceSlug())
                    .header(UserViewContextHolder.USER_HEADER, String.valueOf(developer.getId()))
                    .header(UserViewContextHolder.REASON_HEADER, "Check the review runs")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody();
        }

        private void makeColleagueAnAdmin() {
            WorkspaceMembership membership = workspaceMemberships
                    .findByWorkspace_IdAndUser_Id(workspace.getId(), colleague.getId())
                    .orElseThrow();
            membership.setRole(WorkspaceRole.ADMIN);
            workspaceMemberships.save(membership);
        }

        /** The same answer read off the list and off the run's own detail, which must not disagree. */
        private void assertMayRequest(UUID reviewId, int listIndex, boolean expected) {
            readRuns()
                    .jsonPath("$.content[" + listIndex + "].reviewId")
                    .isEqualTo(reviewId.toString())
                    .jsonPath("$.content[" + listIndex + "].mayRequest")
                    .isEqualTo(expected);
            readRun(reviewId).jsonPath("$.run.mayRequest").isEqualTo(expected);
        }
    }

    private Repository persistMonitoredRepository() {
        Repository repository = persistRepository(9101L, "api");
        monitor(repository);
        return repository;
    }

    /** A repository a team setting hides from contributions, which every developer surface leaves out. */
    private Repository persistHiddenRepository() {
        Repository repository = persistRepository(9102L, "private");
        Team team = new Team();
        team.setNativeId(9102L);
        team.setProvider(repository.getProvider());
        team.setName("Private work");
        team.setSlug("private-work");
        team.setPrivacy(Team.Privacy.VISIBLE);
        WorkspaceTeamRepositorySettings settings =
                new WorkspaceTeamRepositorySettings(workspace, teamRepository.save(team), repository);
        settings.setHiddenFromContributions(true);
        teamRepositorySettings.save(settings);
        return repository;
    }

    private Repository persistRepository(long nativeId, String name) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitHubProvider());
        repository.setName(name);
        repository.setNameWithOwner("acme/" + name);
        repository.setHtmlUrl("https://github.com/acme/" + name);
        repository.setDefaultBranch("main");
        return repositoryRepository.save(repository);
    }

    private void monitor(Repository repository) {
        monitor(workspace, repository);
    }

    private void monitor(Workspace monitoring, Repository repository) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(monitoring);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        repositoryToMonitorRepository.save(monitor);
    }

    private long persistPullRequest(Repository repository, User prAuthor, int number) {
        Instant now = Instant.now();
        Long providerId = repository.getProvider().getId();
        assertNotNull(providerId);
        pullRequestRepository.upsertCore(
                9200L + number,
                providerId,
                number,
                "A change worth reviewing",
                "Body",
                "OPEN",
                null,
                "https://github.com/" + repository.getNameWithOwner() + "/pull/" + number,
                false,
                null,
                0,
                now,
                now,
                now,
                prAuthor.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/branch",
                "main",
                "headsha",
                "basesha",
                null,
                null);
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), number)
                .orElseThrow()
                .getId();
    }

    private WebTestClient.BodyContentSpec readRun(UUID reviewId) {
        return webTestClient
                .get()
                .uri(RUN_URI, workspace.getWorkspaceSlug(), reviewId)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private void expectRunNotFound(UUID reviewId) {
        webTestClient
                .get()
                .uri(RUN_URI, workspace.getWorkspaceSlug(), reviewId)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private WebTestClient.BodyContentSpec readRuns() {
        return readRuns(null, null, null);
    }

    private WebTestClient.BodyContentSpec readRuns(
            @Nullable Integer page, @Nullable Integer size, @Nullable String kind) {
        return readRuns(page, size, kind, null);
    }

    private WebTestClient.BodyContentSpec readRuns(
            @Nullable Integer page, @Nullable Integer size, @Nullable String kind, @Nullable Instant since) {
        return webTestClient
                .get()
                .uri(builder -> {
                    var uri = builder.path(RUNS_URI);
                    if (page != null) {
                        uri.queryParam("page", page);
                    }
                    if (size != null) {
                        uri.queryParam("size", size);
                    }
                    if (kind != null) {
                        uri.queryParam("kind", kind);
                    }
                    if (since != null) {
                        uri.queryParam("since", since);
                    }
                    return uri.build(workspace.getWorkspaceSlug());
                })
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }
}
