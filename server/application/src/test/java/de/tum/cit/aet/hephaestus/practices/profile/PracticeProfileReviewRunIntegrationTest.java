package de.tum.cit.aet.hephaestus.practices.profile;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.DEMONSTRATED_STRENGTH;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.NOT_APPLICABLE;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

        oldestRun = persistPullRequestReview(workspace, 30, OLDEST_RUN_AT);
        middleRun = persistPullRequestReview(workspace, 31, MIDDLE_RUN_AT);
        latestRun = persistPullRequestReview(workspace, 32, LATEST_RUN_AT);
        // The one run this test reads a status and a duration off: 41 seconds, start to finish.
        latestRun.setStatus(AgentJobStatus.COMPLETED);
        latestRun.setStartedAt(LATEST_RUN_AT.minusSeconds(41));
        latestRun = agentJobRepository.save(latestRun);
        // A run over a pull request the two of them wrote together: it observed both of them.
        colleaguesRun = persistPullRequestReview(workspace, 33, MIDDLE_RUN_AT.plusSeconds(60));

        observe(explainChanges, oldestRun, 30L, developer, DEMONSTRATED_STRENGTH, null, OLDEST_RUN_AT);
        observe(reviewableDiffSize, middleRun, 31L, developer, NOT_APPLICABLE, null, MIDDLE_RUN_AT);
        observe(explainChanges, latestRun, 32L, developer, DEMONSTRATED_STRENGTH, null, LATEST_RUN_AT);
        UUID latestProblem =
                observe(reviewableDiffSize, latestRun, 32L, developer, OMISSION_GAP, Severity.MAJOR, LATEST_RUN_AT);

        observe(
                explainChanges,
                colleaguesRun,
                33L,
                developer,
                DEMONSTRATED_STRENGTH,
                null,
                MIDDLE_RUN_AT.plusSeconds(60));
        // The colleague's two strengths on the same run must not be counted on this developer's page.
        colleaguesStrength = observe(
                explainChanges,
                colleaguesRun,
                33L,
                colleague,
                DEMONSTRATED_STRENGTH,
                null,
                MIDDLE_RUN_AT.plusSeconds(60));
        observe(
                reviewableDiffSize,
                colleaguesRun,
                33L,
                colleague,
                DEMONSTRATED_STRENGTH,
                null,
                MIDDLE_RUN_AT.plusSeconds(60));

        Feedback delivered = persistInAppFeedback(
                latestRun, developer, 1, FeedbackDeliveryState.DELIVERED, BODY, LATEST_RUN_AT.plusSeconds(30));
        bind(delivered, latestProblem);
        // The colleague heard about their own work from the shared run; that is not a number about this reader.
        Feedback theirs = persistInAppFeedback(
                colleaguesRun, colleague, 1, FeedbackDeliveryState.DELIVERED, BODY, MIDDLE_RUN_AT.plusSeconds(90));
        bind(theirs, colleaguesStrength);
    }

    /**
     * A run writes every observation it has in one pass partway through itself, so that moment is
     * neither its start nor its end: a reader who watched a review finish at 9:10 must not be told it
     * reviewed their work at 9:06.
     */
    @Test
    @WithUser
    @DisplayName("a run is dated by when it finished, and while it is still going by when it started")
    void shouldDateARunByItsOwnEdgesWhenTheyWereRecorded() {
        Instant finishedAt = LATEST_RUN_AT.plusSeconds(900);
        AgentJob finished = persistPullRequestReview(workspace, 50, finishedAt);
        finished.setStatus(AgentJobStatus.COMPLETED);
        finished.setStartedAt(LATEST_RUN_AT.plusSeconds(300));
        agentJobRepository.save(finished);
        observe(explainChanges, finished, 50L, developer, DEMONSTRATED_STRENGTH, null, LATEST_RUN_AT.plusSeconds(600));

        Instant startedAt = LATEST_RUN_AT.plusSeconds(1200);
        AgentJob running = persistPullRequestReview(workspace, 51, null);
        running.setStatus(AgentJobStatus.RUNNING);
        running.setStartedAt(startedAt);
        agentJobRepository.save(running);
        observe(explainChanges, running, 51L, developer, DEMONSTRATED_STRENGTH, null, LATEST_RUN_AT.plusSeconds(1500));

        readRuns(null, null, null)
                .jsonPath("$.content[?(@.reviewId=='" + finished.getId() + "')].reviewedAt")
                .isEqualTo(finishedAt.toString())
                .jsonPath("$.content[?(@.reviewId=='" + running.getId() + "')].reviewedAt")
                .isEqualTo(startedAt.toString())
                .jsonPath("$.content[?(@.reviewId=='" + running.getId() + "')].status")
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @WithUser
    @DisplayName("every run on the developer's own work, newest first, counting only what it said about them")
    void shouldListTheDevelopersOwnRunsNewestFirstWhenTheyReadTheirProfile() {
        readRuns(null, null, null)
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
                .jsonPath("$.content[0].reviewedWork.repositoryName")
                .isEqualTo("acme/api")
                .jsonPath("$.content[0].status")
                .isEqualTo("COMPLETED")
                .jsonPath("$.content[0].durationSeconds")
                .isEqualTo(41)
                .jsonPath("$.content[0].triggerMode")
                .isEqualTo("MANUAL")
                .jsonPath("$.content[0].observations.strengths")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.problems")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedbackDelivered")
                .isEqualTo(1)
                // The shared run: one strength about this reader, none of the colleague's, and no feedback
                // of theirs counted here.
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(colleaguesRun.getId().toString())
                .jsonPath("$.content[1].observations.strengths")
                .isEqualTo(1)
                .jsonPath("$.content[1].observations.problems")
                .isEqualTo(0)
                .jsonPath("$.content[1].feedbackDelivered")
                .isEqualTo(0)
                // A run that found nothing to judge still ran, and still appears.
                .jsonPath("$.content[2].reviewId")
                .isEqualTo(middleRun.getId().toString())
                .jsonPath("$.content[2].observations.notApplicable")
                .isEqualTo(1)
                .jsonPath("$.content[3].reviewId")
                .isEqualTo(oldestRun.getId().toString());
    }

    /**
     * A row leads with what slipped, so it takes the practices' names rather than a count: the latest run
     * recorded one problem and the run over shared work none.
     */
    @Test
    @WithUser
    @DisplayName("a run names the practices it recorded a problem about, and none where it recorded none")
    void shouldNameTheSlippedPracticesWhenTheRunRecordedAProblem() {
        readRuns(null, null, null)
                .jsonPath("$.content[0].slippedPractices.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].slippedPractices[0].practiceSlug")
                .isEqualTo("reviewable-diff-size")
                .jsonPath("$.content[0].slippedPractices[0].practiceName")
                .isEqualTo("Keep the diff reviewable")
                .jsonPath("$.content[1].slippedPractices.length()")
                .isEqualTo(0);
    }

    /**
     * The one home of the practices' names is the row, so the run's own level reads them off the same shape
     * rather than counting the observations beside it a second time.
     */
    @Test
    @WithUser
    @DisplayName("an opened run carries the same slipped practices its row does")
    void shouldCarryTheSlippedPracticesWhenOneRunIsOpened() {
        readRun(latestRun.getId())
                .jsonPath("$.run.slippedPractices.length()")
                .isEqualTo(1)
                .jsonPath("$.run.slippedPractices[0].practiceSlug")
                .isEqualTo("reviewable-diff-size");
    }

    /**
     * The row links to the feedback Hephaestus left on the work, which is the comment the run's summary
     * landed in addressed on the work's own page. GitHub anchors one as {@code #issuecomment-<id>}.
     */
    @Test
    @WithUser
    @DisplayName("a run whose summary landed in an addressable comment says where that feedback is read")
    void shouldAddressTheDeliveredFeedbackWhenTheCommentCanBeAddressed() {
        latestRun.setDeliveryCommentId("48620117");
        agentJobRepository.save(latestRun);

        readRuns(null, null, null)
                .jsonPath("$.content[0].feedbackUrl")
                .isEqualTo("https://github.com/acme/api/pull/32#issuecomment-48620117");
    }

    /**
     * A comment identifier that is not the number GitHub's own anchor takes cannot be turned into one, and a
     * run that delivered no comment has nothing to address either: both leave the row with the plain count.
     */
    @Test
    @WithUser
    @DisplayName("a run carries no feedback address when its comment cannot be addressed")
    void shouldCarryNoFeedbackAddressWhenTheCommentCannotBeAddressed() {
        latestRun.setDeliveryCommentId("IC_kwDOBm6k_c6cVyYt");
        agentJobRepository.save(latestRun);

        readRuns(null, null, null)
                .jsonPath("$.content[0].feedbackUrl")
                .doesNotExist()
                // The run below it delivered no comment at all.
                .jsonPath("$.content[1].feedbackUrl")
                .doesNotExist();
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
                OMISSION_GAP,
                Severity.MAJOR,
                MIDDLE_RUN_AT.plusSeconds(60),
                WITHHELD_EVIDENCE_JSON);

        readRun(colleaguesRun.getId())
                .jsonPath("$.run.observations.strengths")
                .isEqualTo(1)
                .jsonPath("$.run.observations.problems")
                .isEqualTo(0)
                .jsonPath("$.observations.length()")
                .isEqualTo(1)
                .jsonPath("$.observations[0].practiceSlug")
                .isEqualTo("explain-changes");
        readRuns(null, null, null)
                .jsonPath("$.content[1].reviewId")
                .isEqualTo(colleaguesRun.getId().toString())
                .jsonPath("$.content[1].observations.problems")
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
                OMISSION_GAP,
                Severity.MAJOR,
                LATEST_RUN_AT.plusSeconds(600),
                WITHHELD_EVIDENCE_JSON);

        readRuns(null, null, null)
                .jsonPath("$.content.length()")
                .isEqualTo(4)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(latestRun.getId().toString());
        webTestClient
                .get()
                .uri(RUN_URI, workspace.getWorkspaceSlug(), withheld.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    @DisplayName("a run that observed nothing about this developer is not theirs to read")
    void shouldAnswerNotFoundWhenTheRunObservedNothingAboutTheReader() {
        AgentJob somebodyElses = persistPullRequestReview(workspace, 34, MIDDLE_RUN_AT);
        observe(explainChanges, somebodyElses, 34L, colleague, DEMONSTRATED_STRENGTH, null, MIDDLE_RUN_AT);

        webTestClient
                .get()
                .uri(RUN_URI, workspace.getWorkspaceSlug(), somebodyElses.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
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
                DEMONSTRATED_STRENGTH,
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

    /**
     * A run names its work by what the work is called now. The run's own metadata is a snapshot taken when
     * it started, so a pull request renamed since then would otherwise be listed under a title the work
     * itself contradicts.
     */
    @Nested
    @DisplayName("The name the reviewed work is listed under")
    class CurrentTitle {

        private AgentJob runOnMirroredWork;

        @BeforeEach
        void seedRenamedWork() {
            Repository repository = persistMonitoredRepository();
            long pullRequestId = persistPullRequest(repository, developer, 78);
            // The run recorded "Pull request 78"; the mirror now holds the name the author gave it.
            runOnMirroredWork = persistPullRequestReview(workspace, 78, pullRequestId, LATEST_RUN_AT.plusSeconds(600));
            observe(
                    explainChanges,
                    runOnMirroredWork,
                    pullRequestId,
                    developer,
                    DEMONSTRATED_STRENGTH,
                    null,
                    LATEST_RUN_AT.plusSeconds(600));
        }

        @Test
        @WithUser
        @DisplayName("a pull request renamed after its review is listed under the name it has now")
        void shouldNameTheWorkByItsCurrentTitleWhenItWasRenamedAfterTheReview() {
            readRuns(null, null, null)
                    .jsonPath("$.content[?(@.reviewId=='" + runOnMirroredWork.getId() + "')].reviewedWork.title")
                    .isEqualTo("A change worth reviewing");
            readRun(runOnMirroredWork.getId())
                    .jsonPath("$.run.reviewedWork.title")
                    .isEqualTo("A change worth reviewing");
        }

        /** The run still ran on something, and the name it ran under is the only name left to give it. */
        @Test
        @WithUser
        @DisplayName("work this workspace no longer mirrors keeps the name the run recorded")
        void shouldKeepTheRecordedTitleWhenTheWorkIsNoLongerOnRecord() {
            readRuns(null, null, null)
                    .jsonPath("$.content[?(@.reviewId=='" + latestRun.getId() + "')].reviewedWork.title")
                    .isEqualTo("Pull request 32");
        }

        private Repository persistMonitoredRepository() {
            Repository repository = new Repository();
            repository.setNativeId(9102L);
            repository.setProvider(ensureGitHubProvider());
            repository.setName("api");
            repository.setNameWithOwner("acme/api");
            repository.setHtmlUrl("https://github.com/acme/api");
            repository.setDefaultBranch("main");
            repository = repositoryRepository.save(repository);
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setWorkspace(workspace);
            monitor.setNameWithOwner(repository.getNameWithOwner());
            repositoryToMonitorRepository.save(monitor);
            return repository;
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
    }

    /**
     * "Review this now" is offered only where the request front door would accept the ask, so the page must
     * answer the same question {@code ReviewRequestAuthority} does — for the author, for a colleague the run
     * also observed, and for an admin who wrote none of it.
     */
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
                    DEMONSTRATED_STRENGTH,
                    null,
                    LATEST_RUN_AT.plusSeconds(300));
            observe(
                    explainChanges,
                    sharedRun,
                    sharedPullRequestId,
                    colleague,
                    DEMONSTRATED_STRENGTH,
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
            workspaceMemberships
                    .findByWorkspace_IdAndUser_Id(workspace.getId(), colleague.getId())
                    .ifPresent(membership -> {
                        membership.setRole(WorkspaceRole.ADMIN);
                        workspaceMemberships.save(membership);
                    });

            assertMayRequest(sharedRun.getId(), 0, true);
        }

        @Test
        @WithUser
        @DisplayName("work this workspace no longer mirrors cannot be asked about, even by its author")
        void shouldSayNobodyMayAskWhenTheWorkIsNotInTheMirror() {
            // The seeded runs above carry artifact ids with no pull request behind them.
            assertMayRequest(latestRun.getId(), 1, false);
        }

        /** The same answer read off the list and off the run's own detail, which must not disagree. */
        private void assertMayRequest(UUID reviewId, int listIndex, boolean expected) {
            readRuns(null, null, null)
                    .jsonPath("$.content[" + listIndex + "].reviewId")
                    .isEqualTo(reviewId.toString())
                    .jsonPath("$.content[" + listIndex + "].mayRequest")
                    .isEqualTo(expected);
            readRun(reviewId).jsonPath("$.run.mayRequest").isEqualTo(expected);
        }

        private Repository persistMonitoredRepository() {
            Repository repository = new Repository();
            repository.setNativeId(9101L);
            repository.setProvider(ensureGitHubProvider());
            repository.setName("api");
            repository.setNameWithOwner("acme/api");
            repository.setHtmlUrl("https://github.com/acme/api");
            repository.setDefaultBranch("main");
            repository = repositoryRepository.save(repository);
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setWorkspace(workspace);
            monitor.setNameWithOwner(repository.getNameWithOwner());
            repositoryToMonitorRepository.save(monitor);
            return repository;
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
