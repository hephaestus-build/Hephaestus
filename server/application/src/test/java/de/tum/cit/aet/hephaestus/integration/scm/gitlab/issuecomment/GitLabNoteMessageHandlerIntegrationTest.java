package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Integration tests: JSON fixtures → DTO → handler → processor → DB. */
@Tag("integration")
@DisplayName("GitLab Note Message Handler")
class GitLabNoteMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Native IDs from fixtures
    private static final long NATIVE_NOTE_ID = 4406174L;
    private static final long NATIVE_ISSUE_ID = 422296L;
    private static final int ISSUE_IID = 5;
    private static final long NATIVE_USER_ID = 18024L;
    private static final long NATIVE_MR_NOTE_ID = 4406178L;
    private static final long NATIVE_MR_ID = 334047L;
    private static final int MR_IID = 2;

    // Fixture values
    private static final String FIXTURE_NOTE_BODY = "I'll start working on this feature";
    private static final String FIXTURE_NOTE_UPDATED_BODY =
            "Updated: I'll start working on this feature - high priority\\!";
    private static final String FIXTURE_MR_NOTE_BODY =
            "LGTM\\! Just a minor suggestion: consider adding error handling.";
    private static final String FIXTURE_MR_NOTE_UPDATED_BODY =
            "Updated: Consider adding error handling here. Also add input validation.";
    private static final String FIXTURE_NOTE_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5#note_4406174";
    private static final String FIXTURE_AUTHOR_LOGIN = "ga84xah";

    // Repository/org setup
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabNoteMessageHandler handler;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository savedRepo;
    private IdentityProvider savedProvider;
    private Issue savedIssue;
    private PullRequest savedPr;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Type

    @Test
    void returnsCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("note");
    }

    // Issue Notes

    @Nested
    class IssueNotes {

        @Test
        void shouldCreateCommentFromIssueNote() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_NOTE_BODY);
                assertThat(comment.getHtmlUrl()).isEqualTo(FIXTURE_NOTE_URL);
                assertNotNull(comment.getIssue());
                assertThat(comment.getIssue().getId()).isEqualTo(savedIssue.getId());
                assertThat(comment.getAuthor()).isNotNull();
                assertThat(comment.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(comment.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateCommentOnIssueNoteUpdate() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));
            eventListener.clear();

            handler.handleEvent(loadPayload("note.issue.update"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_NOTE_UPDATED_BODY);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .hasSize(1);
        }
    }

    // MR Notes

    @Nested
    class MergeRequestNotes {

        @Test
        void shouldCreateCommentFromMrNote() throws Exception {
            handler.handleEvent(loadPayload("note.mergerequest.create"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_MR_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_MR_NOTE_BODY);
                assertNotNull(comment.getIssue());
                assertThat(comment.getIssue().getId()).isEqualTo(savedPr.getId());
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateCommentFromMrNoteUpdate() throws Exception {
            handler.handleEvent(loadPayload("note.mergerequest.create"));
            eventListener.clear();

            handler.handleEvent(loadPayload("note.mergerequest.update"));

            transactionTemplate.executeWithoutResult(status -> {
                List<IssueComment> comments = commentRepository.findAll();
                assertThat(comments).hasSize(1);

                IssueComment comment = comments.get(0);
                assertThat(comment.getNativeId()).isEqualTo(NATIVE_MR_NOTE_ID);
                assertThat(comment.getBody()).isEqualTo(FIXTURE_MR_NOTE_UPDATED_BODY);
            });

            assertThat(eventListener.ofType(ScmDomainEvent.CommentUpdated.class))
                    .hasSize(1);
        }
    }

    // System Notes

    @Nested
    class SystemNotes {

        @Test
        void shouldSkipSystemNote() throws Exception {
            handler.handleEvent(loadPayload("note.system"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    // Confidential Notes

    @Nested
    class ConfidentialNotes {

        @Test
        void shouldSkipConfidentialNote() throws Exception {
            handler.handleEvent(loadPayload("note.confidential.issue.create"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabNoteEventDTO event = loadPayload("note.issue.create");
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
            assertThat(commentRepository.count()).isZero();
        }

        @Test
        @DisplayName("is idempotent — same event twice creates one comment")
        void shouldBeIdempotent() throws Exception {
            handler.handleEvent(loadPayload("note.issue.create"));
            long countAfterFirst = commentRepository.count();

            handler.handleEvent(loadPayload("note.issue.create"));

            assertThat(commentRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldCreateStubIssueWhenParentMissing() throws Exception {
            // Delete the pre-created issue
            commentRepository.deleteAll();
            issueRepository.deleteAll();

            handler.handleEvent(loadPayload("note.issue.create"));

            transactionTemplate.executeWithoutResult(status -> {
                // Should have created a stub issue AND the comment
                assertThat(issueRepository.count()).isEqualTo(1);
                assertThat(commentRepository.count()).isEqualTo(1);

                Issue stubIssue = issueRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                        .orElse(null);
                assertThat(stubIssue).isNotNull();
                assertThat(stubIssue.getNativeId()).isEqualTo(NATIVE_ISSUE_ID);
            });
        }

        @Test
        void shouldSkipCommitNote() throws Exception {
            handler.handleEvent(loadPayload("note.commit.create"));

            assertThat(commentRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.CommentCreated.class))
                    .isEmpty();
        }
    }

    // Helpers

    private GitLabNoteEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("gitlab/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabNoteEventDTO.class);
    }

    private void setupTestData() {
        savedProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));

        Organization org = new Organization();
        org.setNativeId(1L);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("HephaestusTest");
        org.setAvatarUrl("");
        org.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        org.setProvider(savedProvider);
        org = organizationRepository.save(org);

        Repository repo = new Repository();
        repo.setNativeId(246765L);
        repo.setName("demo-repository");
        repo.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        repo.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repo.setVisibility(Repository.Visibility.PRIVATE);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(org);
        repo.setProvider(savedProvider);
        savedRepo = repositoryRepository.save(repo);

        // Pre-create Issue (IID 5) — parent for issue notes
        Issue issue = new Issue();
        issue.setNativeId(NATIVE_ISSUE_ID);
        issue.setProvider(savedProvider);
        issue.setNumber(ISSUE_IID);
        issue.setTitle("Feature: Add user authentication");
        issue.setBody("Implement OAuth2 authentication flow");
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5");
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setRepository(savedRepo);
        savedIssue = issueRepository.save(issue);

        // Pre-create PullRequest (IID 2) — parent for MR notes
        PullRequest pr = new PullRequest();
        pr.setNativeId(NATIVE_MR_ID);
        pr.setProvider(savedProvider);
        pr.setNumber(MR_IID);
        pr.setTitle("Implement OAuth authentication");
        pr.setBody("This MR implements OAuth2 authentication.\n\nCloses #5");
        pr.setState(Issue.State.OPEN);
        pr.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository/-/merge_requests/2");
        pr.setMerged(false);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);
        pr.setHeadRefName("feature/oauth");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("a".repeat(40));
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setRepository(savedRepo);
        savedPr = pullRequestRepository.save(pr);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test-gitlab");
        workspace.setDisplayName("HephaestusTest GitLab");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }
}
