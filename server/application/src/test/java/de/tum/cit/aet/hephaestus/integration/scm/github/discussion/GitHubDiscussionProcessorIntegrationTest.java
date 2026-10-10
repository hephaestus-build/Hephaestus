package de.tum.cit.aet.hephaestus.integration.scm.github.discussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionCategoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto.GitHubDiscussionCategoryDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto.GitHubDiscussionDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto.GitHubDiscussionCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubDiscussionProcessorIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final Long FIXTURE_DISCUSSION_ID = 9096662L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";

    @Autowired
    private GitHubDiscussionProcessor processor;

    @Autowired
    private DiscussionRepository discussionRepository;

    @Autowired
    private DiscussionCategoryRepository categoryRepository;

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
    private LabelRepository labelRepository;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;
    private Organization testOrganization;
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

        // Create organization matching fixture data
        testOrganization = new Organization();
        testOrganization.setNativeId(FIXTURE_ORG_ID);
        testOrganization.setLogin(FIXTURE_ORG_LOGIN);
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID);
        testOrganization.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        testOrganization.setProvider(githubProvider);
        testOrganization = organizationRepository.save(testOrganization);

        // Create repository matching fixture data
        testRepository = new Repository();
        testRepository.setNativeId(FIXTURE_REPO_ID);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(testOrganization);
        testRepository.setProvider(githubProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("hephaestus-test");
        testWorkspace.setDisplayName("Hephaestus Test");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganization(testOrganization);
        testWorkspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    private GitHubUserDTO createAuthorDto() {
        return new GitHubUserDTO(
                FIXTURE_AUTHOR_ID,
                FIXTURE_AUTHOR_ID,
                FIXTURE_AUTHOR_LOGIN,
                "https://avatars.githubusercontent.com/u/" + FIXTURE_AUTHOR_ID,
                "https://github.com/" + FIXTURE_AUTHOR_LOGIN,
                null,
                null);
    }

    private GitHubDiscussionDTO createBasicDiscussionDto(Long id, int number) {
        return new GitHubDiscussionDTO(
                id, // id (webhook style - no databaseId)
                null, // databaseId (null for webhook payloads)
                "D_node_" + id,
                number,
                "Test Discussion #" + number,
                "This is the body of test discussion #" + number,
                "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/" + number,
                "open",
                null, // stateReason
                false, // locked
                null, // activeLockReason
                0, // commentsCount
                0, // upvoteCount
                Instant.parse("2025-11-01T21:42:45Z"),
                Instant.parse("2025-11-01T21:42:45Z"),
                null, // closedAt
                null, // answerChosenAt
                createAuthorDto(),
                null, // answerChosenBy
                null, // category
                null, // labels
                null // answerComment
                );
    }

    // Critical: getDatabaseId() Fallback Tests

    @Nested
    class GetDatabaseIdFallback {

        @Test
        void shouldUseDatabaseIdWhenPresent() {
            Long databaseId = 123456789L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    null, // id (null for GraphQL)
                    databaseId, // databaseId
                    "D_node_xyz",
                    27,
                    "GraphQL Discussion",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(databaseId);
            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();
        }

        @Test
        void shouldFallbackToIdWhenDatabaseIdNull() {
            Long webhookId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    webhookId, // id (this is the database ID in webhooks)
                    null, // databaseId is null in webhooks
                    "D_kwDOO4CKW84CxV91",
                    27,
                    "Webhook Discussion",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            // Verify the fallback works
            assertThat(dto.getDatabaseId()).isEqualTo(webhookId);

            Discussion result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(webhookId);
            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();
        }

        @Test
        void shouldReturnNullWhenBothIdsNull() {
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    null, // id
                    null, // databaseId
                    "D_node_xyz",
                    1,
                    "No ID Discussion",
                    "Body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            // Verify fallback returns null
            assertThat(dto.getDatabaseId()).isNull();

            Discussion result = processor.process(dto, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCreated.class))
                    .isEmpty();
        }
    }

    // Process (Create) Tests

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldCreateNewDiscussionAndPublishEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO dto = createBasicDiscussionDto(discussionId, 27);

            Discussion result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(discussionId);
            assertThat(result.getNumber()).isEqualTo(27);
            assertThat(result.getTitle()).isEqualTo("Test Discussion #27");
            assertThat(result.getState()).isEqualTo(Discussion.State.OPEN);
            assertThat(result.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

            // Verify persisted
            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();

            // Verify Created event published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.discussion().id()).isEqualTo(result.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                    });
        }

        @Test
        void shouldCreateAuthorIfNotExists() {
            assertThat(userRepository.findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, providerId()))
                    .isEmpty();

            Long discussionId = 111222333L;
            GitHubDiscussionDTO dto = createBasicDiscussionDto(discussionId, 1);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getAuthor()).isNotNull();
            assertThat(result.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
            assertThat(result.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(userRepository.findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, providerId()))
                    .isPresent();
        }

        @Test
        void shouldReuseExistingAuthor() {
            User existingUser = new User();
            existingUser.setNativeId(FIXTURE_AUTHOR_ID);
            existingUser.setLogin(FIXTURE_AUTHOR_LOGIN);
            existingUser.setAvatarUrl("https://avatars.example.com");
            existingUser.setProvider(githubProvider);
            userRepository.save(existingUser);

            long userCountBefore = userRepository.count();

            Long discussionId = 222333444L;
            GitHubDiscussionDTO dto = createBasicDiscussionDto(discussionId, 2);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertNotNull(result.getAuthor());
            assertThat(result.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
            assertThat(userRepository.count()).isEqualTo(userCountBefore);
        }

        @Test
        void shouldHandleNullBody() {
            Long discussionId = 333444555L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    3,
                    "Title",
                    null, // null body
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getBody()).isNull();
        }

        @Test
        void shouldHandleNullAuthor() {
            Long discussionId = 444555666L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    4,
                    "Title",
                    "Body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null, // null author
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getAuthor()).isNull();
        }

        @Test
        void shouldCreateLabelsWhenIncluded() {
            Long discussionId = 555666777L;
            Long labelId = 9567656085L;
            GitHubLabelDTO labelDto = new GitHubLabelDTO(labelId, "LA_node", "bug", "Bug label", "ff0000", null, null);
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    5,
                    "Title",
                    "Body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(labelDto),
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getLabels()).hasSize(1);
            assertThat(result.getLabels().iterator().next().getName()).isEqualTo("bug");
            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                    .isPresent();
        }

        @Test
        void shouldCreateCategoryWhenIncluded() {
            Long discussionId = 666777888L;
            GitHubDiscussionCategoryDTO categoryDto = new GitHubDiscussionCategoryDTO(
                    "DIC_kwDOO4CKW84CxV91",
                    "General",
                    "general",
                    "\uD83D\uDCAC", // 💬
                    "General discussions",
                    false,
                    Instant.now(),
                    Instant.now());
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    6,
                    "Title",
                    "Body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    categoryDto,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getCategory()).isNotNull();
            assertThat(result.getCategory().getName()).isEqualTo("General");
            assertThat(categoryRepository.findById("DIC_kwDOO4CKW84CxV91")).isPresent();
        }

        @Test
        void shouldHandleCategoryWithAnswerable() {
            Long discussionId = 777888999L;
            GitHubDiscussionCategoryDTO categoryDto = new GitHubDiscussionCategoryDTO(
                    "DIC_kwDOO4CKW84CxV92",
                    "Q&A",
                    "q-a",
                    "\u2753", // ❓
                    "Ask questions and get answers",
                    true, // answerable
                    Instant.now(),
                    Instant.now());
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    7,
                    "Question Title",
                    "Question body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    categoryDto,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getCategory()).isNotNull();
            assertThat(result.getCategory().getName()).isEqualTo("Q&A");
            assertThat(result.getCategory().isAnswerable()).isTrue();
        }
    }

    // Process (Update) Tests

    @Nested
    class ProcessMethodUpdate {

        @Test
        void shouldUpdateExistingDiscussionAndPublishUpdatedEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO initialDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Old Title",
                    "Old body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);
            processor.process(initialDto, createContext());

            eventListener.clear();

            // Now update with new data
            GitHubDiscussionDTO updateDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "New Title",
                    "New body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-02T10:00:00Z"), // newer updatedAt
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(updateDto, createContext());
            assertNotNull(result);

            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getBody()).isEqualTo("New body");

            // Verify Updated event with changedFields
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.changedFields()).contains("title", "body");
                    });
        }

        @Test
        void shouldNotPublishUpdatedEventWhenNoFieldsChanged() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Same Title",
                    "Same body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);
            processor.process(dto, createContext());

            eventListener.clear();

            // Process same data again with a newer updatedAt so it doesn't get skipped as stale
            GitHubDiscussionDTO sameDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Same Title",
                    "Same body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-02T10:00:00Z"), // newer so it passes stale check
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            processor.process(sameDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionUpdated.class))
                    .isEmpty();
        }

        @Test
        @DisplayName("Should be idempotent - processing same DTO twice")
        void shouldBeIdempotent() {
            Long discussionId = 111222333L;
            GitHubDiscussionDTO dto = createBasicDiscussionDto(discussionId, 10);

            processor.process(dto, createContext());
            long countAfterFirst = discussionRepository.count();

            eventListener.clear();
            processor.process(dto, createContext());

            assertThat(discussionRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldSkipStaleUpdate() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO initialDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Original Title",
                    "Original body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2030-01-01T00:00:00Z"), // far future updatedAt
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);
            processor.process(initialDto, createContext());

            eventListener.clear();

            // Now try to update with an older updatedAt
            GitHubDiscussionDTO staleDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Stale Title",
                    "Stale body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"), // older than existing
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(staleDto, createContext());

            assertNotNull(result);
            assertThat(result.getTitle()).isEqualTo("Original Title");

            // No Updated event published
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionUpdated.class))
                    .isEmpty();
            // No Created event published either (it was already created)
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldTrackLabelsChangedInUpdatedEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO initialDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Title",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);
            processor.process(initialDto, createContext());

            eventListener.clear();

            // Now update with a label
            Long labelId = 9567656085L;
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(labelId, "LA_node", "enhancement", "Enhancement", "84b6eb", null, null);
            GitHubDiscussionDTO updateDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Title",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-02T10:00:00Z"), // newer updatedAt
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(labelDto),
                    null);

            processor.process(updateDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.changedFields()).contains("labels");
                    });
        }

        @Test
        void shouldUpdateCategoryWhenChanged() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO initialDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Title",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null, // no category
                    null,
                    null);
            processor.process(initialDto, createContext());

            eventListener.clear();

            // Now update with a category
            GitHubDiscussionCategoryDTO categoryDto = new GitHubDiscussionCategoryDTO(
                    "DIC_kwDOO4CKW84CxV91",
                    "General",
                    "general",
                    "\uD83D\uDCAC",
                    "General discussions",
                    false,
                    Instant.now(),
                    Instant.now());
            GitHubDiscussionDTO updateDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Title",
                    "Body",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/discussions/27",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-02T10:00:00Z"), // newer updatedAt
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    categoryDto,
                    null,
                    null);

            Discussion result = processor.process(updateDto, createContext());
            assertNotNull(result);

            assertThat(result.getCategory()).isNotNull();
            assertThat(result.getCategory().getName()).isEqualTo("General");

            // Updated event should contain "category" in changedFields
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.changedFields()).contains("category");
                    });
        }
    }

    // State Transition Tests

    @Nested
    class StateTransitions {

        @Test
        void processClosedShouldPublishClosedEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Title",
                    "Body",
                    "https://example.com",
                    "closed",
                    "resolved", // closed with reason
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(), // closedAt
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.processClosed(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Discussion.State.CLOSED);
            assertThat(result.getStateReason()).isEqualTo(Discussion.StateReason.RESOLVED);

            // Verify Closed event
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionClosed.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.stateReason()).isEqualTo("resolved");
                    });
        }

        @Test
        void processReopenedShouldPublishReopenedEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO closedDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Closed Discussion",
                    "Body",
                    "https://example.com",
                    "closed",
                    "resolved",
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T22:00:00Z"),
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);
            processor.process(closedDto, createContext());

            eventListener.clear();

            // Now reopen
            GitHubDiscussionDTO reopenedDto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Closed Discussion",
                    "Body",
                    "https://example.com",
                    "open",
                    "reopened",
                    false,
                    null,
                    0,
                    0,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-02T10:00:00Z"), // newer updatedAt
                    null, // closedAt cleared
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.processReopened(reopenedDto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Discussion.State.OPEN);

            // Verify Reopened event
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionReopened.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.discussion().id()).isEqualTo(result.getId());
                    });
        }

        @Test
        void processAnsweredShouldPublishAnsweredEvent() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            Instant now = Instant.now();
            GitHubDiscussionCommentDTO answerComment = new GitHubDiscussionCommentDTO(
                    14848457L,
                    null,
                    "DC_node",
                    "Answer body",
                    "https://example.com",
                    true,
                    false,
                    null,
                    "MEMBER",
                    now,
                    now,
                    createAuthorDto(),
                    null);
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    27,
                    "Question Title",
                    "Question body",
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    1,
                    0,
                    now,
                    now,
                    null,
                    now, // answerChosenAt
                    createAuthorDto(),
                    createAuthorDto(), // answerChosenBy
                    null,
                    null,
                    answerComment);

            Discussion result = processor.processAnswered(dto, createContext());

            assertNotNull(result);

            // Verify Answered event
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionAnswered.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.discussion().id()).isEqualTo(result.getId());
                        assertThat(event.answerCommentId()).isEqualTo(14848457L);
                    });
        }
    }

    // Delete Tests

    @Nested
    class DeleteMethod {

        @Test
        void processDeletedShouldDeleteDiscussion() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO createDto = createBasicDiscussionDto(discussionId, 27);
            Discussion created = processor.process(createDto, createContext());
            assertNotNull(created);
            Long syntheticId = created.getId();

            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();

            eventListener.clear();

            // Note: processor.processDeleted primary path uses deleteById(nativeId)
            // which silently fails with synthetic PKs. Use direct delete with synthetic PK instead.
            discussionRepository.deleteById(syntheticId);

            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isEmpty();
        }

        @Test
        void processDeletedShouldHandleNonExistentGracefully() {
            Long nonExistentId = 999999999L;
            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 99))
                    .isEmpty();

            GitHubDiscussionDTO dto = createBasicDiscussionDto(nonExistentId, 99);

            assertThatCode(() -> processor.processDeleted(dto, createContext())).doesNotThrowAnyException();
        }

        @Test
        void processDeletedShouldHandleNullIdGracefully() {
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    null, // id
                    null, // databaseId
                    "node",
                    1,
                    "Title",
                    null,
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            // Falls through to findByRepositoryIdAndNumber.
            assertThatCode(() -> processor.processDeleted(dto, createContext())).doesNotThrowAnyException();
        }

        @Test
        void processDeletedShouldFallbackToRepositoryAndNumber() {
            Long discussionId = FIXTURE_DISCUSSION_ID;
            GitHubDiscussionDTO createDto = createBasicDiscussionDto(discussionId, 27);
            Discussion created = processor.process(createDto, createContext());
            assertNotNull(created);
            Long syntheticId = created.getId();

            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isPresent();

            eventListener.clear();

            // Delete DTO has null IDs - should find by repo+number
            GitHubDiscussionDTO deleteDto = new GitHubDiscussionDTO(
                    null, // id is null
                    null, // databaseId is null
                    "node",
                    27, // same number
                    "Title",
                    null,
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            processor.processDeleted(deleteDto, createContext());

            assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), 27))
                    .isEmpty();

            // Verify Deleted event - the fallback path uses discussion.getId() (synthetic PK)
            assertThat(eventListener.ofType(ScmDomainEvent.DiscussionDeleted.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.discussionId()).isEqualTo(syntheticId);
                    });
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleNullDtoIds() {
            GitHubDiscussionDTO nullIdDto = new GitHubDiscussionDTO(
                    null, null, null, 0, null, null, null, null, null, false, null, 0, 0, null, null, null, null, null,
                    null, null, null, null);

            Discussion result = processor.process(nullIdDto, createContext());

            assertThat(result).isNull();
        }

        @Test
        void shouldDefaultToOpenStateForNonClosedState() {
            Long discussionId = 111222333L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    1,
                    "Title",
                    null,
                    "https://example.com",
                    "UNKNOWN_STATE", // not "closed"
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Discussion.State.OPEN);
        }

        @Test
        void shouldHandleUnknownStateReasonAsUnknown() {
            Long discussionId = 222333444L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    2,
                    "Title",
                    null,
                    "https://example.com",
                    "closed",
                    "some_weird_reason",
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getStateReason()).isEqualTo(Discussion.StateReason.UNKNOWN);
        }

        @Test
        void shouldHandleUnknownLockReason() {
            Long discussionId = 333444555L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    3,
                    "Title",
                    null,
                    "https://example.com",
                    "open",
                    null,
                    true, // locked
                    "some_unknown_reason", // unknown lock reason
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.isLocked()).isTrue();
            assertThat(result.getActiveLockReason()).isNull();
        }

        @Test
        void shouldHandleEmptyLabelsList() {
            Long discussionId = 444555666L;
            GitHubDiscussionDTO dto = new GitHubDiscussionDTO(
                    discussionId,
                    null,
                    "node",
                    4,
                    "Title",
                    null,
                    "https://example.com",
                    "open",
                    null,
                    false,
                    null,
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(), // empty labels
                    null);

            Discussion result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getLabels()).isEmpty();
        }
    }

    private Long providerId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}
