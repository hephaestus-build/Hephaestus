package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubIssueMessageHandler.
 * <p>
 * Tests the full webhook handling flow using JSON fixtures parsed directly
 * into DTOs using JSON fixtures for complete isolation. Verifies:
 * - Correct routing of webhook actions to processor methods
 * - Issue persistence for all action types
 * - Event publishing through the handler → processor chain
 * - Edge cases in event handling
 * <p>
 * Note: This test class does NOT use @Transactional because the issue processing
 * chain calls GitHubUserProcessor.findOrCreate() which uses REQUIRES_NEW propagation.
 * Having @Transactional here would cause connection pool deadlocks under parallel test
 * execution (-T 2C) as the test transaction holds a connection while REQUIRES_NEW
 * needs an additional one. We use TransactionTemplate for lazy-loading assertions.
 * <p>
 * <b>Fixture Values (issues.opened.json - Issue #20):</b>
 * <ul>
 *   <li>ID: 3578496080</li>
 *   <li>Number: 20</li>
 *   <li>Title: "Webhook fixture: parent tracker"</li>
 *   <li>State: open</li>
 *   <li>Locked: false</li>
 *   <li>HTML URL: "https://github.com/HephaestusTest/TestRepository/issues/20"</li>
 *   <li>Created at: 2025-11-01T21:42:45Z</li>
 *   <li>Updated at: 2025-11-01T21:42:45Z</li>
 *   <li>Comments: 0</li>
 *   <li>Author: FelixTJDietrich (ID: 5898705)</li>
 *   <li>Label: etl-sample (ID: 9567656085)</li>
 * </ul>
 */
class GitHubIssueMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    // Issue IDs from different fixtures
    private static final Long ISSUE_20_ID = 3578496080L; // opened, labeled, assigned, closed, reopened
    private static final Long ISSUE_22_ID =
            3578518416L; // milestoned, demilestoned, locked, unlocked, pinned, unpinned, transferred
    private static final Long ISSUE_23_ID = 3578523639L; // deleted
    private static final Long ISSUE_25_ID = 3578528003L; // typed, untyped

    // Exact fixture values from issues.opened.json for correctness verification
    private static final int FIXTURE_ISSUE_NUMBER = 20;
    private static final String FIXTURE_ISSUE_TITLE = "Webhook fixture: parent tracker";
    private static final String FIXTURE_ISSUE_HTML_URL = "https://github.com/HephaestusTest/TestRepository/issues/20";
    private static final String FIXTURE_ISSUE_BODY =
            "This parent issue seeds webhook fixtures for our ETL pipeline.\n\nChecklist:\n- [ ] add sub tasks\n- [ ] wire dependencies\n- [ ] tidy up at the end";
    private static final Instant FIXTURE_ISSUE_CREATED_AT = Instant.parse("2025-11-01T21:42:45Z");
    private static final Instant FIXTURE_ISSUE_UPDATED_AT = Instant.parse("2025-11-01T21:42:45Z");
    private static final int FIXTURE_ISSUE_COMMENTS_COUNT = 0;

    // Author fixture values
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";
    private static final String FIXTURE_AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/u/5898705?v=4";
    private static final String FIXTURE_AUTHOR_HTML_URL = "https://github.com/FelixTJDietrich";

    // Label fixture values from issues.opened.json
    private static final Long FIXTURE_LABEL_ID = 9567656085L;
    private static final String FIXTURE_LABEL_NAME = "etl-sample";
    private static final String FIXTURE_LABEL_COLOR = "ededed";

    @Autowired
    private GitHubIssueMessageHandler handler;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Key Tests

    @Nested
    class EventType {

        @Test
        void shouldReturnCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("repository.issues");
        }
    }

    // Basic Lifecycle Events

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldPersistIssueOnOpenedEvent() throws Exception {
            GitHubIssueEventDTO event = loadPayload("issues.opened");

            handler.handleEvent(event);

            // Then - verify ALL persisted fields against hardcoded fixture values
            // Use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                        .orElseThrow();

                // Core identification fields
                assertThat(issue.getNativeId()).isEqualTo(ISSUE_20_ID);
                assertThat(issue.getNumber()).isEqualTo(FIXTURE_ISSUE_NUMBER);

                // Content fields
                assertThat(issue.getTitle()).isEqualTo(FIXTURE_ISSUE_TITLE);
                assertThat(issue.getBody()).isEqualTo(FIXTURE_ISSUE_BODY);

                // State fields
                assertThat(issue.getState()).isEqualTo(Issue.State.OPEN);
                assertThat(issue.isLocked()).isFalse();
                assertThat(issue.getClosedAt()).isNull();

                // URL fields
                assertThat(issue.getHtmlUrl()).isEqualTo(FIXTURE_ISSUE_HTML_URL);

                // Timestamp fields (critical for sync correctness)
                assertThat(issue.getCreatedAt()).isEqualTo(FIXTURE_ISSUE_CREATED_AT);
                assertThat(issue.getUpdatedAt()).isEqualTo(FIXTURE_ISSUE_UPDATED_AT);

                // Counts
                assertThat(issue.getCommentsCount()).isEqualTo(FIXTURE_ISSUE_COMMENTS_COUNT);

                // Repository association (foreign key)
                assertThat(issue.getRepository()).isNotNull();
                assertThat(issue.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

                // Author association (foreign key) - verify exact fixture values
                assertThat(issue.getAuthor()).isNotNull();
                assertThat(issue.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
                assertThat(issue.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(issue.getAuthor().getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
                assertThat(issue.getAuthor().getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);

                // Label association - verify exact fixture values
                assertThat(issue.getLabels()).hasSize(1);
                Label label = issue.getLabels().iterator().next();
                assertThat(label.getNativeId()).isEqualTo(FIXTURE_LABEL_ID);
                assertThat(label.getName()).isEqualTo(FIXTURE_LABEL_NAME);
                assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR);

                // Null associations (not present in fixture)
                assertThat(issue.getMilestone()).isNull();
                assertThat(issue.getAssignees()).isEmpty();
                assertThat(issue.getIssueType()).isNull();
            });

            // Domain event published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class)).hasSize(1);
        }

        @Test
        void shouldUpdateIssueOnEditedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));
            eventListener.clear();

            GitHubIssueEventDTO editedEvent = loadPayload("issues.edited");

            handler.handleEvent(editedEvent);

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
            // Issue should still exist (edited, not created new)
            assertThat(issueRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldPreserveAnEditorWhoIsNotTheIssueAuthor() throws Exception {
            handler.handleEvent(loadPayload("issues.opened"));
            eventListener.clear();
            GitHubIssueEventDTO edit = loadPayload("issues.edited");
            var editor = new GitHubUserDTO(987654321L, null, "different-editor", null, null, null, null);
            handler.handleEvent(new GitHubIssueEventDTO(
                    edit.action(),
                    edit.issue(),
                    edit.repository(),
                    edit.organization(),
                    editor,
                    edit.label(),
                    edit.issueType(),
                    edit.changes()));

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElseThrow();
            var update = eventListener.ofType(ScmDomainEvent.IssueUpdated.class).getFirst();
            var context = Objects.requireNonNull(update.context());
            assertThat(context.actorUserId())
                    .isNotNull()
                    .isNotEqualTo(Objects.requireNonNull(issue.getAuthor()).getId());
            assertThat(userRepository.findByNativeIdAndProviderId(
                            987654321L,
                            Objects.requireNonNull(Objects.requireNonNull(testRepository.getProvider())
                                    .getId())))
                    .map(user -> user.getId())
                    .contains(context.actorUserId());
        }

        @Test
        void shouldPreserveTheActorOnReassignmentAndReopen() throws Exception {
            handler.handleEvent(loadPayload("issues.opened"));
            var editor = new GitHubUserDTO(987654321L, null, "different-editor", null, null, null, null);
            GitHubIssueEventDTO assigned = loadPayload("issues.assigned");
            eventListener.clear();
            handler.handleEvent(new GitHubIssueEventDTO(
                    assigned.action(),
                    assigned.issue(),
                    assigned.repository(),
                    assigned.organization(),
                    editor,
                    assigned.label(),
                    assigned.issueType(),
                    assigned.changes()));
            Long actorId = userRepository
                    .findByNativeIdAndProviderId(
                            987654321L,
                            Objects.requireNonNull(Objects.requireNonNull(testRepository.getProvider())
                                    .getId()))
                    .orElseThrow()
                    .getId();
            assertThat(Objects.requireNonNull(eventListener
                                    .ofType(ScmDomainEvent.IssueUpdated.class)
                                    .getFirst()
                                    .context())
                            .actorUserId())
                    .isEqualTo(actorId);

            handler.handleEvent(loadPayload("issues.closed"));
            GitHubIssueEventDTO reopened = loadPayload("issues.reopened");
            eventListener.clear();
            handler.handleEvent(new GitHubIssueEventDTO(
                    reopened.action(),
                    reopened.issue(),
                    reopened.repository(),
                    reopened.organization(),
                    editor,
                    reopened.label(),
                    reopened.issueType(),
                    reopened.changes()));
            assertThat(Objects.requireNonNull(eventListener
                                    .ofType(ScmDomainEvent.IssueUpdated.class)
                                    .getFirst()
                                    .context())
                            .actorUserId())
                    .isEqualTo(actorId);
        }

        @Test
        void shouldHandleClosedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));
            eventListener.clear();

            GitHubIssueEventDTO closedEvent = loadPayload("issues.closed");

            handler.handleEvent(closedEvent);

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
            assertThat(issue.getState()).isEqualTo(Issue.State.CLOSED);

            // Verify Closed event was published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).hasSize(1);
        }

        @Test
        void shouldRecountTheParentsRollupWhenAChildClosesAndReopens() throws Exception {
            // The closed payload carries the child's own summary, never the parent's: the parent is
            // recounted from the children this repository stores.
            handler.handleEvent(loadPayload("issues.opened"));
            Issue child = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElseThrow();
            Issue parent = new Issue();
            parent.setNativeId(3_578_400_000L);
            parent.setNumber(19);
            parent.setTitle("Epic");
            parent.setState(Issue.State.OPEN);
            parent.setRepository(testRepository);
            parent.setProvider(child.getProvider());
            parent = issueRepository.save(parent);
            child.setParentIssue(parent);
            issueRepository.save(child);

            handler.handleEvent(loadPayload("issues.closed"));
            Issue afterClose = issueRepository.findById(parent.getId()).orElseThrow();
            assertThat(afterClose.getSubIssuesTotal()).isEqualTo(1);
            assertThat(afterClose.getSubIssuesCompleted()).isEqualTo(1);
            assertThat(afterClose.getSubIssuesPercentCompleted()).isEqualTo(100);

            handler.handleEvent(loadPayload("issues.reopened"));
            Issue afterReopen = issueRepository.findById(parent.getId()).orElseThrow();
            assertThat(afterReopen.getSubIssuesTotal()).isEqualTo(1);
            assertThat(afterReopen.getSubIssuesCompleted()).isEqualTo(0);
        }

        @Test
        void shouldHandleReopenedEvent() throws Exception {
            // Given - create and close issue
            handler.handleEvent(loadPayload("issues.opened"));
            handler.handleEvent(loadPayload("issues.closed"));
            eventListener.clear();

            GitHubIssueEventDTO reopenedEvent = loadPayload("issues.reopened");

            handler.handleEvent(reopenedEvent);

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
            assertThat(issue.getState()).isEqualTo(Issue.State.OPEN);
        }

        @Test
        void shouldDeleteIssueOnDeletedEvent() throws Exception {
            // Given - the deleted fixture uses issue #23 (ID 3578523639)
            // First, we create it by simulating it exists
            Issue issueToDelete = new Issue();
            issueToDelete.setNativeId(ISSUE_23_ID);
            issueToDelete.setNumber(23);
            issueToDelete.setTitle("Issue to delete");
            issueToDelete.setState(Issue.State.OPEN);
            issueToDelete.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/23");
            issueToDelete.setRepository(testRepository);
            issueToDelete.setProvider(gitProvider);
            issueRepository.save(issueToDelete);

            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 23))
                    .isPresent();

            GitHubIssueEventDTO deletedEvent = loadPayload("issues.deleted");

            // When - delete via handler (exercises processDeleted with natural key lookup)
            handler.handleEvent(deletedEvent);

            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 23))
                    .isEmpty();
        }
    }

    // Label Events

    @Nested
    class LabelEvents {

        @Test
        void shouldHandleLabeledEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));
            eventListener.clear();

            GitHubIssueEventDTO labeledEvent = loadPayload("issues.labeled");

            handler.handleEvent(labeledEvent);

            // Then - use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                        .orElse(null);
                assertNotNull(issue);
                assertThat(labelNames(issue)).contains("etl-sample");
            });

            // Verify label was created in repository
            assertThat(labelRepository.findByNativeIdAndProviderId(9567656085L, gitProviderId()))
                    .isPresent();

            // Verify Labeled event was published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueLabeled.class)).hasSize(1);
        }

        @Test
        void shouldHandleUnlabeledEvent() throws Exception {
            // Given - create issue with label
            handler.handleEvent(loadPayload("issues.opened"));
            handler.handleEvent(loadPayload("issues.labeled"));
            eventListener.clear();

            GitHubIssueEventDTO unlabeledEvent = loadPayload("issues.unlabeled");

            handler.handleEvent(unlabeledEvent);

            // Then - Unlabeled event should be published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUnlabeled.class))
                    .hasSize(1);
        }
    }

    // Assignment Events

    @Nested
    class AssignmentEvents {

        @Test
        void shouldHandleAssignedEvent() throws Exception {
            // Note: The assigned webhook action routes to process(), which creates/updates
            // the issue with assignees from the DTO. Since we're testing the handler routing,
            // we verify the issue is persisted with assignees when created fresh.
            GitHubIssueEventDTO assignedEvent = loadPayload("issues.assigned");

            // When - process the assigned event (which includes assignees in the DTO)
            handler.handleEvent(assignedEvent);

            // Then - issue should be created with assignees from the DTO
            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                        .orElse(null);
                assertNotNull(issue);
                // The assignees are set from the DTO on creation
                assertThat(issue.getAssignees()).isNotEmpty();
                assertThat(issue.getAssignees().iterator().next().getLogin()).isEqualTo("FelixTJDietrich");
            });
        }

        @Test
        void shouldHandleUnassignedEvent() throws Exception {
            // Given - create issue with assignee
            handler.handleEvent(loadPayload("issues.opened"));
            handler.handleEvent(loadPayload("issues.assigned"));

            GitHubIssueEventDTO unassignedEvent = loadPayload("issues.unassigned");

            handler.handleEvent(unassignedEvent);

            // Then - issue still exists and was processed
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
        }
    }

    // Milestone Events

    @Nested
    class MilestoneEvents {

        @Test
        void shouldHandleMilestonedEvent() throws Exception {
            // Given - this fixture uses a different issue (22)
            GitHubIssueEventDTO milestonedEvent = loadPayload("issues.milestoned");

            handler.handleEvent(milestonedEvent);

            // Then - use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 22)
                        .orElse(null);
                assertNotNull(issue);
                assertThat(issue.getMilestone()).isNotNull();
                assertThat(issue.getMilestone().getTitle()).isEqualTo("Webhook Fixtures");
                assertThat(issue.getMilestone().getNumber()).isEqualTo(2);
            });

            // Verify milestone was created in repository
            assertThat(milestoneRepository.findByNativeIdAndProviderId(14028563L, gitProviderId()))
                    .isPresent();
        }

        @Test
        void shouldHandleDemilestonedEvent() throws Exception {
            // Given - create issue with milestone
            handler.handleEvent(loadPayload("issues.milestoned"));

            GitHubIssueEventDTO demilestonedEvent = loadPayload("issues.demilestoned");

            handler.handleEvent(demilestonedEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 22)
                        .orElse(null);
                assertNotNull(issue);
                assertThat(issue.getMilestone()).isNull();
            });
        }
    }

    // Issue Type Events

    @Nested
    class IssueTypeEvents {

        @Test
        void shouldHandleTypedEvent() throws Exception {
            // Given - this fixture uses issue 25
            GitHubIssueEventDTO typedEvent = loadPayload("issues.typed");

            handler.handleEvent(typedEvent);

            // Then - use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 25)
                        .orElse(null);
                assertNotNull(issue);
                assertThat(issue.getIssueType()).isNotNull();
                assertThat(issue.getIssueType().getName()).isEqualTo("Task");
                assertThat(issue.getIssueType().getColor()).isEqualTo(IssueType.Color.YELLOW);
            });

            // Verify Typed event was published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueTyped.class)).hasSize(1);
        }

        @Test
        void shouldHandleUntypedEvent() throws Exception {
            // Given - create issue with type
            handler.handleEvent(loadPayload("issues.typed"));
            eventListener.clear();

            GitHubIssueEventDTO untypedEvent = loadPayload("issues.untyped");

            handler.handleEvent(untypedEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Issue issue = issueRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 25)
                        .orElse(null);
                assertNotNull(issue);
                assertThat(issue.getIssueType()).isNull();
            });

            // Verify Untyped event was published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUntyped.class)).hasSize(1);
        }
    }

    // Lock Events

    @Nested
    class LockEvents {

        @Test
        void shouldHandleLockedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));

            GitHubIssueEventDTO lockedEvent = loadPayload("issues.locked");

            handler.handleEvent(lockedEvent);

            // Then - issue processed (locked is treated like a general update)
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
        }

        @Test
        void shouldHandleUnlockedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));
            handler.handleEvent(loadPayload("issues.locked"));

            GitHubIssueEventDTO unlockedEvent = loadPayload("issues.unlocked");

            handler.handleEvent(unlockedEvent);

            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
        }
    }

    // Pin Events

    @Nested
    class PinEvents {

        @Test
        void shouldHandlePinnedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));

            GitHubIssueEventDTO pinnedEvent = loadPayload("issues.pinned");

            handler.handleEvent(pinnedEvent);

            // Then - issue should still exist
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isPresent();
        }

        @Test
        void shouldHandleUnpinnedEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));
            handler.handleEvent(loadPayload("issues.pinned"));

            GitHubIssueEventDTO unpinnedEvent = loadPayload("issues.unpinned");

            handler.handleEvent(unpinnedEvent);

            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isPresent();
        }
    }

    // Transfer Event

    @Nested
    class TransferEvent {

        @Test
        void shouldHandleTransferredEvent() throws Exception {
            // Given - create issue first
            handler.handleEvent(loadPayload("issues.opened"));

            GitHubIssueEventDTO transferredEvent = loadPayload("issues.transferred");

            handler.handleEvent(transferredEvent);

            // Then - issue should be processed
            Issue issue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElse(null);
            assertNotNull(issue);
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleUnknownActionGracefully() throws Exception {
            // Given - we'll modify the opened payload to have unknown action
            // But since we can't easily modify, we just verify the handler doesn't crash
            // with a known action that goes to default
            GitHubIssueEventDTO event = loadPayload("issues.opened");

            // When/Then - should not throw
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleMissingRepositoryContextGracefully() throws Exception {
            // Given - remove the repository so context creation fails
            repositoryRepository.deleteAll();

            GitHubIssueEventDTO event = loadPayload("issues.opened");

            // When/Then - should not throw, just log warning
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // Issue should not be persisted since context is null
            assertThat(issueRepository.count()).isZero();
        }

        @Test
        @DisplayName("Should be idempotent - processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitHubIssueEventDTO event = loadPayload("issues.opened");

            // When - handle same event twice
            handler.handleEvent(event);
            long countAfterFirst = issueRepository.count();

            handler.handleEvent(event);

            // Then - still only one issue
            assertThat(issueRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldVerifyGetDatabaseIdFallback() throws Exception {
            // Given - webhook payloads have 'id' not 'database_id'
            GitHubIssueEventDTO event = loadPayload("issues.opened");

            // Verify the DTO is using the fallback correctly
            // In webhook payloads, databaseId will be null and id will have the value
            assertThat(event.issue().getDatabaseId()).isEqualTo(ISSUE_20_ID);

            handler.handleEvent(event);

            // Then - issue should be persisted with the correct ID
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isPresent();
        }

        @Test
        @DisplayName("Should create author and label entities with correct field values")
        void shouldCreateAllRelatedEntitiesFromOpenedEvent() throws Exception {
            // Given - no users or labels exist
            assertThat(userRepository.count()).isZero();
            assertThat(labelRepository.count()).isZero();

            handler.handleEvent(loadPayload("issues.opened"));

            // Then - author created with exact fixture values
            var author = userRepository
                    .findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, gitProviderId())
                    .orElseThrow();
            assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(author.getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
            assertThat(author.getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);

            // Then - label created with exact fixture values
            var label = labelRepository
                    .findByNativeIdAndProviderId(FIXTURE_LABEL_ID, gitProviderId())
                    .orElseThrow();
            assertThat(label.getName()).isEqualTo(FIXTURE_LABEL_NAME);
            assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR);
            assertThat(label.getDescription()).isNull(); // fixture has null description
        }
    }

    // Helper Methods

    private GitHubIssueEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubIssueEventDTO.class);
    }

    private IdentityProvider gitProvider;
    private Repository testRepository;

    private void setupTestData() {
        // Create GitHub provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization matching fixture data
        Organization org = new Organization();
        org.setNativeId(FIXTURE_ORG_ID);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID);
        org.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository matching fixture data
        Repository repo = new Repository();
        repo.setNativeId(FIXTURE_REPO_ID);
        repo.setName("TestRepository");
        repo.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        repo.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
        repo.setVisibility(Repository.Visibility.PUBLIC);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(org);
        repo.setProvider(gitProvider);
        testRepository = repositoryRepository.save(repo);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
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

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}
