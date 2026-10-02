package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ScmWork;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettings;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * The retained reviews Heph may name for a developer's own pull requests and issues, against a real schema: every
 * row asserted here was written by the test that asserts it.
 */
class ReviewAttemptsContentSourceIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String INSTANCE = "https://gitlab.attempts.example";
    private static final String OTHER_INSTANCE = "https://gitlab.elsewhere.example";
    private static final String COURSE = "course/intro";
    private static final String ERROR_TEXT = "SANDBOX-ERROR-NOT-FOR-THE-DEVELOPER";

    @Autowired
    private ReviewAttemptsContentSource source;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;

    private final AtomicLong nativeIds = new AtomicLong(190_000);

    private IdentityProvider instance;
    private Workspace workspace;
    private Connection connection;
    private Repository course;
    private User student;
    private User tutor;

    @BeforeEach
    void setUp() {
        instance = gitLabInstance(INSTANCE);
        student = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "student", instance));
        tutor = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "tutor", instance));
        workspace = createWorkspace("attempts-course", "Attempts course", "attempts", AccountType.ORG, student);
        connection = connect(workspace, INSTANCE);
        course = repository(instance, COURSE);
        monitor(workspace, COURSE);
    }

    @Test
    void shouldListEveryRetainedReviewOfTheStudentsIssueWhetherOrNotItRecordedAnything() {
        Issue issue = issue(course, 21, student);
        AgentJob opening = issueReview(issue, AgentJobStatus.COMPLETED, NOW.minus(Duration.ofHours(4)));
        AgentJob failedClosure = issueReview(issue, AgentJobStatus.FAILED, NOW.minus(Duration.ofHours(3)));
        AgentJob laterClosure = issueReview(issue, AgentJobStatus.COMPLETED, NOW.minus(Duration.ofHours(2)));
        AgentJob running = issueReview(issue, AgentJobStatus.RUNNING, NOW.minus(Duration.ofHours(1)));
        Practice closurePractice = persistPractice(workspace, null, "closure-outcome", "Closure outcome", null);
        observe(
                closurePractice,
                laterClosure,
                ArtifactKinds.ISSUE.value(),
                issue.getId(),
                student,
                null,
                Outcome.NOT_APPLICABLE,
                null,
                laterClosure.getCreatedAt(),
                DIFF_EVIDENCE_JSON,
                null);

        JsonNode detail = source.inspect(workspace.getId(), student.getId(), issueWork(issue));

        assertThat(detail.has("status")).isFalse();
        assertThat(detail.path("work").path("artifactKind").asString()).isEqualTo(ArtifactKinds.ISSUE.value());
        assertThat(detail.path("work").path("number").asInt()).isEqualTo(21);
        assertThat(reviewIds(detail))
                .containsExactly(running.getId(), laterClosure.getId(), failedClosure.getId(), opening.getId());
        assertThat(values(detail, "status")).containsExactly("IN_PROGRESS", "COMPLETED", "FAILED", "COMPLETED");
        JsonNode failed = detail.path("attempts").get(2);
        assertThat(failed.path("completedAt").asString())
                .isEqualTo(failedClosure.getCompletedAt().toString());
        assertThat(detail.path("attempts").get(0).has("completedAt")).isFalse();
        assertThat(detail.path("coverage").path("hasMore").asBoolean()).isFalse();
        assertThat(detail.path("coverage").path("lookbackDays").asInt()).isEqualTo(90);
        assertThat(detail.toString()).doesNotContain(ERROR_TEXT, "feedbackUrl", "practicesEvaluated");

        assertThat(reviewIds(overview()))
                .containsExactly(running.getId(), laterClosure.getId(), failedClosure.getId(), opening.getId());
    }

    @Test
    void shouldListAMergeRequestsReviewsUnderItsOwnKindAndNeverUnderTheOtherKind() {
        PullRequest mr = mergeRequest(course, 7, student);
        Issue issue = issue(course, 8, student);
        AgentJob ownReview = job(
                workspace,
                AgentJobType.PULL_REQUEST_REVIEW,
                idMetadata("pull_request_id", mr.getId()),
                AgentJobStatus.COMPLETED,
                NOW.minus(Duration.ofHours(1)));
        // Each names the merge request's id in a way its own type does not read it, or the issue's id as a merge
        // request.
        job(workspace, AgentJobType.ISSUE_REVIEW, idMetadata("issue_id", mr.getId()), AgentJobStatus.COMPLETED, NOW);
        job(
                workspace,
                AgentJobType.PULL_REQUEST_REVIEW,
                idMetadata("issue_id", mr.getId()),
                AgentJobStatus.COMPLETED,
                NOW);
        job(
                workspace,
                AgentJobType.PULL_REQUEST_REVIEW,
                idMetadata("pull_request_id", issue.getId()),
                AgentJobStatus.COMPLETED,
                NOW);

        JsonNode detail = source.inspect(
                workspace.getId(), student.getId(), new ScmWork(AgentJobType.PULL_REQUEST_REVIEW, mr.getId()));

        assertThat(detail.path("work").path("artifactKind").asString()).isEqualTo(ArtifactKinds.PULL_REQUEST.value());
        assertThat(reviewIds(detail)).containsExactly(ownReview.getId());
        assertThat(detail.path("attempts").get(0).path("reviewsResource").asString())
                .isEqualTo("inputs/context/review_attempts/pull_request/" + mr.getId() + ".json");
        assertThat(notFound(new ScmWork(AgentJobType.ISSUE_REVIEW, mr.getId()))).isTrue();
        assertThat(reviewIds(source.inspect(workspace.getId(), student.getId(), issueWork(issue))))
                .isEmpty();
        assertThat(reviewIds(overview())).containsExactly(ownReview.getId());
    }

    @Test
    void shouldAnswerTheSameNotFoundForEveryWorkTheStudentMayNotRead() {
        Issue tutors = issue(course, 30, tutor);
        Issue deleted = issue(course, 31, student);
        deleted.setDeletedAt(NOW);
        issueRepository.save(deleted);
        Repository hiddenRepository = repository(instance, "course/hidden");
        monitor(workspace, "course/hidden");
        hide(hiddenRepository);
        Issue hidden = issue(hiddenRepository, 32, student);
        Issue unmonitored = issue(repository(instance, "course/unmonitored"), 33, student);
        // The same path on another instance: the monitor matches it by path alone.
        Issue elsewhere = issue(repository(gitLabInstance(OTHER_INSTANCE), COURSE), 34, student);
        List<Issue> unreadable = List.of(tutors, deleted, hidden, unmonitored, elsewhere);
        unreadable.forEach(work -> issueReview(work, AgentJobStatus.FAILED, NOW));

        Set<String> answers = new HashSet<>();
        for (Issue work : unreadable) {
            JsonNode answer = source.inspect(workspace.getId(), student.getId(), issueWork(work));
            assertThat(answer.path("status").asString()).isEqualTo("NOT_FOUND");
            assertThat(answer.has("attempts")).isFalse();
            answers.add(answer.path("reason").asString());
        }
        assertThat(answers).hasSize(1);
        assertThat(source.inspect(workspace.getId(), tutor.getId(), issueWork(tutors))
                        .path("status")
                        .asString())
                .as("the tutor reads their own issue")
                .isNotEqualTo("NOT_FOUND");
        assertThat(overview().path("attempts")).isEmpty();
    }

    @Test
    void shouldNotListAnotherWorkspacesReviewOfTheSameIssue() {
        Issue issue = issue(course, 40, student);
        User otherOwner =
                userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "other", instance));
        Workspace other =
                createWorkspace("attempts-other", "Other course", "attempts-other", AccountType.ORG, otherOwner);
        monitor(other, COURSE);
        issueReview(other, issue, AgentJobStatus.COMPLETED, NOW);
        AgentJob own = issueReview(issue, AgentJobStatus.COMPLETED, NOW.minus(Duration.ofMinutes(5)));

        assertThat(reviewIds(source.inspect(workspace.getId(), student.getId(), issueWork(issue))))
                .containsExactly(own.getId());
        assertThat(reviewIds(overview())).containsExactly(own.getId());
    }

    @Test
    void shouldTellAnEligibleWorkWithoutRetainedReviewsApartFromInaccessibleWork() {
        Issue quiet = issue(course, 50, student);
        Issue old = issue(course, 51, student);
        issueReview(old, AgentJobStatus.FAILED, NOW.minus(Duration.ofDays(91)));
        AgentJob retained = issueReview(old, AgentJobStatus.COMPLETED, NOW.minus(Duration.ofDays(89)));

        JsonNode empty = source.inspect(workspace.getId(), student.getId(), issueWork(quiet));
        assertThat(empty.has("status")).isFalse();
        assertThat(empty.path("work").path("number").asInt()).isEqualTo(50);
        assertThat(empty.path("attempts")).isEmpty();
        assertThat(empty.path("coverage").path("hasMore").asBoolean()).isFalse();

        assertThat(reviewIds(source.inspect(workspace.getId(), student.getId(), issueWork(old))))
                .containsExactly(retained.getId());
    }

    @Test
    void shouldListTheNewestTwentyAndSayMoreExist() {
        Issue busy = issue(course, 60, student);
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            newestFirst.addFirst(issueReview(busy, AgentJobStatus.COMPLETED, NOW.minus(Duration.ofMinutes(60 - i)))
                    .getId());
        }

        JsonNode detail = source.inspect(workspace.getId(), student.getId(), issueWork(busy));
        assertThat(reviewIds(detail)).containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(detail.path("coverage").path("hasMore").asBoolean()).isTrue();

        JsonNode overview = overview();
        assertThat(reviewIds(overview)).containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(overview.path("coverage").path("hasMore").asBoolean()).isTrue();
    }

    @Test
    void shouldMatchNoTargetFromMalformedMetadataOrAnotherKindOfReview() {
        Issue issue = issue(course, 70, student);
        job(workspace, AgentJobType.ISSUE_REVIEW, stringId(issue.getId()), AgentJobStatus.FAILED, NOW);
        job(
                workspace,
                AgentJobType.ISSUE_REVIEW,
                objectMapper.createArrayNode().add(issue.getId()),
                AgentJobStatus.FAILED,
                NOW);
        job(
                workspace,
                AgentJobType.ISSUE_REVIEW,
                objectMapper.createObjectNode().putNull("issue_id"),
                AgentJobStatus.FAILED,
                NOW);
        job(workspace, AgentJobType.ISSUE_REVIEW, null, AgentJobStatus.FAILED, NOW);
        job(
                workspace,
                AgentJobType.CONVERSATION_REVIEW,
                idMetadata("issue_id", issue.getId()),
                AgentJobStatus.FAILED,
                NOW);
        AgentJob mentorPurpose = job(
                workspace,
                AgentJobType.ISSUE_REVIEW,
                idMetadata("issue_id", issue.getId()),
                AgentJobStatus.FAILED,
                NOW);
        mentorPurpose.setPurpose(AgentPurpose.MENTOR);
        agentJobRepository.save(mentorPurpose);

        JsonNode detail = source.inspect(workspace.getId(), student.getId(), issueWork(issue));

        assertThat(detail.has("status")).isFalse();
        assertThat(detail.path("attempts")).isEmpty();
        assertThat(overview().path("attempts")).isEmpty();
    }

    @Test
    void shouldBeUnavailableWithoutAnActiveConnectionAndFindNoWork() {
        Issue issue = issue(course, 80, student);
        issueReview(issue, AgentJobStatus.COMPLETED, NOW);
        connection.setState(IntegrationState.SUSPENDED);
        connectionRepository.saveAndFlush(connection);

        JsonNode overview = overview();
        assertThat(overview.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(overview.has("attempts")).isFalse();
        assertThat(source.inspect(workspace.getId(), student.getId(), issueWork(issue))
                        .path("status")
                        .asString())
                .isEqualTo("NOT_FOUND");
    }

    private JsonNode overview() {
        Map<String, byte[]> files = new HashMap<>();
        source.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), student.getId(), UUID.randomUUID(), null),
                files);
        return objectMapper.readTree(Objects.requireNonNull(files.get(ReviewAttemptsContentSource.OUTPUT_KEY)));
    }

    private boolean notFound(ScmWork work) {
        return "NOT_FOUND"
                .equals(source.inspect(workspace.getId(), student.getId(), work)
                        .path("status")
                        .asString());
    }

    private static ScmWork issueWork(Issue issue) {
        return new ScmWork(AgentJobType.ISSUE_REVIEW, issue.getId());
    }

    private static List<UUID> reviewIds(JsonNode document) {
        return document.path("attempts")
                .valueStream()
                .map(attempt -> UUID.fromString(attempt.path("reviewId").asString()))
                .toList();
    }

    private static List<String> values(JsonNode document, String field) {
        return document.path("attempts")
                .valueStream()
                .map(attempt -> attempt.path(field).asString())
                .toList();
    }

    private AgentJob issueReview(Issue issue, AgentJobStatus status, Instant createdAt) {
        return issueReview(workspace, issue, status, createdAt);
    }

    private AgentJob issueReview(Workspace target, Issue issue, AgentJobStatus status, Instant createdAt) {
        return job(target, AgentJobType.ISSUE_REVIEW, idMetadata("issue_id", issue.getId()), status, createdAt);
    }

    private JsonNode idMetadata(String key, long id) {
        return objectMapper.createObjectNode().put(key, id);
    }

    private JsonNode stringId(long id) {
        return objectMapper.createObjectNode().put("issue_id", Long.toString(id));
    }

    /** A practice review as submission records one, with an error a failed run may carry. */
    private AgentJob job(
            Workspace target,
            AgentJobType type,
            @Nullable JsonNode metadata,
            AgentJobStatus status,
            Instant createdAt) {
        AgentJob job = new AgentJob();
        job.setWorkspace(target);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(type);
        job.setIntegrationKind(IntegrationKind.GITLAB);
        job.setMetadata(metadata);
        job.setConfigSnapshot(objectMapper.createObjectNode().put("model", "test"));
        job.setStatus(status);
        job.setCreatedAt(createdAt);
        if (status != AgentJobStatus.RUNNING && status != AgentJobStatus.QUEUED) {
            job.setCompletedAt(createdAt.plusSeconds(60));
        }
        if (status == AgentJobStatus.FAILED) {
            job.setErrorMessage(ERROR_TEXT);
        }
        return agentJobRepository.save(job);
    }

    private Issue issue(Repository repository, int number, User author) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/-/issues/" + number);
        issue.setAuthor(author);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issueRepository.save(issue);
    }

    private PullRequest mergeRequest(Repository repository, int number, User author) {
        PullRequest mr = new PullRequest();
        mr.setNativeId(nativeIds.incrementAndGet());
        mr.setProvider(repository.getProvider());
        mr.setRepository(repository);
        mr.setNumber(number);
        mr.setTitle("MR !" + number);
        mr.setState(PullRequest.State.OPEN);
        mr.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + number);
        mr.setAuthor(author);
        mr.setHeadRefName("feature-" + number);
        mr.setBaseRefName("main");
        mr.setCreatedAt(Instant.now());
        mr.setUpdatedAt(Instant.now());
        return pullRequestRepository.save(mr);
    }

    private IdentityProvider gitLabInstance(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

    private Connection connect(Workspace target, String serverUrl) {
        Connection created = new Connection(
                target,
                IntegrationKind.GITLAB,
                "GITLAB",
                new ConnectionConfig.GitLabConfig(
                        serverUrl, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null));
        created.setState(IntegrationState.ACTIVE);
        return connectionRepository.saveAndFlush(created);
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
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        return repositoryRepository.save(repository);
    }

    private void monitor(Workspace target, String path) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(target);
        monitor.setNameWithOwner(path);
        monitorRepository.save(monitor);
    }

    /** A team setting hiding the repository from contributions, which every developer surface leaves out. */
    private void hide(Repository repository) {
        Team team = new Team();
        team.setNativeId(nativeIds.incrementAndGet());
        team.setProvider(repository.getProvider());
        team.setName("Hidden work");
        team.setSlug("hidden-work");
        team.setPrivacy(Team.Privacy.VISIBLE);
        WorkspaceTeamRepositorySettings settings =
                new WorkspaceTeamRepositorySettings(workspace, teamRepository.save(team), repository);
        settings.setHiddenFromContributions(true);
        teamRepositorySettings.save(settings);
    }
}
