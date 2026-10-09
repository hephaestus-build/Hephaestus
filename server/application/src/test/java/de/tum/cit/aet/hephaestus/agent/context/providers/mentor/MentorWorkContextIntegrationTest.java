package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettings;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The developer's own work as Heph reads it from the stored copy: the change behind a pull request, an issue they own,
 * and their dated activity — each read only inside monitored repositories that no team hides from contributions.
 */
class MentorWorkContextIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String INSTANCE = "https://gitlab.context.example";

    @Autowired
    private MergeReadinessContentSource mergeReadiness;

    @Autowired
    private IssueDetailSource issueDetail;

    @Autowired
    private ActivityContentSource activity;

    @Autowired
    private RecentAuthoredWorkContentSource recentAuthoredWork;

    @Autowired
    private WorkspaceContentSource workspaceContext;

    @Autowired
    private UserContentSource userContext;

    @Autowired
    private ObservationHistoryContentSource observationHistory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private CommitRepository commitRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;

    private final AtomicLong nativeIds = new AtomicLong(140_000);
    private final Instant yesterday = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

    private IdentityProvider instance;
    private Workspace workspace;
    private Repository course;
    private Repository hidden;
    private User student;
    private User tutor;

    @BeforeEach
    void setUp() {
        instance = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, INSTANCE)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, INSTANCE)));
        student = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "learner", instance));
        tutor = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "coach", instance));
        workspace = createWorkspace("work-context", "Work context", "work-context", AccountType.ORG, student);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "GITLAB",
                new ConnectionConfig.GitLabConfig(
                        INSTANCE, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null));
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.saveAndFlush(connection);
        course = monitored("context/course");
        hidden = monitored("context/hidden");
        hide(hidden);
    }

    @Test
    void shouldGiveThePullRequestsReviewersCommitsAndFileTotalsAndSayWhenFilesWereNotCaptured() {
        PullRequest pr = pullRequest(course, 3, student);
        User mentor = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "mentor", instance));
        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        reviewers.put(tutor, RequestedReviewer.ReviewState.UNREVIEWED);
        reviewers.put(mentor, RequestedReviewer.ReviewState.APPROVED);
        pr.replaceRequestedReviewers(reviewers, yesterday);
        pr.setCommits(2);
        pr = pullRequestRepository.save(pr);
        Commit first = commit(course, student, "Add the parser", yesterday.minusSeconds(60), pr);
        first.setGitDetailsCapturedAt(yesterday);
        first.getFileChanges().add(fileChange(first, "src/Parser.java", 40, 2));
        first.getFileChanges().add(fileChange(first, "README.md", 3, 0));
        commitRepository.save(first);
        commit(course, student, "Fix the parser test", yesterday, pr);
        PullRequest bare = pullRequest(course, 4, student);
        bare.setCommits(1);
        bare = pullRequestRepository.save(bare);
        commit(course, student, "Start the feature", yesterday, bare);

        JsonNode change = inspect(pr);
        JsonNode uncaptured = inspect(bare);

        assertThat(change.path("repository").asString()).isEqualTo("context/course");
        assertThat(change.path("createdAt").asString()).isEqualTo(yesterday.toString());
        assertThat(change.path("requestedReviewers")
                        .valueStream()
                        .map(r -> r.path("login").asString() + " "
                                + r.path("awaitingVerdict").asBoolean()))
                .containsExactly("coach true", "mentor false");
        assertThat(change.path("commits")
                        .valueStream()
                        .map(c -> c.path("headline").asString()))
                .containsExactly("Add the parser", "Fix the parser test");
        assertThat(change.path("commits").get(0).path("url").asString())
                .isEqualTo(first.getHtmlUrl())
                .startsWith(INSTANCE + "/context/course/-/commit/");
        assertThat(change.path("files")
                        .valueStream()
                        .map(f -> f.path("path").asString() + " +"
                                + f.path("additions").asInt() + " -"
                                + f.path("deletions").asInt()))
                .containsExactlyInAnyOrder("src/Parser.java +40 -2", "README.md +3 -0");
        assertThat(change.path("filesCoverage").asString())
                .as("one of the two commits has no captured file changes")
                .isEqualTo("PARTIAL");
        assertThat(uncaptured.path("files")).isEmpty();
        assertThat(uncaptured.path("filesCoverage").asString()).isEqualTo("NOT_CAPTURED");
    }

    @Test
    void shouldGiveAnAssignedIssueWithItsDiscussionAndNoIssueTheDeveloperDoesNotOwnOrCannotSee() {
        Issue assigned = issue(course, 11, tutor, "Write the setup guide.");
        assigned.addAssignee(student);
        assigned = issueRepository.save(assigned);
        comment(assigned, tutor, "Start with the install steps.");
        Issue others = issue(course, 12, tutor, "Someone else's task.");
        Issue inHiddenRepository = issue(hidden, 13, student, "Hidden work.");

        JsonNode found = issueDetail.inspect(workspace.getId(), student.getId(), assigned.getId());

        JsonNode issue = found.path("issue");
        assertThat(issue.path("number").asInt()).isEqualTo(11);
        assertThat(issue.path("developerRelation").valueStream().map(JsonNode::asString))
                .containsExactly("ASSIGNEE");
        assertThat(issue.path("body").asString()).isEqualTo("Write the setup guide.");
        assertThat(issue.path("comments").get(0).path("body").asString()).isEqualTo("Start with the install steps.");
        assertThat(issue.path("comments").get(0).path("url").asString()).startsWith(assigned.getHtmlUrl() + "#note_");
        for (Issue unreadable : List.of(others, inHiddenRepository)) {
            assertThat(issueDetail
                            .inspect(workspace.getId(), student.getId(), unreadable.getId())
                            .path("status")
                            .asString())
                    .as("issue #%d", unreadable.getNumber())
                    .isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void shouldNameNoRelatedWorkOrClosingPullRequestOutsideTheScopeTheIssueWasReadIn() {
        Issue assigned = issue(course, 61, tutor, "Ship the guide.");
        Issue hiddenParent = issue(hidden, 62, tutor, "HIDDEN-PARENT");
        assigned.setParentIssue(hiddenParent);
        assigned.addAssignee(student);
        Issue hiddenBlocker = issue(hidden, 63, tutor, "HIDDEN-BLOCKER");
        assigned.getBlockedBy().add(hiddenBlocker);
        assigned = issueRepository.save(assigned);
        // Monitored only by another workspace on the same instance.
        Workspace other = createWorkspace("other-context", "Other", "other-context", AccountType.ORG, tutor);
        Repository foreign = repository(instance, "context/foreign");
        monitor(other, "context/foreign");
        Issue foreignSub = issue(foreign, 64, tutor, "FOREIGN-SUB");
        foreignSub.setParentIssue(assigned);
        issueRepository.save(foreignSub);
        Issue visibleSub = issue(course, 65, tutor, "Visible sub-issue.");
        visibleSub.setParentIssue(assigned);
        issueRepository.save(visibleSub);
        Issue visibleBlocked = issue(course, 66, tutor, "Visible blocked issue.");
        visibleBlocked.getBlockedBy().add(assigned);
        issueRepository.save(visibleBlocked);
        for (Repository repository : List.of(hidden, foreign, course)) {
            PullRequest closing = pullRequest(repository, 70 + (int) (repository.getId() % 20), tutor);
            closing.getClosingIssues().add(assigned);
            pullRequestRepository.save(closing);
        }

        JsonNode issue = issueDetail
                .inspect(workspace.getId(), student.getId(), assigned.getId())
                .path("issue");

        assertThat(issue.has("parentIssue")).isFalse();
        assertThat(issue.path("blockedBy")).isEmpty();
        assertThat(issue.path("subIssues")
                        .path("items")
                        .valueStream()
                        .map(item -> item.path("number").asInt()))
                .containsExactly(65);
        assertThat(issue.path("blocking")
                        .valueStream()
                        .map(item -> item.path("number").asInt()))
                .containsExactly(66);
        assertThat(issue.path("subIssues").path("items").get(0).path("url").asString())
                .as("a related item carries its whole link")
                .isEqualTo(INSTANCE + "/context/course/-/issues/65");
        assertThat(issue.path("closingPullRequests")
                        .valueStream()
                        .map(pr -> pr.path("url").asString()))
                .singleElement()
                .asString()
                .contains("context/course");
        assertThat(issue.toString()).doesNotContain("context/hidden", "context/foreign");
    }

    /** A developer's work under the path this workspace monitors, on the instance it no longer connects to. */
    private User workOnAPreviousInstance() {
        IdentityProvider previous = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.previous.example")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.previous.example")));
        User former = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "former", previous));
        User colleague =
                userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "colleague", previous));
        Repository samePath = repository(previous, "context/course");
        PullRequest own = pullRequest(samePath, 81, former);
        review(own, colleague, "PREVIOUS-INSTANCE-REVIEW-RECEIVED");
        review(pullRequest(samePath, 82, colleague), former, "PREVIOUS-INSTANCE-REVIEW");
        issue(samePath, 83, former, "PREVIOUS-INSTANCE-ISSUE");
        Issue assigned = issue(samePath, 84, colleague, "PREVIOUS-INSTANCE-ASSIGNED");
        assigned.addAssignee(former);
        issueRepository.save(assigned);
        PullRequest asking = pullRequest(samePath, 85, colleague);
        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        reviewers.put(former, RequestedReviewer.ReviewState.UNREVIEWED);
        asking.replaceRequestedReviewers(reviewers, yesterday);
        pullRequestRepository.save(asking);
        commit(samePath, former, "PREVIOUS-INSTANCE-COMMIT", yesterday, null);
        return former;
    }

    @Test
    void shouldReadNoActivityFromTheSameRepositoryPathOnAnotherInstance() {
        User former = workOnAPreviousInstance();

        JsonNode payload = activity.buildPayload(workspace.getId(), former.getId());

        for (String list : List.of("pullRequests", "issues", "reviewsGiven", "comments", "inlineComments", "commits")) {
            assertThat(payload.path(list)).as(list).isEmpty();
        }
    }

    @Test
    void shouldListNoRecentWorkFromTheSameRepositoryPathOnAnotherInstance() {
        User former = workOnAPreviousInstance();

        JsonNode payload = recentAuthoredWork.buildPayload(workspace.getId(), former.getId());

        assertThat(payload.path("pullRequests")).isEmpty();
        assertThat(payload.path("issues")).isEmpty();
    }

    @Test
    void shouldListNoAssignedIssueOrOwedReviewFromTheSameRepositoryPathOnAnotherInstance() {
        User former = workOnAPreviousInstance();

        JsonNode payload = workspaceContext.buildPayload(workspace.getId(), former.getId());

        assertThat(payload.path("assignedIssues")).isEmpty();
        assertThat(payload.path("pendingReviewRequests")).isEmpty();
    }

    @Test
    void shouldCountNothingFromTheSameRepositoryPathOnAnotherInstance() {
        User former = workOnAPreviousInstance();

        JsonNode payload = userContext.buildPayload(workspace.getId(), former.getId());

        assertThat(payload.path("thisWeek").valueStream().map(JsonNode::asLong)).containsOnly(0L);
        assertThat(payload.path("lastWeek").valueStream().map(JsonNode::asLong)).containsOnly(0L);
    }

    @Test
    void shouldListNoReceivedReviewFromTheSameRepositoryPathOnAnotherInstance() {
        User former = workOnAPreviousInstance();

        JsonNode payload = observationHistory.buildPayload(workspace.getId(), former.getId());

        assertThat(payload.path("reviewsReceived")).isEmpty();
    }

    @Test
    void shouldDateTheDevelopersOwnReviewsCommentsAndCommitsAndLeaveOutHiddenRepositories() {
        PullRequest theirs = pullRequest(course, 21, tutor);
        review(theirs, student, "Please add a test for the empty input.");
        review(theirs, tutor, "Not the developer's review.");
        PullRequest hiddenWork = pullRequest(hidden, 22, tutor);
        review(hiddenWork, student, "Hidden review.");
        comment(issue(course, 23, tutor, "A question."), student, "I can take this one.");
        commit(course, student, "Add the empty-input test", yesterday, null);
        commit(hidden, student, "Hidden commit", yesterday, null);

        JsonNode payload = read(activity, ActivityContentSource.OUTPUT_KEY);

        assertThat(payload.path("reviewsGiven")).hasSize(1);
        JsonNode given = payload.path("reviewsGiven").get(0);
        assertThat(given.path("pullRequest").path("number").asInt()).isEqualTo(21);
        assertThat(given.path("pullRequest").path("author").asString()).isEqualTo("coach");
        assertThat(given.path("submittedAt").asString()).isEqualTo(yesterday.toString());
        assertThat(given.path("words").asString()).isEqualTo("Please add a test for the empty input.");
        assertThat(given.path("url").asString()).isEqualTo(INSTANCE + "/context/course/-/merge_requests/21#review");
        assertThat(payload.path("comments")
                        .valueStream()
                        .map(c -> c.path("words").asString()))
                .containsExactly("I can take this one.");
        assertThat(payload.path("commits")
                        .valueStream()
                        .map(c -> c.path("headline").asString()))
                .containsExactly("Add the empty-input test");
        assertThat(payload.toString()).doesNotContain("Hidden");
    }

    @Test
    void shouldListAuthoredWorkWithItsDatesAndLeaveOutAHiddenRepository() {
        pullRequest(course, 31, student);
        pullRequest(hidden, 32, student);

        JsonNode payload = read(recentAuthoredWork, RecentAuthoredWorkContentSource.OUTPUT_KEY);

        assertThat(payload.path("pullRequests")
                        .valueStream()
                        .map(pr -> pr.path("number").asInt()))
                .containsExactly(31);
        JsonNode listed = payload.path("pullRequests").get(0);
        assertThat(listed.path("repository").asString()).isEqualTo("context/course");
        assertThat(listed.path("createdAt").asString()).isEqualTo(yesterday.toString());
    }

    private JsonNode inspect(PullRequest pr) {
        return mergeReadiness
                .inspect(workspace.getId(), student.getId(), pr.getId())
                .path("pullRequests")
                .get(0);
    }

    private JsonNode read(ContentSource source, String key) {
        Map<String, byte[]> files = new HashMap<>();
        source.contribute(
                new ContextRequest.HephTurnRequest(workspace.getId(), student.getId(), UUID.randomUUID(), null), files);
        return objectMapper.readTree(Objects.requireNonNull(files.get(key)));
    }

    private Repository monitored(String path) {
        Repository repository = repository(instance, path);
        monitor(workspace, path);
        return repository;
    }

    private void monitor(Workspace target, String path) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(target);
        monitor.setNameWithOwner(path);
        monitorRepository.save(monitor);
    }

    private Repository repository(IdentityProvider provider, String path) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(provider);
        repository.setName(path.substring(path.indexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(yesterday);
        repository.setUpdatedAt(yesterday);
        repository.setPushedAt(yesterday);
        return repositoryRepository.save(repository);
    }

    /** A team setting hiding the repository from contributions, which every developer surface leaves out. */
    private void hide(Repository repository) {
        Team team = new Team();
        team.setNativeId(nativeIds.incrementAndGet());
        team.setProvider(instance);
        team.setName("Hidden work");
        team.setSlug("hidden-work");
        team.setPrivacy(Team.Privacy.VISIBLE);
        WorkspaceTeamRepositorySettings settings =
                new WorkspaceTeamRepositorySettings(workspace, teamRepository.save(team), repository);
        settings.setHiddenFromContributions(true);
        teamRepositorySettings.save(settings);
    }

    private PullRequest pullRequest(Repository repository, int number, User author) {
        PullRequest pr = new PullRequest();
        pr.setNativeId(nativeIds.incrementAndGet());
        pr.setProvider(repository.getProvider());
        pr.setRepository(repository);
        pr.setNumber(number);
        pr.setTitle("MR !" + number);
        pr.setState(PullRequest.State.OPEN);
        pr.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + number);
        pr.setAuthor(author);
        pr.setHeadRefName("feature-" + number);
        pr.setBaseRefName("main");
        pr.setCreatedAt(yesterday);
        pr.setUpdatedAt(yesterday);
        return pullRequestRepository.save(pr);
    }

    private Issue issue(Repository repository, int number, User author, String body) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setBody(body);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/-/issues/" + number);
        issue.setAuthor(author);
        issue.setCreatedAt(yesterday);
        issue.setUpdatedAt(yesterday);
        return issueRepository.save(issue);
    }

    private void comment(Issue issue, User author, String body) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeIds.incrementAndGet());
        comment.setProvider(issue.getProvider());
        comment.setIssue(issue);
        comment.setAuthor(author);
        comment.setBody(body);
        comment.setHtmlUrl(issue.getHtmlUrl() + "#note_" + comment.getNativeId());
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setCreatedAt(yesterday);
        commentRepository.save(comment);
    }

    private void review(PullRequest pr, User reviewer, String body) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(nativeIds.incrementAndGet());
        review.setProvider(pr.getProvider());
        review.setPullRequest(pr);
        review.setAuthor(reviewer);
        review.setState(PullRequestReview.State.COMMENTED);
        review.setBody(body);
        review.setHtmlUrl(pr.getHtmlUrl() + "#review");
        review.setSubmittedAt(yesterday);
        reviewRepository.save(review);
    }

    private Commit commit(Repository repository, User author, String message, Instant at, @Nullable PullRequest of) {
        Commit commit = new Commit();
        commit.setSha(String.format(Locale.ROOT, "%040x", nativeIds.incrementAndGet()));
        commit.setMessage(message);
        commit.setHtmlUrl(repository.getHtmlUrl() + "/-/commit/" + commit.getSha());
        commit.setAuthoredAt(at);
        commit.setCommittedAt(at);
        commit.setRepository(repository);
        commit.setAuthor(author);
        if (of != null) {
            commit.getAssociatedPullRequests().add(of);
        }
        return commitRepository.save(commit);
    }

    private static CommitFileChange fileChange(Commit commit, String path, int additions, int deletions) {
        CommitFileChange change = new CommitFileChange();
        change.setCommit(commit);
        change.setFilename(path);
        change.setChangeType(CommitFileChange.ChangeType.MODIFIED);
        change.setAdditions(additions);
        change.setDeletions(deletions);
        change.setChanges(additions + deletions);
        return change;
    }
}
