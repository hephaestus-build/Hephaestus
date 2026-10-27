package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityScope;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityWorkCursor;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Limit;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitHubIssueCommentProcessorIntegrationTest extends BaseIntegrationTest {

    private static final Long TEST_ORG_ID = 215361191L;
    private static final Long TEST_REPO_ID = 998279771L;
    private static final Long TEST_ISSUE_ID = 123456789L;
    private static final Long TEST_COMMENT_ID = 987654321L;
    private static final String TEST_ORG_LOGIN = "HephaestusTest";
    private static final String TEST_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    @Autowired
    private GitHubIssueCommentProcessor processor;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RecordingScmEventListener eventListener;

    @Autowired
    private GitHubIssueCommentSyncService commentSyncService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ActivityQueryRepository activityQueries;

    private Repository testRepository;
    private Workspace testWorkspace;
    private Issue testIssue;
    private IdentityProvider githubProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        githubProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        Organization org = new Organization();
        org.setNativeId(TEST_ORG_ID);
        org.setLogin(TEST_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + TEST_ORG_ID);
        org.setHtmlUrl("https://github.com/" + TEST_ORG_LOGIN);
        org.setProvider(githubProvider);
        org = organizationRepository.save(org);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(TEST_REPO_ID);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner(TEST_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME);
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(githubProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("test-workspace");
        testWorkspace.setDisplayName("Test Workspace");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganizationId(org.getId());
        testWorkspace.setAccountLogin(TEST_ORG_LOGIN);
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);

        // Create issue
        testIssue = new Issue();
        testIssue.setNativeId(TEST_ISSUE_ID);
        testIssue.setProvider(githubProvider);
        testIssue.setNumber(42);
        testIssue.setTitle("Test Issue");
        testIssue.setState(Issue.State.OPEN);
        testIssue.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME + "/issues/42");
        testIssue.setCreatedAt(Instant.now());
        testIssue.setUpdatedAt(Instant.now());
        testIssue.setRepository(testRepository);
        testIssue = issueRepository.save(testIssue);
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    private GitHubCommentDTO createCommentDTO(Long id, String body) {
        return new GitHubCommentDTO(
                id,
                "node_id_" + id,
                "https://github.com/" + TEST_REPO_FULL_NAME + "/issues/42#issuecomment-" + id,
                body,
                null,
                "OWNER",
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private Repository storedRepository(IdentityProvider provider, long nativeId, String nameWithOwner) {
        var repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName("Other");
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://example.com/" + nameWithOwner);
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setProvider(provider);
        return repositoryRepository.save(repository);
    }

    private <T extends Issue> T stored(T work, Repository repository, int number, long nativeId) {
        work.setNativeId(nativeId);
        work.setProvider(repository.getProvider());
        work.setNumber(number);
        work.setTitle("Work " + number);
        work.setState(Issue.State.OPEN);
        work.setHtmlUrl(repository.getHtmlUrl() + "/" + number);
        work.setCreatedAt(Instant.now());
        work.setUpdatedAt(Instant.now());
        work.setRepository(repository);
        return work;
    }

    private PullRequest storedPullRequest(Repository repository, int number, long nativeId) {
        PullRequest pr = stored(new PullRequest(), repository, number, nativeId);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);
        return pullRequestRepository.save(pr);
    }

    /** A comment stored on {@code parent} as an earlier version did, with no processing. */
    private IssueComment storedComment(Issue parent, long nativeId) {
        var comment = new IssueComment();
        comment.setNativeId(nativeId);
        comment.setProvider(parent.getProvider());
        comment.setIssue(parent);
        comment.setBody("Comment " + nativeId);
        comment.setHtmlUrl(parent.getHtmlUrl() + "#issuecomment-" + nativeId);
        comment.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        comment.setUpdatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        comment.setAuthorAssociation(AuthorAssociation.fromString("OWNER"));
        return commentRepository.save(comment);
    }

    private IssueComment reloaded(IssueComment comment) {
        return commentRepository
                .findAllByIdWithRelations(List.of(Objects.requireNonNull(comment.getId())))
                .getFirst();
    }

    private Long parentOf(IssueComment comment) {
        return Objects.requireNonNull(
                Objects.requireNonNull(reloaded(comment).getIssue()).getId());
    }

    // GitHub shares issue and pull-request numbers; the stored pull request determines a comment's canonical parent.
    @Nested
    class CanonicalPullRequestParent {

        @Test
        void shouldMoveAReplayedCommentToTheStoredPullRequestWithoutAnEvent() {
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "On the pull request");
            IssueComment first = Objects.requireNonNull(processor.process(dto, 42, createContext()));
            assertThat(parentOf(first)).isEqualTo(testIssue.getId());
            PullRequest pr = storedPullRequest(testRepository, 42, TEST_ISSUE_ID + 1);
            eventListener.clear();

            IssueComment replayed = Objects.requireNonNull(processor.process(dto, 42, createContext()));

            assertThat(replayed.getId()).isEqualTo(first.getId());
            assertThat(reloaded(replayed).getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(reloaded(replayed).getBody()).isEqualTo("On the pull request");
            assertThat(parentOf(replayed)).isEqualTo(pr.getId());
            assertThat(issueRepository.findById(Objects.requireNonNull(testIssue.getId())))
                    .isPresent();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldMoveStoredCommentsOnceAndLeaveEveryOtherParent() {
            // A retired issue row keeps its tombstone; only its comments move. Retired before any
            // comment is
            // stored, so saving the detached row cannot orphan one.
            testIssue.setDeletedAt(Instant.parse("2025-02-01T00:00:00Z"));
            testIssue = issueRepository.save(testIssue);
            IssueComment first = storedComment(testIssue, 1);
            IssueComment second = storedComment(testIssue, 2);
            Issue genuine = issueRepository.save(stored(new Issue(), testRepository, 43, 4300));
            IssueComment onGenuine = storedComment(genuine, 3);
            Repository otherRepository =
                    storedRepository(githubProvider, TEST_REPO_ID + 1, "HephaestusTest/OtherRepository");
            Issue sameNumberElsewhere = issueRepository.save(stored(new Issue(), otherRepository, 42, 4201));
            IssueComment elsewhere = storedComment(sameNumberElsewhere, 4);
            var gitlab = gitProviderRepository.save(
                    new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com"));
            Repository gitlabRepository = storedRepository(gitlab, 77, "group/project");
            Issue gitlabIssue = issueRepository.save(stored(new Issue(), gitlabRepository, 42, 4202));
            storedPullRequest(gitlabRepository, 42, 4203);
            IssueComment onGitlabIssue = storedComment(gitlabIssue, 5);
            PullRequest pr = storedPullRequest(testRepository, 42, TEST_ISSUE_ID + 1);

            assertThat(commentSyncService.repairCanonicalParents(testRepository.getId()))
                    .isEqualTo(2);
            assertThat(commentSyncService.repairCanonicalParents(testRepository.getId()))
                    .isZero();
            assertThat(commentSyncService.repairCanonicalParents(gitlabRepository.getId()))
                    .isZero();
            assertThat(Objects.requireNonNull(processor.process(
                                    createCommentDTO(5L, "Comment 5"),
                                    42,
                                    ProcessingContext.forSync(testWorkspace.getId(), gitlabRepository)))
                            .getIssue())
                    .isNotNull()
                    .extracting(Issue::getId)
                    .isEqualTo(gitlabIssue.getId());

            assertThat(parentOf(first)).isEqualTo(pr.getId());
            assertThat(parentOf(second)).isEqualTo(pr.getId());
            assertThat(reloaded(first).getBody()).isEqualTo("Comment 1");
            assertThat(reloaded(first).getNativeId()).isEqualTo(1L);
            assertThat(parentOf(onGenuine)).isEqualTo(genuine.getId());
            assertThat(parentOf(elsewhere)).isEqualTo(sameNumberElsewhere.getId());
            assertThat(parentOf(onGitlabIssue)).isEqualTo(gitlabIssue.getId());
            assertThat(issueRepository.findById(Objects.requireNonNull(testIssue.getId())))
                    .hasValueSatisfying(issue -> {
                        assertThat(issue.getDeletedAt()).isEqualTo(Instant.parse("2025-02-01T00:00:00Z"));
                        assertThat(issue.getNativeId()).isEqualTo(TEST_ISSUE_ID);
                    });
            assertThat(pullRequestRepository.findById(Objects.requireNonNull(pr.getId())))
                    .hasValueSatisfying(
                            stored -> assertThat(stored.getNativeId()).isEqualTo(TEST_ISSUE_ID + 1));
        }

        @Test
        void shouldRegroupTheSameLedgerEventWithoutChangingItsFacts() {
            IssueComment comment = storedComment(testIssue, 1);
            PullRequest pr = storedPullRequest(testRepository, 42, TEST_ISSUE_ID + 1);
            long actorId = Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES" + " (?,?,?,'USER') RETURNING id",
                    Long.class,
                    77,
                    githubProvider.getId(),
                    "commenter"));
            UUID eventId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO activity_event(id,workspace_id,event_key,event_type,occurred_at,actor_id,
                    repository_id,target_type,target_id,ingested_at)
                VALUES (?,?,'stored-comment','COMMENT_CREATED','2025-01-01 10:00:00Z',?,?,'issue_comment',?,now())
                """, eventId, testWorkspace.getId(), actorId, testRepository.getId(), comment.getId());
            var before = jdbc.queryForMap("SELECT * FROM activity_event WHERE id=?", eventId);
            var scope =
                    new ActivityScope(testWorkspace.getId(), Set.of(actorId), Set.of(), Set.of(testRepository.getId()));
            var range = new TimeRange(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
            var cursor = new ActivityWorkCursor(range.to(), range.to(), "");
            assertThat(activityQueries.findWork(
                            scope, List.of(ActivityEventType.COMMENT_CREATED), range, cursor, Limit.of(10)))
                    .singleElement()
                    .satisfies(group -> assertThat(group.getId()).isEqualTo("work:" + testIssue.getId()));

            assertThat(commentSyncService.repairCanonicalParents(testRepository.getId()))
                    .isEqualTo(1);

            assertThat(jdbc.queryForMap("SELECT * FROM activity_event WHERE id=?", eventId))
                    .isEqualTo(before);
            assertThat(activityQueries.findWork(
                            scope, List.of(ActivityEventType.COMMENT_CREATED), range, cursor, Limit.of(10)))
                    .singleElement()
                    .satisfies(group -> {
                        assertThat(group.getId()).isEqualTo("work:" + pr.getId());
                        assertThat(group.getComments()).isEqualTo(1);
                    });
        }

        @Test
        void shouldContinueAfterTheLastIdUnderTheFixedCeilingAndRecheckTheParent() {
            IssueComment first = storedComment(testIssue, 1);
            IssueComment second = storedComment(testIssue, 2);
            PullRequest pr = storedPullRequest(testRepository, 42, TEST_ISSUE_ID + 1);
            long ceiling = processor.canonicalParentCeiling(testRepository.getId());
            assertThat(ceiling).isEqualTo(second.getId());
            // Stored after the pass began: above its ceiling.
            IssueComment later = storedComment(testIssue, 3);

            var chunk = processor.moveToCanonicalParents(testRepository.getId(), 0, ceiling, 1);
            assertThat(chunk.lastId()).isEqualTo(first.getId());
            assertThat(chunk.moved()).isEqualTo(1);
            assertThat(parentOf(second)).isEqualTo(testIssue.getId());

            // A parent changed after selection is not overwritten by the selected-ID update.
            Issue genuine = issueRepository.save(stored(new Issue(), testRepository, 43, 4300));
            IssueComment changed = reloaded(second);
            changed.setIssue(genuine);
            commentRepository.save(changed);
            int moved = Objects.requireNonNull(
                    transactionTemplate.execute(status -> commentRepository.moveToCanonicalPullRequest(
                            testRepository.getId(), List.of(Objects.requireNonNull(second.getId())))));
            assertThat(moved).isZero();
            assertThat(parentOf(second)).isEqualTo(genuine.getId());

            var rest = processor.moveToCanonicalParents(testRepository.getId(), chunk.lastId(), ceiling, 1);
            assertThat(rest.moved()).isZero();
            assertThat(parentOf(later)).isEqualTo(testIssue.getId());
            assertThat(commentSyncService.repairCanonicalParents(testRepository.getId()))
                    .isEqualTo(1);
            assertThat(parentOf(later)).isEqualTo(pr.getId());
        }
    }

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldCreateCommentAndPublishEvent() {
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "This is a test comment.");
            ProcessingContext context = createContext();

            IssueComment result = processor.process(dto, testIssue.getNumber(), context);

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result.getBody()).isEqualTo("This is a test comment.");
            assertNotNull(result.getIssue());
            assertThat(result.getIssue().getId()).isEqualTo(testIssue.getId());

            // Verify CommentCreated event was published
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.comment().id()).isEqualTo(result.getId());
                        assertThat(event.issueId()).isEqualTo(testIssue.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                    });
        }

        @Test
        void shouldHandleNullDTO() {
            IssueComment result = processor.process(null, testIssue.getNumber(), createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldHandleMissingIssue() {
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Test");

            IssueComment result = processor.process(dto, 999999, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    @Nested
    class ProcessMethodUpdate {

        @Test
        void shouldUpdateCommentAndPublishEvent() {
            // Create initial comment
            IssueComment existing = new IssueComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Original body");
            existing.setHtmlUrl("https://github.com/test");
            existing.setCreatedAt(Instant.now());
            existing.setIssue(testIssue);
            commentRepository.save(existing);

            // Update with new body
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Updated body");
            ProcessingContext context = createContext();

            IssueComment result = processor.process(dto, testIssue.getNumber(), context);

            assertNotNull(result);
            assertThat(result.getBody()).isEqualTo("Updated body");

            // Verify CommentUpdated event was published
            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.comment().id()).isEqualTo(result.getId());
                        assertThat(event.changedFields()).contains("body");
                        assertThat(event.issueId()).isEqualTo(testIssue.getId());
                    });
        }

        @Test
        void shouldNotPublishEventWhenNothingChanges() {
            // Create initial comment with matching data
            IssueComment existing = new IssueComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Same body");
            existing.setHtmlUrl(
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/issues/42#issuecomment-" + TEST_COMMENT_ID);
            existing.setAuthorAssociation(AuthorAssociation.OWNER);
            existing.setCreatedAt(Instant.now());
            existing.setIssue(testIssue);
            commentRepository.save(existing);

            // Process with same data
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Same body");

            processor.process(dto, testIssue.getNumber(), createContext());

            // No events should be published for unchanged data
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .isEmpty();
        }
    }

    @Nested
    class ProcessWithParentCreation {

        private static final Long NEW_ISSUE_ID = 555555555L;
        private static final Long NEW_PR_ID = 666666666L;

        private GitHubIssueDTO createIssueDTOForNewIssue(Long issueId, boolean isPullRequest) {
            return new GitHubIssueDTO(
                    issueId,
                    issueId,
                    "node_id_" + issueId,
                    100,
                    "New Issue from Comment Webhook",
                    "Issue body",
                    "open",
                    null,
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/issues/100",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null,
                    null,
                    isPullRequest
                            ? new GitHubIssueDTO.PullRequestRef(
                                    "https://api.github.com/repos/test/pulls/100", "https://github.com/test/pull/100")
                            : null,
                    null // subIssuesSummary
                    );
        }

        private GitHubIssueDTO createIssueDTOForExistingIssue(Long issueId, int number) {
            return new GitHubIssueDTO(
                    issueId,
                    issueId,
                    "node_id_" + issueId,
                    number,
                    "Existing Issue from Comment Webhook",
                    "Issue body",
                    "open",
                    null,
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/issues/" + number,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null,
                    null,
                    null,
                    null // subIssuesSummary
                    );
        }

        @Test
        void shouldCreateIssueStubWhenParentDoesNotExist() {
            GitHubCommentDTO commentDto = createCommentDTO(TEST_COMMENT_ID, "Comment on new issue");
            GitHubIssueDTO issueDto = createIssueDTOForNewIssue(NEW_ISSUE_ID, false);
            ProcessingContext context = createContext();

            // Verify issue does not exist
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 100))
                    .isEmpty();

            IssueComment result = processor.processWithParentCreation(commentDto, issueDto, context);

            // Verify comment was created
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result.getBody()).isEqualTo("Comment on new issue");

            // Verify Issue stub was created
            Issue createdIssue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 100)
                    .orElseThrow();
            assertThat(createdIssue.getNativeId()).isEqualTo(NEW_ISSUE_ID);
            assertThat(createdIssue.getNumber()).isEqualTo(100);
            assertThat(createdIssue.getTitle()).isEqualTo("New Issue from Comment Webhook");
            assertThat(createdIssue.isPullRequest()).isFalse();

            // Verify comment is linked to the new issue
            assertNotNull(result.getIssue());
            assertThat(result.getIssue().getId()).isEqualTo(createdIssue.getId());
        }

        @Test
        void shouldCreatePullRequestStubWhenParentIsPRAndDoesNotExist() {
            GitHubCommentDTO commentDto = createCommentDTO(TEST_COMMENT_ID, "Comment on new PR");
            GitHubIssueDTO issueDto = createIssueDTOForNewIssue(NEW_PR_ID, true);
            ProcessingContext context = createContext();

            // Verify PR does not exist
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), 100))
                    .isEmpty();

            IssueComment result = processor.processWithParentCreation(commentDto, issueDto, context);

            // Verify comment was created
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(TEST_COMMENT_ID);

            // Verify PullRequest stub was created (not Issue!)
            PullRequest createdPR = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 100)
                    .orElseThrow();
            assertThat(createdPR.getNativeId()).isEqualTo(NEW_PR_ID);
            assertThat(createdPR.getNumber()).isEqualTo(100);
            assertThat(createdPR.getTitle()).isEqualTo("New Issue from Comment Webhook");
            assertThat(createdPR.isPullRequest()).isTrue();

            // Verify comment is linked to the new PR (via Issue base class)
            assertNotNull(result.getIssue());
            assertThat(result.getIssue().getId()).isEqualTo(createdPR.getId());
        }

        @Test
        void shouldUseExistingParentWhenItAlreadyExists() {
            GitHubCommentDTO commentDto = createCommentDTO(TEST_COMMENT_ID, "Comment on existing issue");
            // Use testIssue's number (42) so that findByRepositoryIdAndNumber finds the existing
            // issue
            GitHubIssueDTO issueDto = createIssueDTOForExistingIssue(testIssue.getNativeId(), testIssue.getNumber());
            ProcessingContext context = createContext();

            // Get initial issue count
            long initialCount = issueRepository.count();

            IssueComment result = processor.processWithParentCreation(commentDto, issueDto, context);

            // Verify comment was created
            assertNotNull(result);
            assertNotNull(result.getIssue());
            assertThat(result.getIssue().getId()).isEqualTo(testIssue.getId());

            // Verify no new issue was created
            assertThat(issueRepository.count()).isEqualTo(initialCount);
        }

        @Test
        void shouldUseTheStoredPullRequestForAWebhookBesideAnIssueSibling() {
            PullRequest pr = storedPullRequest(testRepository, 42, TEST_ISSUE_ID + 1);
            GitHubCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Conversation");
            GitHubIssueDTO issueDto = createIssueDTOForExistingIssue(TEST_ISSUE_ID, 42);

            IssueComment comment =
                    Objects.requireNonNull(processor.processWithParentCreation(dto, issueDto, createContext()));

            assertThat(parentOf(comment)).isEqualTo(pr.getId());
            assertThat(reloaded(comment).getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(issueRepository.findById(Objects.requireNonNull(testIssue.getId())))
                    .isPresent();
        }

        @Test
        void shouldHandleMissingIssueIdGracefully() {
            GitHubCommentDTO commentDto = createCommentDTO(TEST_COMMENT_ID, "Comment");
            GitHubIssueDTO issueDto = new GitHubIssueDTO(
                    null,
                    null,
                    null,
                    100,
                    "Title",
                    null,
                    "open",
                    null,
                    null,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null,
                    null,
                    null,
                    null // subIssuesSummary
                    );
            ProcessingContext context = createContext();

            IssueComment result = processor.processWithParentCreation(commentDto, issueDto, context);

            assertThat(result).isNull();
        }
    }

    @Nested
    class DeleteMethod {

        @Test
        void shouldDeleteCommentAndPublishEvent() {
            // Create comment to delete
            IssueComment existing = new IssueComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("To be deleted");
            existing.setHtmlUrl("https://github.com/test");
            existing.setCreatedAt(Instant.now());
            existing.setIssue(testIssue);
            commentRepository.save(existing);

            processor.delete(TEST_COMMENT_ID, createContext());

            // Verify comment is deleted
            assertThat(commentRepository.findByNativeIdAndProviderId(TEST_COMMENT_ID, githubProviderId()))
                    .isEmpty();

            // Verify CommentDeleted event was published
            assertThat(eventListener.ofType(ScmDomainEvent.CommentDeleted.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.commentId()).isEqualTo(TEST_COMMENT_ID);
                        assertThat(event.issueId()).isEqualTo(testIssue.getId());
                    });
        }

        @Test
        @Transactional
        void shouldSyncBidirectionalRelationshipWhenDeleting() {
            // Create comment with bidirectional relationship set up
            IssueComment existing = new IssueComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Comment to test bidirectional sync");
            existing.setHtmlUrl("https://github.com/test");
            existing.setCreatedAt(Instant.now());
            existing.setIssue(testIssue);
            commentRepository.save(existing);

            // Load the issue and initialize its comments collection (simulates
            // real-world scenario where parent is in persistence context)
            Issue loadedIssue = issueRepository.findById(testIssue.getId()).orElseThrow();
            Hibernate.initialize(loadedIssue.getComments());

            // Delete should work without TransientObjectException
            // because the processor syncs bidirectional relationship
            assertThatCode(() -> processor.delete(TEST_COMMENT_ID, createContext()))
                    .doesNotThrowAnyException();

            // Verify comment is deleted
            assertThat(commentRepository.findByNativeIdAndProviderId(TEST_COMMENT_ID, githubProviderId()))
                    .isEmpty();
        }

        @Test
        void shouldHandleNullCommentId() {
            processor.delete(null, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.CommentDeleted.class))
                    .isEmpty();
        }

        @Test
        void shouldHandleNonExistentComment() {
            processor.delete(999999L, createContext());

            // No event should be published for non-existent comment
            assertThat(eventListener.ofType(ScmDomainEvent.CommentDeleted.class))
                    .isEmpty();
        }
    }

    @Test
    void shouldCommitRemainingCommentsAcrossPagesWhenInputProviderIsDetached() {
        assertDetachedCommentPagination(true);
    }

    @Test
    void shouldCommitFullCommentsAcrossPagesWhenInputProviderIsDetached() {
        assertDetachedCommentPagination(false);
    }

    private void assertDetachedCommentPagination(boolean remaining) {
        Repository detachedRepository = Objects.requireNonNull(transactionTemplate.execute(
                status -> repositoryRepository.findById(testRepository.getId()).orElseThrow()));
        assertThat(Hibernate.isInitialized(detachedRepository.getProvider())).isFalse();
        Issue detachedIssue = new Issue();
        detachedIssue.setId(testIssue.getId());
        detachedIssue.setNumber(testIssue.getNumber());
        detachedIssue.setRepository(detachedRepository);

        var provider = mock(GitHubGraphQlClientProvider.class);
        var client = mock(HttpGraphQlClient.class);
        var request = mock(RequestSpec.class);
        when(provider.forScope(testWorkspace.getId())).thenReturn(client);
        when(provider.getRateLimitRemaining(testWorkspace.getId())).thenReturn(5000);
        when(client.documentName("GetIssueComments")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        ClientGraphQlResponse firstPage = commentPage(TEST_COMMENT_ID + 1, true);
        ClientGraphQlResponse secondPage = commentPage(TEST_COMMENT_ID + 2, false);
        when(request.execute()).thenReturn(Mono.just(firstPage)).thenReturn(Mono.just(secondPage));
        var service = new GitHubIssueCommentSyncService(
                provider,
                processor,
                applicationContext.getBean(GitHubSyncProperties.class),
                applicationContext.getBean(GitHubExceptionClassifier.class),
                applicationContext.getBean(GitHubGraphQlSyncCoordinator.class),
                repositoryRepository);

        Integer count = transactionTemplate.execute(status -> remaining
                ? service.syncRemainingComments(testWorkspace.getId(), detachedIssue, "embedded-page")
                : service.syncForIssue(testWorkspace.getId(), detachedIssue));

        assertThat(count).isEqualTo(2);
        for (long nativeId : List.of(TEST_COMMENT_ID + 1, TEST_COMMENT_ID + 2)) {
            assertThat(commentRepository.findByNativeIdAndProviderId(nativeId, githubProviderId()))
                    .hasValueSatisfying(comment -> {
                        Issue parent = comment.getIssue();
                        assertNotNull(parent);
                        assertThat(parent.getId()).isEqualTo(testIssue.getId());
                    });
        }
        assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class)).hasSize(2);
    }

    private ClientGraphQlResponse commentPage(long nativeId, boolean hasNext) {
        var comment = new GHIssueComment();
        comment.setId("IC_" + nativeId);
        comment.setFullDatabaseId(BigInteger.valueOf(nativeId));
        comment.setBody("Captured pagination fixture");
        comment.setUrl(URI.create("https://github.com/" + TEST_REPO_FULL_NAME + "/issues/42#issuecomment-" + nativeId));
        comment.setCreatedAt(OffsetDateTime.parse("2026-10-17T12:00:00Z"));
        comment.setUpdatedAt(OffsetDateTime.parse("2026-10-17T12:00:00Z"));
        var pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNext);
        pageInfo.setEndCursor("next-page");
        var connection = new GHIssueCommentConnection();
        connection.setNodes(List.of(comment));
        connection.setTotalCount(2);
        connection.setPageInfo(pageInfo);
        var response = mock(ClientGraphQlResponse.class);
        var field = mock(ClientResponseField.class);
        when(response.isValid()).thenReturn(true);
        when(response.field("repository.issue.comments")).thenReturn(field);
        when(field.toEntity(GHIssueCommentConnection.class)).thenReturn(connection);
        return response;
    }

    private Long githubProviderId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}
