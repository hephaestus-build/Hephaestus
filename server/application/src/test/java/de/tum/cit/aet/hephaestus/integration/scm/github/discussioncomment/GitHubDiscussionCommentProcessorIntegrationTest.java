package de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto.GitHubDiscussionCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubDiscussionCommentProcessorIntegrationTest extends BaseIntegrationTest {

    private static final Long TEST_ORG_ID = 215361191L;
    private static final Long TEST_REPO_ID = 998279771L;
    private static final Long TEST_DISCUSSION_ID = 9096662L;
    private static final Long TEST_COMMENT_ID = 14848457L;
    private static final String TEST_ORG_LOGIN = "HephaestusTest";
    private static final String TEST_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    @Autowired
    private GitHubDiscussionCommentProcessor processor;

    @Autowired
    private DiscussionCommentRepository commentRepository;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;
    private Discussion testDiscussion;
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

        // Create discussion
        testDiscussion = new Discussion();
        testDiscussion.setNativeId(TEST_DISCUSSION_ID);
        testDiscussion.setNumber(27);
        testDiscussion.setTitle("Test Discussion");
        testDiscussion.setState(Discussion.State.OPEN);
        testDiscussion.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27");
        testDiscussion.setCreatedAt(Instant.now());
        testDiscussion.setUpdatedAt(Instant.now());
        testDiscussion.setRepository(testRepository);
        testDiscussion.setProvider(githubProvider);
        testDiscussion = discussionRepository.save(testDiscussion);
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    private GitHubDiscussionCommentDTO createCommentDTO(Long id, String body) {
        return new GitHubDiscussionCommentDTO(
                id,
                id,
                "node_id_" + id,
                body,
                "https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-" + id,
                false,
                false,
                null,
                "OWNER",
                Instant.now(),
                Instant.now(),
                new GitHubUserDTO(
                        100L,
                        100L,
                        "test-user",
                        "https://avatars.githubusercontent.com/u/100",
                        "https://github.com/test-user",
                        "Test User",
                        null),
                null);
    }

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldCreateCommentAndPublishCreatedEvent() {
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "This is a test comment.");
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result.getBody()).isEqualTo("This is a test comment.");
            assertThat(result.getDiscussion().getId()).isEqualTo(testDiscussion.getId());

            // Verify DiscussionCommentCreated event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.comment().id()).isEqualTo(result.getId());
                        assertThat(event.discussionId()).isEqualTo(testDiscussion.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                    });
        }

        @Test
        void shouldHandleNullDtoGracefully() {
            GitHubDiscussionCommentDTO dto = new GitHubDiscussionCommentDTO(
                    null,
                    null,
                    "node_id",
                    "body",
                    "https://github.com/test",
                    false,
                    false,
                    null,
                    "NONE",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null);

            DiscussionComment result = processor.process(dto, testDiscussion, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldHandleNullAuthor() {
            GitHubDiscussionCommentDTO dto = new GitHubDiscussionCommentDTO(
                    TEST_COMMENT_ID,
                    TEST_COMMENT_ID,
                    "node_id_" + TEST_COMMENT_ID,
                    "Comment without author",
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-"
                            + TEST_COMMENT_ID,
                    false,
                    false,
                    null,
                    "NONE",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null);
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result.getAuthor()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldSetDiscussionRelationship() {
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Test body");
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.getDiscussion()).isNotNull();
            assertThat(result.getDiscussion().getNativeId()).isEqualTo(TEST_DISCUSSION_ID);
            assertThat(result.getDiscussion().getTitle()).isEqualTo("Test Discussion");
        }

        @Test
        void shouldSetAuthorAssociation() {
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Test body");
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.getAuthorAssociation()).isEqualTo(AuthorAssociation.OWNER);
        }
    }

    @Nested
    class ProcessMethodUpdate {

        @Test
        void shouldUpdateCommentAndPublishEditedEventWhenBodyChanges() {
            // Create initial comment
            DiscussionComment existing = new DiscussionComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Original body");
            existing.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-"
                    + TEST_COMMENT_ID);
            existing.setCreatedAt(Instant.now());
            existing.setDiscussion(testDiscussion);
            commentRepository.save(existing);

            // Update with new body
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Updated body");
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.getBody()).isEqualTo("Updated body");

            // Verify DiscussionCommentEdited event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentEdited.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.comment().id()).isEqualTo(result.getId());
                        assertThat(event.changedFields()).contains("body");
                        assertThat(event.discussionId()).isEqualTo(testDiscussion.getId());
                    });
        }

        @Test
        void shouldUpdateAnswerStatusAndPublishEditedEvent() {
            // Create initial comment that is not an answer
            DiscussionComment existing = new DiscussionComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Answer body");
            existing.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-"
                    + TEST_COMMENT_ID);
            existing.setAnswer(false);
            existing.setCreatedAt(Instant.now());
            existing.setDiscussion(testDiscussion);
            commentRepository.save(existing);

            // Update with isAnswer=true
            GitHubDiscussionCommentDTO dto = new GitHubDiscussionCommentDTO(
                    TEST_COMMENT_ID,
                    TEST_COMMENT_ID,
                    "node_id_" + TEST_COMMENT_ID,
                    "Answer body",
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-"
                            + TEST_COMMENT_ID,
                    true,
                    false,
                    null,
                    "OWNER",
                    Instant.now(),
                    Instant.now(),
                    new GitHubUserDTO(
                            100L,
                            100L,
                            "test-user",
                            "https://avatars.githubusercontent.com/u/100",
                            "https://github.com/test-user",
                            "Test User",
                            null),
                    null);
            ProcessingContext context = createContext();

            DiscussionComment result = processor.process(dto, testDiscussion, context);

            assertNotNull(result);
            assertThat(result.isAnswer()).isTrue();

            // Verify DiscussionCommentEdited event was published with isAnswer change
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentEdited.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.changedFields()).contains("isAnswer");
                        assertThat(event.discussionId()).isEqualTo(testDiscussion.getId());
                    });
        }

        @Test
        void shouldNotPublishEditedEventWhenNothingChanges() {
            // Create initial comment with matching data
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Same body");
            ProcessingContext context = createContext();

            // First process creates the comment
            processor.process(dto, testDiscussion, context);
            eventListener.clear();

            // Process again with the same data
            processor.process(dto, testDiscussion, context);

            // No edited events should be published for unchanged data
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentCreated.class))
                    .isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentEdited.class))
                    .isEmpty();
        }

        @Test
        void shouldBeIdempotent() {
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Idempotent body");
            ProcessingContext context = createContext();

            // Process multiple times
            DiscussionComment result1 = processor.process(dto, testDiscussion, context);
            assertNotNull(result1);
            DiscussionComment result2 = processor.process(dto, testDiscussion, context);
            assertNotNull(result2);
            DiscussionComment result3 = processor.process(dto, testDiscussion, context);
            assertNotNull(result3);

            // All results should have the same ID and body
            assertThat(result1.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result2.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result3.getNativeId()).isEqualTo(TEST_COMMENT_ID);
            assertThat(result1.getBody()).isEqualTo("Idempotent body");
            assertThat(result2.getBody()).isEqualTo("Idempotent body");
            assertThat(result3.getBody()).isEqualTo("Idempotent body");

            // Only one record should exist in the database
            assertThat(commentRepository.count()).isEqualTo(1);
        }
    }

    @Nested
    class DeleteMethod {

        @Test
        void shouldDeleteCommentAndPublishDeletedEvent() {
            // Create comment to delete
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "To be deleted");
            processor.process(dto, testDiscussion, createContext());
            eventListener.clear();

            // Delete the comment
            processor.processDeleted(dto, createContext());

            // Verify comment is deleted
            assertThat(commentRepository.findByNativeIdAndProviderId(TEST_COMMENT_ID, githubProviderId()))
                    .isEmpty();

            // Verify DiscussionCommentDeleted event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentDeleted.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.commentId()).isEqualTo(TEST_COMMENT_ID);
                        assertThat(event.discussionId()).isEqualTo(testDiscussion.getId());
                    });
        }

        @Test
        @Transactional
        void shouldSyncBidirectionalRelationshipWhenDeleting() {
            // Create comment with bidirectional relationship set up
            DiscussionComment existing = new DiscussionComment();
            existing.setNativeId(TEST_COMMENT_ID);
            existing.setProvider(githubProvider);
            existing.setBody("Comment to test bidirectional sync");
            existing.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-"
                    + TEST_COMMENT_ID);
            existing.setCreatedAt(Instant.now());
            existing.setDiscussion(testDiscussion);
            commentRepository.save(existing);

            // Load the discussion and initialize its comments collection (simulates
            // real-world scenario where parent is in persistence context)
            Discussion loadedDiscussion =
                    discussionRepository.findById(testDiscussion.getId()).orElseThrow();
            loadedDiscussion.getComments().size(); // Force lazy initialization

            // Delete should work without TransientObjectException
            // because the processor syncs bidirectional relationship
            GitHubDiscussionCommentDTO dto = createCommentDTO(TEST_COMMENT_ID, "Comment to test bidirectional sync");
            assertThatCode(() -> processor.processDeleted(dto, createContext())).doesNotThrowAnyException();

            // Verify comment is deleted
            assertThat(commentRepository.findByNativeIdAndProviderId(TEST_COMMENT_ID, githubProviderId()))
                    .isEmpty();
        }

        @Test
        void shouldHandleNullCommentIdGracefully() {
            GitHubDiscussionCommentDTO dto = new GitHubDiscussionCommentDTO(
                    null, null, null, null, null, false, false, null, null, null, null, null, null);

            processor.processDeleted(dto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentDeleted.class))
                    .isEmpty();
        }

        @Test
        void shouldHandleNonExistentCommentGracefully() {
            GitHubDiscussionCommentDTO dto = createCommentDTO(999999L, "Non-existent");

            processor.processDeleted(dto, createContext());

            // No event should be published for non-existent comment
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentDeleted.class))
                    .isEmpty();
        }
    }

    @Nested
    class ReplyThreading {

        @Test
        void shouldResolveParentComment() {
            // Create parent comment
            Long parentId = TEST_COMMENT_ID;
            GitHubDiscussionCommentDTO parentDto = createCommentDTO(parentId, "Parent comment");
            DiscussionComment parent = processor.process(parentDto, testDiscussion, createContext());

            // Create reply comment
            Long replyId = TEST_COMMENT_ID + 1;
            GitHubDiscussionCommentDTO replyDto = new GitHubDiscussionCommentDTO(
                    replyId,
                    replyId,
                    "node_id_" + replyId,
                    "Reply comment",
                    "https://github.com/" + TEST_REPO_FULL_NAME + "/discussions/27#discussioncomment-" + replyId,
                    false,
                    false,
                    null,
                    "NONE",
                    Instant.now(),
                    Instant.now(),
                    new GitHubUserDTO(
                            101L,
                            101L,
                            "reply-user",
                            "https://avatars.githubusercontent.com/u/101",
                            "https://github.com/reply-user",
                            "Reply User",
                            null),
                    "node_id_" + parentId);
            DiscussionComment reply = processor.process(replyDto, testDiscussion, createContext());
            assertNotNull(reply);

            // Resolve parent
            processor.resolveParentComment(reply, parent);

            // Verify parent was set (wrap in transaction to avoid LazyInitializationException)
            transactionTemplate.executeWithoutResult(status -> {
                DiscussionComment savedReply = commentRepository
                        .findByNativeIdAndProviderId(replyId, githubProviderId())
                        .orElseThrow();
                assertThat(savedReply.getParentComment()).isNotNull();
                assertThat(savedReply.getParentComment().getNativeId()).isEqualTo(parentId);
            });
        }
    }

    private Long githubProviderId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}
