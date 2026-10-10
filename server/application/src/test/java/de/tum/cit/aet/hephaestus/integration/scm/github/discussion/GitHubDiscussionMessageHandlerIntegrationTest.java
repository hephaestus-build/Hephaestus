package de.tum.cit.aet.hephaestus.integration.scm.github.discussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionCategoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto.GitHubDiscussionEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * Note: This test class does NOT use @Transactional because the discussion processing
 * chain calls GitHubUserProcessor.findOrCreate() which uses REQUIRES_NEW propagation.
 * Having @Transactional here would cause connection pool deadlocks under parallel test
 * execution as the test transaction holds a connection while REQUIRES_NEW
 * needs an additional one. We use TransactionTemplate for lazy-loading assertions.
 * <p>
 * <b>Fixture Values (discussion.created.json - Discussion #27):</b>
 * <ul>
 *   <li>ID: 9096662</li>
 *   <li>Number: 27</li>
 *   <li>Title: "Fixture discussion thread"</li>
 *   <li>State: open</li>
 *   <li>Locked: false</li>
 *   <li>HTML URL: "https://github.com/HephaestusTest/TestRepository/discussions/27"</li>
 *   <li>Created at: 2025-11-01T23:15:45Z</li>
 *   <li>Updated at: 2025-11-01T23:15:45Z</li>
 *   <li>Comments: 0</li>
 *   <li>Author: FelixTJDietrich (ID: 5898705)</li>
 *   <li>Category: General (ID: "46489461")</li>
 * </ul>
 */
class GitHubDiscussionMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    // Discussion IDs from different fixtures
    private static final Long DISCUSSION_27_ID = 9096662L; // created, edited, closed, reopened, answered, etc.
    private static final Long DISCUSSION_28_ID = 9096674L; // deleted

    // Exact fixture values from discussion.created.json for correctness verification
    private static final int FIXTURE_DISCUSSION_NUMBER = 27;
    private static final String FIXTURE_DISCUSSION_TITLE = "Fixture discussion thread";
    private static final String FIXTURE_DISCUSSION_HTML_URL =
            "https://github.com/HephaestusTest/TestRepository/discussions/27";
    private static final String FIXTURE_DISCUSSION_BODY = "Testing discussion webhook payloads";
    private static final Instant FIXTURE_DISCUSSION_CREATED_AT = Instant.parse("2025-11-01T23:15:45Z");
    private static final Instant FIXTURE_DISCUSSION_UPDATED_AT = Instant.parse("2025-11-01T23:15:45Z");
    private static final int FIXTURE_DISCUSSION_COMMENTS_COUNT = 0;

    // Author fixture values
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";
    private static final String FIXTURE_AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/u/5898705?v=4";
    private static final String FIXTURE_AUTHOR_HTML_URL = "https://github.com/FelixTJDietrich";

    // Category fixture values (General category from created fixture)
    // Note: GitHubDiscussionCategoryDTO maps @JsonProperty("id") to nodeId (String),
    // so the JSON numeric "id": 46489461 is coerced to "46489461"
    private static final String FIXTURE_GENERAL_CATEGORY_ID = "46489461";
    private static final String FIXTURE_GENERAL_CATEGORY_NAME = "General";
    private static final String FIXTURE_GENERAL_CATEGORY_SLUG = "general";

    // Q&A category from edited/answered fixtures
    private static final String FIXTURE_QA_CATEGORY_ID = "46489462";
    private static final String FIXTURE_QA_CATEGORY_NAME = "Q&A";

    // Label fixture values from discussion.labeled.json
    private static final Long FIXTURE_LABEL_ID = 9568029313L;
    private static final String FIXTURE_LABEL_NAME = "fixtures";
    private static final String FIXTURE_LABEL_COLOR = "0e8a16";

    @Autowired
    private GitHubDiscussionMessageHandler handler;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private DiscussionCategoryRepository categoryRepository;

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
            assertThat(handler.key().eventType()).isEqualTo("repository.discussion");
        }
    }

    // Basic Lifecycle Events

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldPersistDiscussionOnCreatedEvent() throws Exception {
            GitHubDiscussionEventDTO event = loadPayload("discussion.created");

            handler.handleEvent(event);

            // Use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Discussion discussion = discussionRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                        .orElseThrow();

                // Core identification fields
                assertThat(discussion.getNativeId()).isEqualTo(DISCUSSION_27_ID);
                assertThat(discussion.getNumber()).isEqualTo(FIXTURE_DISCUSSION_NUMBER);

                // Content fields
                assertThat(discussion.getTitle()).isEqualTo(FIXTURE_DISCUSSION_TITLE);
                assertThat(discussion.getBody()).isEqualTo(FIXTURE_DISCUSSION_BODY);

                // State fields
                assertThat(discussion.getState()).isEqualTo(Discussion.State.OPEN);
                assertThat(discussion.getStateReason()).isNull();
                assertThat(discussion.isLocked()).isFalse();
                assertThat(discussion.getActiveLockReason()).isNull();
                assertThat(discussion.getClosedAt()).isNull();
                assertThat(discussion.getAnswerChosenAt()).isNull();

                // URL fields
                assertThat(discussion.getHtmlUrl()).isEqualTo(FIXTURE_DISCUSSION_HTML_URL);

                // Timestamp fields (critical for sync correctness)
                assertThat(discussion.getCreatedAt()).isEqualTo(FIXTURE_DISCUSSION_CREATED_AT);
                assertThat(discussion.getUpdatedAt()).isEqualTo(FIXTURE_DISCUSSION_UPDATED_AT);

                // Counts
                assertThat(discussion.getCommentsCount()).isEqualTo(FIXTURE_DISCUSSION_COMMENTS_COUNT);

                // Repository association (foreign key)
                assertThat(discussion.getRepository()).isNotNull();
                assertThat(discussion.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

                // Author association (foreign key) - verify exact fixture values
                assertThat(discussion.getAuthor()).isNotNull();
                assertThat(discussion.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
                assertThat(discussion.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(discussion.getAuthor().getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
                assertThat(discussion.getAuthor().getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);

                // Category association - verify exact fixture values
                assertThat(discussion.getCategory()).isNotNull();
                assertThat(discussion.getCategory().getId()).isEqualTo(FIXTURE_GENERAL_CATEGORY_ID);
                assertThat(discussion.getCategory().getName()).isEqualTo(FIXTURE_GENERAL_CATEGORY_NAME);
                assertThat(discussion.getCategory().getSlug()).isEqualTo(FIXTURE_GENERAL_CATEGORY_SLUG);

                // Empty associations
                assertThat(discussion.getLabels()).isEmpty();
                assertThat(discussion.getAnswerChosenBy()).isNull();
                assertThat(discussion.getAnswerComment()).isNull();
            });

            // Domain event published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateDiscussionOnEditedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO editedEvent = loadPayload("discussion.edited");

            handler.handleEvent(editedEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Discussion discussion = discussionRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                        .orElseThrow();
                assertThat(discussion.getBody()).isEqualTo("Updated body for webhook tests");
            });

            // Discussion should still be unique (edited, not created new)
            assertThat(discussionRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldHandleClosedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO closedEvent = loadPayload("discussion.closed");

            handler.handleEvent(closedEvent);

            Discussion discussion = discussionRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                    .orElse(null);
            assertNotNull(discussion);
            assertThat(discussion.getState()).isEqualTo(Discussion.State.CLOSED);
            assertThat(discussion.getStateReason()).isEqualTo(Discussion.StateReason.RESOLVED);

            // Verify Closed event was published (processClosed publishes both Updated and Closed)
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionClosed.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleReopenedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            handler.handleEvent(loadPayload("discussion.closed"));
            eventListener.clear();

            GitHubDiscussionEventDTO reopenedEvent = loadPayload("discussion.reopened");

            handler.handleEvent(reopenedEvent);

            Discussion discussion = discussionRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                    .orElse(null);
            assertNotNull(discussion);
            assertThat(discussion.getState()).isEqualTo(Discussion.State.OPEN);

            // Verify Reopened event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionReopened.class))
                    .hasSize(1);
        }

        @Test
        void shouldDeleteDiscussionOnDeletedEvent() throws Exception {
            // First, we create it by simulating it exists
            Discussion discussionToDelete = new Discussion();
            discussionToDelete.setNativeId(DISCUSSION_28_ID);
            discussionToDelete.setNumber(28);
            discussionToDelete.setTitle("Disposable discussion");
            discussionToDelete.setState(Discussion.State.OPEN);
            discussionToDelete.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/28");
            discussionToDelete.setRepository(testRepository);
            discussionToDelete.setProvider(gitProvider);
            discussionRepository.save(discussionToDelete);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 28))
                    .isTrue();

            GitHubDiscussionEventDTO deletedEvent = loadPayload("discussion.deleted");

            handler.handleEvent(deletedEvent);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 28))
                    .isFalse();

            // Verify Deleted event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionDeleted.class))
                    .hasSize(1);
        }
    }

    // Answer Events

    @Nested
    class AnswerEvents {

        @Test
        void shouldHandleAnsweredEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO answeredEvent = loadPayload("discussion.answered");

            handler.handleEvent(answeredEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Discussion discussion = discussionRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                        .orElseThrow();
                assertThat(discussion.getAnswerChosenAt()).isNotNull();
                assertThat(discussion.getAnswerChosenBy()).isNotNull();
                assertThat(discussion.getAnswerChosenBy().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            });

            // Verify Answered event was published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionAnswered.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleUnansweredEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            handler.handleEvent(loadPayload("discussion.answered"));
            eventListener.clear();

            GitHubDiscussionEventDTO unansweredEvent = loadPayload("discussion.unanswered");

            handler.handleEvent(unansweredEvent);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isTrue();
        }
    }

    // Label Events

    @Nested
    class LabelEvents {

        @Test
        void shouldHandleLabeledEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO labeledEvent = loadPayload("discussion.labeled");

            handler.handleEvent(labeledEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Discussion discussion = discussionRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                        .orElse(null);
                assertNotNull(discussion);
                assertThat(labelNames(discussion)).contains(FIXTURE_LABEL_NAME);
            });

            // Verify label was created in repository
            assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, gitProviderId()))
                    .hasValueSatisfying(label -> assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR));
        }

        @Test
        void shouldHandleUnlabeledEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            handler.handleEvent(loadPayload("discussion.labeled"));
            eventListener.clear();

            GitHubDiscussionEventDTO unlabeledEvent = loadPayload("discussion.unlabeled");

            handler.handleEvent(unlabeledEvent);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isTrue();
        }
    }

    // Lock Events

    @Nested
    class LockEvents {

        @Test
        void shouldHandleLockedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO lockedEvent = loadPayload("discussion.locked");

            handler.handleEvent(lockedEvent);

            Discussion discussion = discussionRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                    .orElse(null);
            assertNotNull(discussion);
            assertThat(discussion.isLocked()).isTrue();
            assertThat(discussion.getActiveLockReason()).isEqualTo(Discussion.LockReason.RESOLVED);
            // Note: fixture has state="locked" but isClosed() only matches "closed",
            // so the entity state will be OPEN
            assertThat(discussion.getState()).isEqualTo(Discussion.State.OPEN);
        }

        @Test
        void shouldHandleUnlockedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            handler.handleEvent(loadPayload("discussion.locked"));
            eventListener.clear();

            GitHubDiscussionEventDTO unlockedEvent = loadPayload("discussion.unlocked");

            handler.handleEvent(unlockedEvent);

            Discussion discussion = discussionRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                    .orElse(null);
            assertNotNull(discussion);
            assertThat(discussion.isLocked()).isFalse();
        }
    }

    // Pin Events

    @Nested
    class PinEvents {

        @Test
        void shouldHandlePinnedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO pinnedEvent = loadPayload("discussion.pinned");

            handler.handleEvent(pinnedEvent);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isTrue();
        }

        @Test
        void shouldHandleUnpinnedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            handler.handleEvent(loadPayload("discussion.pinned"));
            eventListener.clear();

            GitHubDiscussionEventDTO unpinnedEvent = loadPayload("discussion.unpinned");

            handler.handleEvent(unpinnedEvent);

            assertThat(discussionRepository.existsByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isTrue();
        }
    }

    // Category Changed Event

    @Nested
    class CategoryChangedEvent {

        @Test
        void shouldHandleCategoryChangedEvent() throws Exception {
            handler.handleEvent(loadPayload("discussion.created"));
            eventListener.clear();

            GitHubDiscussionEventDTO categoryChangedEvent = loadPayload("discussion.category_changed");

            handler.handleEvent(categoryChangedEvent);

            transactionTemplate.executeWithoutResult(status -> {
                Discussion discussion = discussionRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), 27)
                        .orElseThrow();
                assertThat(discussion.getCategory()).isNotNull();
                assertThat(discussion.getCategory().getId()).isEqualTo(FIXTURE_QA_CATEGORY_ID);
                assertThat(discussion.getCategory().getName()).isEqualTo(FIXTURE_QA_CATEGORY_NAME);
            });
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleUnknownActionGracefully() throws Exception {
            GitHubDiscussionEventDTO event = loadPayload("discussion.created");

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleMissingRepositoryContextGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitHubDiscussionEventDTO event = loadPayload("discussion.created");

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // Discussion should not be persisted since context is null
            assertThat(discussionRepository.count()).isZero();
        }

        @Test
        @DisplayName("Should be idempotent - processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitHubDiscussionEventDTO event = loadPayload("discussion.created");

            handler.handleEvent(event);
            long countAfterFirst = discussionRepository.count();

            handler.handleEvent(event);

            assertThat(discussionRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldVerifyGetDatabaseIdFallback() throws Exception {
            // Webhook payloads carry 'id', not 'database_id'.
            GitHubDiscussionEventDTO event = loadPayload("discussion.created");

            // Verify the DTO is using the fallback correctly
            assertThat(event.discussion().getDatabaseId()).isEqualTo(DISCUSSION_27_ID);

            handler.handleEvent(event);

            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();
        }

        @Test
        @DisplayName("Should create author and category entities with correct field values")
        void shouldCreateAllRelatedEntitiesFromCreatedEvent() throws Exception {
            assertThat(userRepository.count()).isZero();

            handler.handleEvent(loadPayload("discussion.created"));

            var author = userRepository
                    .findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, gitProviderId())
                    .orElseThrow();
            assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(author.getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
            assertThat(author.getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);

            var category =
                    categoryRepository.findById(FIXTURE_GENERAL_CATEGORY_ID).orElseThrow();
            assertThat(category.getName()).isEqualTo(FIXTURE_GENERAL_CATEGORY_NAME);
            assertThat(category.getSlug()).isEqualTo(FIXTURE_GENERAL_CATEGORY_SLUG);
        }
    }

    // Helper Methods

    private GitHubDiscussionEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubDiscussionEventDTO.class);
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
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    private Set<String> labelNames(Discussion discussion) {
        return discussion.getLabels().stream().map(l -> l.getName()).collect(Collectors.toSet());
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}
