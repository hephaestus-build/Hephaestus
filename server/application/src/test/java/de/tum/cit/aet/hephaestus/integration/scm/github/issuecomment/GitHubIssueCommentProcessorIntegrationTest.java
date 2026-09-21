package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for GitHubIssueCommentProcessor.
 * <p>
 * Tests the processor independently from the webhook handler to verify:
 * - Comment upsert logic (create vs update)
 * - Domain event publishing (CommentCreated, CommentUpdated, CommentDeleted)
 * - Context handling and workspace association
 */
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
        testWorkspace.setIsPubliclyViewable(true);
        testWorkspace.setOrganization(org);
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
                Instant.now(),
                Instant.now());
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
            // Use testIssue's number (42) so that findByRepositoryIdAndNumber finds the existing issue
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
            loadedIssue.getComments().size(); // Force lazy initialization

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

    private Long githubProviderId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}
