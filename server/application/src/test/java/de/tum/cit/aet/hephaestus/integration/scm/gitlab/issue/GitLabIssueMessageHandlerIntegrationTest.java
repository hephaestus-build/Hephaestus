package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitLabIssueMessageHandler.
 * <p>
 * Tests the full webhook handling flow: JSON fixtures → DTO → handler → processor → DB.
 * <p>
 * <b>Fixture values (issue.open.json — Issue IID #5):</b>
 * <ul>
 *   <li>Native ID: 422296 (stored as nativeId; synthetic auto-generated PK for id)</li>
 *   <li>IID: 5</li>
 *   <li>Title: "Feature: Add user authentication"</li>
 *   <li>State: opened → OPEN</li>
 *   <li>Author: ga84xah (native ID 18024)</li>
 *   <li>Label: enhancement (native ID 85907)</li>
 *   <li>Provider: GITLAB</li>
 * </ul>
 * <p>
 * Note: Does NOT use @Transactional (see GitHubIssueMessageHandlerIntegrationTest for rationale).
 */
@Tag("integration")
@DisplayName("GitLab Issue Message Handler")
class GitLabIssueMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Native IDs from GitLab fixtures (positive, raw values)
    private static final long NATIVE_ISSUE_ID = 422296L;
    private static final int ISSUE_IID = 5;
    private static final long NATIVE_USER_ID = 18024L;
    private static final long NATIVE_LABEL_ID = 85907L;

    // Fixture values
    private static final String FIXTURE_ISSUE_TITLE = "Feature: Add user authentication";
    private static final String FIXTURE_ISSUE_BODY = "Implement OAuth2 authentication flow";
    private static final String FIXTURE_ISSUE_HTML_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5";
    private static final String FIXTURE_AUTHOR_LOGIN = "ga84xah";
    private static final String FIXTURE_LABEL_NAME = "enhancement";
    private static final String FIXTURE_LABEL_COLOR = "#a2eeef";

    // Repository/org setup
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabIssueMessageHandler handler;

    @Autowired
    private GitLabIssueProcessor processor;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private LabelRepository labelRepository;

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

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Type

    @Nested
    class EventType {

        @Test
        void returnsCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("issue");
        }
    }

    // Basic Lifecycle

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldAdvanceReviewSnapshotWhenSyncChangesAnOpenIssue() throws Exception {
            handler.handleEvent(loadPayload("issue.open"));
            Issue before = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
            var previousSnapshot = before.getReviewSnapshotId();
            eventListener.clear();

            processor.processFromSync(
                    new GitLabIssueProcessor.SyncIssueData(
                            "gid://gitlab/Issue/422296",
                            "5",
                            "Updated title",
                            FIXTURE_ISSUE_BODY,
                            "opened",
                            false,
                            FIXTURE_ISSUE_HTML_URL,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                            null,
                            null,
                            null,
                            null,
                            null),
                    savedRepo,
                    null);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class)).hasSize(1);
            Issue after = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
            assertThat(after.getTitle()).isEqualTo("Updated title");
            assertThat(after.getReviewSnapshotId()).isNotNull().isNotEqualTo(previousSnapshot);
        }

        @Test
        void shouldPersistIssueOnOpenEvent() throws Exception {
            GitLabIssueEventDTO event = loadPayload("issue.open");

            handler.handleEvent(event);

            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                        .orElseThrow();

                // Core fields
                assertThat(issue.getNativeId()).isEqualTo(NATIVE_ISSUE_ID);
                assertThat(issue.getNumber()).isEqualTo(ISSUE_IID);
                assertThat(issue.getTitle()).isEqualTo(FIXTURE_ISSUE_TITLE);
                assertThat(issue.getBody()).isEqualTo(FIXTURE_ISSUE_BODY);
                assertThat(issue.getState()).isEqualTo(Issue.State.OPEN);
                assertThat(issue.getHtmlUrl()).isEqualTo(FIXTURE_ISSUE_HTML_URL);

                // Provider
                assertThat(issue.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);

                // Timestamps
                assertThat(issue.getCreatedAt()).isNotNull();
                assertThat(issue.getUpdatedAt()).isNotNull();

                // Repository
                assertThat(issue.getRepository()).isNotNull();
                assertThat(issue.getRepository().getId()).isEqualTo(savedRepo.getId());

                // Author
                assertThat(issue.getAuthor()).isNotNull();
                assertThat(issue.getAuthor().getNativeId()).isEqualTo(NATIVE_USER_ID);
                assertThat(issue.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);

                // Labels
                assertThat(issue.getLabels()).hasSize(1);
                assertThat(issue.getLabels().iterator().next().getName()).isEqualTo(FIXTURE_LABEL_NAME);
            });

            // Domain event
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class)).hasSize(1);
        }

        @Test
        void shouldCloseIssueOnCloseEvent() throws Exception {
            // Create first
            handler.handleEvent(loadPayload("issue.open"));
            eventListener.clear();

            // Close
            handler.handleEvent(loadPayload("issue.close"));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
            assertThat(issue.getState()).isEqualTo(Issue.State.CLOSED);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).hasSize(1);
        }

        @Test
        void shouldReopenIssueOnReopenEvent() throws Exception {
            // Create and close
            handler.handleEvent(loadPayload("issue.open"));
            handler.handleEvent(loadPayload("issue.close"));
            eventListener.clear();

            // Reopen
            handler.handleEvent(loadPayload("issue.reopen"));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
            assertThat(issue.getState()).isEqualTo(Issue.State.OPEN);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueReopened.class)).hasSize(1);
        }

        @Test
        void shouldUpdateIssueOnUpdateEvent() throws Exception {
            // Create first
            handler.handleEvent(loadPayload("issue.open"));
            eventListener.clear();

            // Update
            handler.handleEvent(loadPayload("issue.update"));

            // Should still be one issue
            assertThat(issueRepository.count()).isEqualTo(1);
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
        }

        @Test
        void shouldPreserveAnEditorWhoIsNotTheIssueAuthor() throws Exception {
            handler.handleEvent(loadPayload("issue.open"));
            eventListener.clear();
            GitLabIssueEventDTO edit = loadPayload("issue.update");
            var editor = new GitLabWebhookUser(987654321L, "different-editor", "Different Editor", null, null);
            handler.handleEvent(new GitLabIssueEventDTO(
                    edit.objectKind(),
                    edit.eventType(),
                    editor,
                    edit.project(),
                    edit.objectAttributes(),
                    edit.labels(),
                    edit.assignees(),
                    edit.changes()));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
            var update = eventListener.ofType(ScmDomainEvent.IssueUpdated.class).getFirst();
            var context = Objects.requireNonNull(update.context());
            assertThat(context.actorUserId())
                    .isNotNull()
                    .isNotEqualTo(Objects.requireNonNull(issue.getAuthor()).getId());
            assertThat(userRepository.findByNativeIdAndProviderId(
                            987654321L, Objects.requireNonNull(savedProvider.getId())))
                    .map(user -> user.getId())
                    .contains(context.actorUserId());
        }

        @Test
        void shouldPreserveTheActorOnReopen() throws Exception {
            handler.handleEvent(loadPayload("issue.open"));
            handler.handleEvent(loadPayload("issue.close"));
            eventListener.clear();
            GitLabIssueEventDTO reopened = loadPayload("issue.reopen");
            var editor = new GitLabWebhookUser(987654321L, "different-editor", "Different Editor", null, null);
            handler.handleEvent(new GitLabIssueEventDTO(
                    reopened.objectKind(),
                    reopened.eventType(),
                    editor,
                    reopened.project(),
                    reopened.objectAttributes(),
                    reopened.labels(),
                    reopened.assignees(),
                    reopened.changes()));

            var context = Objects.requireNonNull(eventListener
                    .ofType(ScmDomainEvent.IssueUpdated.class)
                    .getFirst()
                    .context());
            assertThat(context.actorUserId())
                    .isEqualTo(userRepository
                            .findByNativeIdAndProviderId(987654321L, Objects.requireNonNull(savedProvider.getId()))
                            .orElseThrow()
                            .getId());
            assertThat(Objects.requireNonNull(issueRepository
                                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                                    .orElseThrow()
                                    .getAuthor())
                            .getId())
                    .isNotEqualTo(context.actorUserId());
        }
    }

    // Confidential Issues

    @Nested
    class ConfidentialIssues {

        @Test
        void shouldSkipConfidentialOpen() throws Exception {
            handler.handleEvent(loadPayload("issue.confidential.open"));

            assertThat(issueRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class)).isEmpty();
        }

        @Test
        void shouldSkipConfidentialUpdate() throws Exception {
            handler.handleEvent(loadPayload("issue.confidential.update"));

            assertThat(issueRepository.count()).isZero();
        }

        @Test
        void shouldSkipConfidentialClose() throws Exception {
            handler.handleEvent(loadPayload("issue.confidential.close"));

            assertThat(issueRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).isEmpty();
        }
    }

    // Author and Label Resolution

    @Nested
    class EntityResolution {

        @Test
        @DisplayName("creates author with native ID and GITLAB provider")
        void shouldCreateAuthorWithCorrectFields() throws Exception {
            assertThat(userRepository.count()).isZero();

            handler.handleEvent(loadPayload("issue.open"));

            // Wrap in transaction to avoid LazyInitializationException when accessing provider
            transactionTemplate.executeWithoutResult(status -> {
                var author = userRepository
                        .findByNativeIdAndProviderId(NATIVE_USER_ID, persistedId(savedProvider))
                        .orElseThrow();
                assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(author.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
                assertThat(author.getHtmlUrl()).isEqualTo("https://gitlab.lrz.de/ga84xah");
            });
        }

        @Test
        void shouldCreateLabelWithCorrectFields() throws Exception {
            handler.handleEvent(loadPayload("issue.open"));

            transactionTemplate.executeWithoutResult(status -> {
                var label = labelRepository
                        .findByRepositoryIdAndName(savedRepo.getId(), FIXTURE_LABEL_NAME)
                        .orElseThrow();
                assertThat(label.getName()).isEqualTo(FIXTURE_LABEL_NAME);
                assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR);
            });
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabIssueEventDTO event = loadPayload("issue.open");

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
            assertThat(issueRepository.count()).isZero();
        }

        @Test
        @DisplayName("is idempotent — processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitLabIssueEventDTO event = loadPayload("issue.open");

            handler.handleEvent(event);
            long countAfterFirst = issueRepository.count();

            handler.handleEvent(event);

            assertThat(issueRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldHandleFullLifecycle() throws Exception {
            handler.handleEvent(loadPayload("issue.open"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.OPEN);

            handler.handleEvent(loadPayload("issue.close"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.CLOSED);

            handler.handleEvent(loadPayload("issue.reopen"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.OPEN);

            handler.handleEvent(loadPayload("issue.update"));
            assertThat(issueRepository.findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID))
                    .isPresent();

            assertThat(issueRepository.count()).isEqualTo(1);
        }
    }

    // Helpers

    private GitLabIssueEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("gitlab/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabIssueEventDTO.class);
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

    private Set<String> labelNames(Issue issue) {
        return issue.getLabels().stream().map(l -> l.getName()).collect(Collectors.toSet());
    }

    private static long persistedId(IdentityProvider provider) {
        Long id = provider.getId();
        assertNotNull(id);
        return id;
    }
}
