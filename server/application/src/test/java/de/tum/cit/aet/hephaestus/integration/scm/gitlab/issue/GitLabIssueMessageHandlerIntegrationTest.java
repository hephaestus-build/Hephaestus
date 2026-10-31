package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueEvidenceVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmReviewFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteTestFixtures;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The webhook flow from recorded GitLab issue hooks to the database. */
@Tag("integration")
@DisplayName("GitLab Issue Message Handler")
class GitLabIssueMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConnectionService routeConnectionService;

    @Autowired
    private GitLabProjectAdmission routeAdmissionReads;

    // Native IDs from GitLab fixtures (positive, raw values)
    private static final long NATIVE_ISSUE_ID = 422296L;
    private static final int ISSUE_IID = 5;
    private static final long NATIVE_USER_ID = 90001L;

    // Fixture values
    private static final String FIXTURE_ISSUE_TITLE = "Feature: Add user authentication";
    private static final String FIXTURE_ISSUE_BODY = "Implement OAuth2 authentication flow";
    private static final String FIXTURE_ISSUE_HTML_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5";
    private static final String FIXTURE_AUTHOR_LOGIN = "ab12cde";
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
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryMonitors;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    @Autowired
    private IssueTypeRepository issueTypeRepository;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private IssueEvidenceVersion issueVersions;

    @Autowired
    private JdbcTemplate fixtureJdbc;

    @Autowired
    private ItemIndexService fixtureItems;

    @Autowired
    private PeopleRegistry fixturePeople;

    private Organization savedOrg;
    private Repository savedRepo;
    private Workspace savedWorkspace;
    private IdentityProvider savedProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    @Nested
    class EventType {

        @Test
        void returnsCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("issue");
        }
    }

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldReportACloseOnlyFromTheSyncThatStoresIt() {
            var closed = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/" + NATIVE_ISSUE_ID,
                    String.valueOf(ISSUE_IID),
                    FIXTURE_ISSUE_TITLE,
                    FIXTURE_ISSUE_BODY,
                    "closed",
                    false,
                    FIXTURE_ISSUE_HTML_URL,
                    "2026-01-31T17:00:00Z",
                    "2026-01-31T18:00:00Z",
                    "2026-01-31T18:00:00Z",
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
                    null);

            processor.processFromSync(closed, savedRepo, null);
            processor.processFromSync(closed, savedRepo, null);

            // Re-reading a closed issue is no event: its close is reported once.
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).hasSize(1);

            // Reopened and closed again between two syncs: the new close is a new event.
            processor.processFromSync(
                    new GitLabIssueProcessor.SyncIssueData(
                            closed.globalId(),
                            closed.iid(),
                            closed.title(),
                            closed.description(),
                            "closed",
                            false,
                            closed.webUrl(),
                            closed.createdAt(),
                            "2026-01-31T19:00:00Z",
                            "2026-01-31T19:00:00Z",
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

            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).hasSize(2);
        }

        @Test
        void shouldAdvanceTheContentVersionWhenSyncChangesAnOpenIssue() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            Issue before = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
            var previousVersion = issueVersions.of(ScmEventPayload.IssueData.from(before));
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
            assertThat(issueVersions.of(ScmEventPayload.IssueData.from(after))).isNotEqualTo(previousVersion);
        }

        @Test
        void shouldPersistIssueOnOpenEvent() throws Exception {
            GitLabIssueEventDTO event = loadPayload("issue.open");

            handleOnRoute(event);

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
            handleOnRoute(loadPayload("issue.open"));
            eventListener.clear();

            // Close
            handleOnRoute(loadPayload("issue.close"));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
            assertThat(issue.getState()).isEqualTo(Issue.State.CLOSED);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).hasSize(1);
        }

        @Test
        void shouldReopenIssueOnReopenEvent() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            handleOnRoute(loadPayload("issue.close"));
            eventListener.clear();

            // Reopen
            handleOnRoute(loadPayload("issue.reopen"));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
            assertThat(issue.getState()).isEqualTo(Issue.State.OPEN);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueReopened.class)).hasSize(1);
        }

        @Test
        void shouldKeepTheReopenedIssueOpenWhenTheCloseHookIsRedelivered() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            handleOnRoute(loadPayload("issue.close"));
            handleOnRoute(loadPayload("issue.reopen"));
            eventListener.clear();

            handleOnRoute(loadPayload("issue.close"));

            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.OPEN);
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).isEmpty();
        }

        @Test
        void shouldUpdateIssueOnUpdateEvent() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            eventListener.clear();

            // Update
            handleOnRoute(loadPayload("issue.update"));

            assertThat(issueRepository.count()).isEqualTo(1);
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElse(null);
            assertThat(issue).isNotNull();
        }

        @Test
        void shouldPreserveAnEditorWhoIsNotTheIssueAuthor() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            eventListener.clear();
            GitLabIssueEventDTO edit = loadPayload("issue.update");
            var editor = new GitLabWebhookUser(987654321L, "different-editor", "Different Editor", null, null);
            handleOnRoute(new GitLabIssueEventDTO(
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
            handleOnRoute(loadPayload("issue.open"));
            handleOnRoute(loadPayload("issue.close"));
            eventListener.clear();
            GitLabIssueEventDTO reopened = loadPayload("issue.reopen");
            var editor = new GitLabWebhookUser(987654321L, "different-editor", "Different Editor", null, null);
            handleOnRoute(new GitLabIssueEventDTO(
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

    @Nested
    class ReviewRevision {

        private static final String REVIEWED_BODY = "Split into subtasks: sign-in; token refresh; sign-out";

        @ParameterizedTest(name = "{0} change")
        @ValueSource(strings = {"content", "relationship"})
        void shouldKeepTheReviewCurrentUntilSyncBringsARealChange(String change) throws Exception {
            seedIssueType("Issue");
            handleOnRoute(loadPayload("issue.open"));
            handleOnRoute(edited(loadPayload("issue.open"), REVIEWED_BODY));
            Issue reviewed = currentIssue();
            String reviewedRevision = reviewRevision();
            UUID practiceResultId = recordResult(reviewed);
            eventListener.clear();

            processor.processFromSync(sync(REVIEWED_BODY, List.of(FIXTURE_LABEL_NAME)), savedRepo, null);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class)).isEmpty();
            assertThat(reviewRevision()).isEqualTo(reviewedRevision);
            assertThat(practiceResultRepository
                            .findById(practiceResultId)
                            .orElseThrow()
                            .getEndedAt())
                    .isNull();

            processor.processFromSync(
                    change.equals("content")
                            ? sync("Implement the whole flow at once", List.of(FIXTURE_LABEL_NAME))
                            : sync(REVIEWED_BODY, List.of()),
                    savedRepo,
                    null);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class)).hasSize(1);
            assertThat(reviewRevision()).isNotEqualTo(reviewedRevision);
            assertThat(practiceResultRepository
                            .findById(practiceResultId)
                            .orElseThrow()
                            .getEndedAt())
                    .isNotNull();
        }

        private GitLabIssueEventDTO edited(GitLabIssueEventDTO opened, String body) {
            var attrs = Objects.requireNonNull(opened.objectAttributes());
            return new GitLabIssueEventDTO(
                    opened.objectKind(),
                    opened.eventType(),
                    opened.user(),
                    opened.project(),
                    new GitLabIssueEventDTO.ObjectAttributes(
                            attrs.id(),
                            attrs.iid(),
                            attrs.title(),
                            body,
                            attrs.state(),
                            "update",
                            attrs.confidential(),
                            attrs.authorId(),
                            attrs.assigneeId(),
                            attrs.milestoneId(),
                            attrs.createdAt(),
                            attrs.updatedAt(),
                            attrs.closedAt(),
                            attrs.duplicatedToId(),
                            attrs.type(),
                            attrs.url()),
                    opened.labels(),
                    opened.assignees(),
                    new GitLabIssueEventDTO.Changes(
                            null, null, new GitLabIssueEventDTO.AttributeChange(), null, null, null));
        }

        private GitLabIssueProcessor.SyncIssueData sync(String body, List<String> labels) {
            return new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/" + NATIVE_ISSUE_ID,
                    String.valueOf(ISSUE_IID),
                    FIXTURE_ISSUE_TITLE,
                    body,
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
                    null,
                    0,
                    labels.stream()
                            .map(name -> new GitLabIssueProcessor.SyncLabelData(null, name, FIXTURE_LABEL_COLOR))
                            .toList(),
                    List.of(),
                    null,
                    "ISSUE",
                    null);
        }

        private void seedIssueType(String name) {
            IssueType type = new IssueType();
            type.setId("gid://gitlab/WorkItems::Type/1");
            type.setName(name);
            type.setColor(IssueType.Color.GRAY);
            type.setOrganization(savedOrg);
            type.setLastSyncAt(Instant.now());
            issueTypeRepository.save(type);
        }

        private Issue currentIssue() {
            return issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
        }

        /** The revision the capture fence compares a review against. */
        private String reviewRevision() {
            return Objects.requireNonNull(transactionTemplate.execute(status -> issueVersions
                    .of(ScmEventPayload.IssueData.from(currentIssue()))
                    .value()));
        }

        private UUID recordResult(Issue issue) {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                var current = issueRepository
                        .findByIdWithAuthorAndRepository(issue.getId())
                        .orElseThrow();
                return new ScmReviewFixtures(
                                fixtureJdbc, fixtureItems, fixturePeople, transactionTemplate, objectMapper)
                        .result(
                                savedWorkspace.getId(),
                                current,
                                issueVersions
                                        .of(ScmEventPayload.IssueData.from(current))
                                        .value(),
                                Instant.now())
                        .resultId();
            }));
        }
    }

    @Nested
    class ConfidentialIssues {

        @Test
        void shouldSkipConfidentialOpen() throws Exception {
            handleOnRoute(loadPayload("issue.confidential.open"));

            assertThat(issueRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class)).isEmpty();
        }

        @Test
        void shouldSkipConfidentialUpdate() throws Exception {
            handleOnRoute(loadPayload("issue.confidential.update"));

            assertThat(issueRepository.count()).isZero();
        }

        @Test
        void shouldSkipConfidentialClose() throws Exception {
            handleOnRoute(loadPayload("issue.confidential.close"));

            assertThat(issueRepository.count()).isZero();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).isEmpty();
        }
    }

    @Nested
    class EntityResolution {

        @Test
        @DisplayName("creates author with native ID and GITLAB provider")
        void shouldCreateAuthorWithCorrectFields() throws Exception {
            assertThat(userRepository.count()).isZero();

            handleOnRoute(loadPayload("issue.open"));

            // Wrap in transaction to avoid LazyInitializationException when accessing provider
            transactionTemplate.executeWithoutResult(status -> {
                var author = userRepository
                        .findByNativeIdAndProviderId(NATIVE_USER_ID, persistedId(savedProvider))
                        .orElseThrow();
                assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(author.getProvider().getType()).isEqualTo(IdentityProviderType.GITLAB);
                assertThat(author.getHtmlUrl()).isEqualTo("https://gitlab.lrz.de/ab12cde");
            });
        }

        @Test
        void shouldCreateLabelWithCorrectFields() throws Exception {
            handleOnRoute(loadPayload("issue.open"));

            transactionTemplate.executeWithoutResult(status -> {
                var label = labelRepository
                        .findByRepositoryIdAndName(savedRepo.getId(), FIXTURE_LABEL_NAME)
                        .orElseThrow();
                assertThat(label.getName()).isEqualTo(FIXTURE_LABEL_NAME);
                assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR);
            });
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabIssueEventDTO event = loadPayload("issue.open");

            assertThatCode(() -> handleOnRoute(event)).doesNotThrowAnyException();
            assertThat(issueRepository.count()).isZero();
        }

        @Test
        @DisplayName("is idempotent — processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitLabIssueEventDTO event = loadPayload("issue.open");

            handleOnRoute(event);
            long countAfterFirst = issueRepository.count();

            handleOnRoute(event);

            assertThat(issueRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldHandleFullLifecycle() throws Exception {
            handleOnRoute(loadPayload("issue.open"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.OPEN);

            handleOnRoute(loadPayload("issue.close"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.CLOSED);

            handleOnRoute(loadPayload("issue.reopen"));
            assertThat(issueRepository
                            .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Issue.State.OPEN);

            handleOnRoute(loadPayload("issue.update"));
            assertThat(issueRepository.findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID))
                    .isPresent();

            assertThat(issueRepository.count()).isEqualTo(1);
        }
    }

    /** GitLab leaves {@code assignees} out of an issue hook when there are none. */
    @Test
    void shouldUnassignEveryoneWhenTheHookLeavesTheAssigneesOut() throws Exception {
        handleOnRoute(loadPayload("issue.open"));
        transactionTemplate.executeWithoutResult(status -> {
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                    .orElseThrow();
            User assignee = new User();
            assignee.setNativeId(990_101L);
            assignee.setLogin("former-assignee");
            assignee.setName("Former Assignee");
            assignee.setAvatarUrl("");
            assignee.setHtmlUrl("https://gitlab.lrz.de/former-assignee");
            assignee.setType(User.Type.USER);
            assignee.setProvider(savedProvider);
            issue.getAssignees().add(userRepository.save(assignee));
            issueRepository.save(issue);
        });

        handleOnRoute(loadPayload("issue.update"));

        Boolean assigned = transactionTemplate.execute(status -> !issueRepository
                .findByRepositoryIdAndNumber(savedRepo.getId(), ISSUE_IID)
                .orElseThrow()
                .getAssignees()
                .isEmpty());
        assertThat(assigned).isFalse();
    }

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
        savedOrg = org;

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

        var workspace =
                WorkspaceTestFixtures.gitLabPatWorkspace(FIXTURE_ORG_LOGIN).withSlug("hephaestus-test-gitlab");
        workspace.build().setPublicActivityEnabled(true);
        savedWorkspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository, connectionRepository, workspace, "https://gitlab.lrz.de");
        repositoryMonitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(savedWorkspace, FIXTURE_REPO_FULL_NAME));
    }

    private static long persistedId(IdentityProvider provider) {
        Long id = provider.getId();
        assertNotNull(id);
        return id;
    }

    /** Delivers {@code event} on the connection route of the fixture workspace, as admission would. */
    private <T> @Nullable T onRoute(Object event, Supplier<@Nullable T> handling) {
        return new GitLabRouteTestFixtures.Deliveries(
                        connectionRepository,
                        routeConnectionService,
                        routeAdmissionReads,
                        objectMapper,
                        transactionTemplate,
                        "https://gitlab.lrz.de")
                .call(savedWorkspace, savedRepo, event, handling);
    }

    private void handleOnRoute(GitLabIssueEventDTO event) {
        // As dispatchEvent does in production: the handler runs in one transaction.
        onRoute(event, () -> {
            transactionTemplate.executeWithoutResult(status -> handler.handleEvent(event));
            return null;
        });
    }
}
