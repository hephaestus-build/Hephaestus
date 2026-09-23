package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueTypeDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for GitHubIssueProcessor.
 * <p>
 * Tests the processor independently from the webhook handler to verify:
 * - Issue upsert logic (create vs update)
 * - Domain event publishing (Created, Updated, Closed, Labeled, etc.)
 * - Context handling and workspace association
 * - Author user association and creation
 * - Label and milestone associations
 * - Issue type handling
 * - Edge cases in DTO processing including the critical getDatabaseId() fallback
 */
class GitHubIssueProcessorIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final Long FIXTURE_ISSUE_ID = 3578496080L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";

    @Autowired
    private GitHubIssueProcessor processor;

    @Autowired
    private IssueRepository issueRepository;

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
    private MilestoneRepository milestoneRepository;

    @Autowired
    private IssueTypeRepository issueTypeRepository;

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
        testWorkspace.setIsPubliclyViewable(true);
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

    private GitHubIssueDTO createBasicIssueDto(Long id, int number) {
        return new GitHubIssueDTO(
                id, // id (webhook style - no databaseId)
                null, // databaseId (null for webhook payloads)
                "I_node_" + id,
                number,
                "Test Issue #" + number,
                "This is the body of test issue #" + number,
                "open",
                null, // stateReason
                "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/" + number,
                0, // commentsCount
                Instant.parse("2025-11-01T21:42:45Z"),
                Instant.parse("2025-11-01T21:42:45Z"),
                null, // closedAt
                false, // locked
                createAuthorDto(),
                null, // assignees
                null, // labels
                null, // milestone
                null, // issueType
                null, // repository
                null // pullRequest
                ,
                null // subIssuesSummary
                );
    }

    // Critical: getDatabaseId() Fallback Tests

    @Nested
    class GetDatabaseIdFallback {

        @Test
        void shouldUseDatabaseIdWhenPresent() {
            // Given - GraphQL style DTO with databaseId
            Long databaseId = 123456789L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    999L, // id (node id as number, but databaseId is what matters)
                    databaseId, // databaseId
                    "I_node_xyz",
                    1,
                    "GraphQL Issue",
                    "Body",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/1",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());

            // Then - should use databaseId
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(databaseId);
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 1))
                    .isPresent();
        }

        @Test
        void shouldFallbackToIdWhenDatabaseIdNull() {
            // Given - Webhook style DTO with only id
            Long webhookId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    webhookId, // id (this is the database ID in webhooks)
                    null, // databaseId is null in webhooks
                    "I_kwDOO4CKW87VS4RQ",
                    20,
                    "Webhook Issue",
                    "Body",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/20",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            // Verify the fallback works
            assertThat(dto.getDatabaseId()).isEqualTo(webhookId);

            Issue result = processor.process(dto, createContext());

            // Then - should use id as fallback
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(webhookId);
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isPresent();
        }

        @Test
        void shouldReturnNullWhenBothIdsNull() {
            // Given - malformed DTO with no IDs
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    null, // id
                    null, // databaseId
                    "I_node_xyz",
                    1,
                    "No ID Issue",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            // Verify fallback returns null
            assertThat(dto.getDatabaseId()).isNull();

            Issue result = processor.process(dto, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class)).isEmpty();
        }
    }

    // Process (Create/Update) Tests

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldCreateNewIssueAndPublishEvent() {
            Long issueId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO dto = createBasicIssueDto(issueId, 20);

            Issue result = processor.process(dto, createContext());

            // Then - verify issue created
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(issueId);
            assertThat(result.getNumber()).isEqualTo(20);
            assertThat(result.getTitle()).isEqualTo("Test Issue #20");
            assertThat(result.getState()).isEqualTo(Issue.State.OPEN);
            assertThat(result.requireRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

            // Verify persisted
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isPresent();

            // Verify Created event published
            assertThat(eventListener.ofType(ScmDomainEvent.IssueCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.issue().id()).isEqualTo(result.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                    });
        }

        @Test
        void shouldCreateAuthorIfNotExists() {
            // Given - no user exists
            assertThat(userRepository.findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, providerId()))
                    .isEmpty();

            Long issueId = 111222333L;
            GitHubIssueDTO dto = createBasicIssueDto(issueId, 1);

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getAuthor()).isNotNull();
            assertThat(result.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
            assertThat(result.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(userRepository.findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, providerId()))
                    .isPresent();
        }

        @Test
        void shouldReuseExistingAuthor() {
            // Given - create user first
            User existingUser = new User();
            existingUser.setNativeId(FIXTURE_AUTHOR_ID);
            existingUser.setLogin(FIXTURE_AUTHOR_LOGIN);
            existingUser.setAvatarUrl("https://avatars.example.com");
            existingUser.setProvider(githubProvider);
            userRepository.save(existingUser);

            long userCountBefore = userRepository.count();

            Long issueId = 222333444L;
            GitHubIssueDTO dto = createBasicIssueDto(issueId, 2);

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            // Then - should reuse existing user, not create new
            assertNotNull(result.getAuthor());
            assertThat(result.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
            assertThat(userRepository.count()).isEqualTo(userCountBefore);
        }

        @Test
        void shouldHandleNullBody() {
            Long issueId = 333444555L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    3,
                    "Title",
                    null, // null body
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getBody()).isNull();
        }

        @Test
        void shouldHandleNullAuthor() {
            Long issueId = 444555666L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    4,
                    "Title",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null, // null author
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getAuthor()).isNull();
        }

        @Test
        void shouldCreateLabelsWhenIncluded() {
            Long issueId = 555666777L;
            Long labelId = 9567656085L;
            GitHubLabelDTO labelDto = new GitHubLabelDTO(labelId, "LA_node", "bug", "Bug label", "ff0000", null, null);
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    5,
                    "Title",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    List.of(labelDto),
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getLabels()).hasSize(1);
            assertThat(result.getLabels().iterator().next().getName()).isEqualTo("bug");
            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                    .isPresent();
        }

        @Test
        void shouldCreateAssigneesWhenIncluded() {
            Long issueId = 666777888L;
            Long assigneeId = 888999111L;
            GitHubUserDTO assigneeDto = new GitHubUserDTO(
                    assigneeId,
                    assigneeId,
                    "assignee1",
                    "https://avatars.example.com",
                    "https://github.com/assignee1",
                    null,
                    null);
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    6,
                    "Title",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    List.of(assigneeDto),
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getAssignees()).hasSize(1);
            assertThat(result.getAssignees().iterator().next().getLogin()).isEqualTo("assignee1");
            assertThat(userRepository.findByNativeIdAndProviderId(assigneeId, providerId()))
                    .isPresent();
        }

        @Test
        void shouldCreateMilestoneWhenIncluded() {
            Long issueId = 777888999L;
            Long milestoneId = 14028563L;
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    2,
                    "Webhook Fixtures",
                    "Milestone description",
                    "open",
                    Instant.parse("2025-12-31T08:00:00Z"),
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/2",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    7,
                    "Title",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    milestoneDto,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getMilestone()).isNotNull();
            assertThat(result.getMilestone().getTitle()).isEqualTo("Webhook Fixtures");
            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent();
        }
    }

    // Process (Update) Tests

    @Nested
    class ProcessMethodUpdate {

        @Test
        void shouldUpdateExistingIssueAndPublishEvent() {
            // Given - create existing issue
            Long issueId = FIXTURE_ISSUE_ID;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(20);
            existing.setTitle("Old Title");
            existing.setBody("Old body");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/20");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    20,
                    "New Title",
                    "New body",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/20",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            // Then - verify issue updated
            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getBody()).isEqualTo("New body");

            // Verify Updated event with changedFields
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.changedFields()).contains("title", "body");
                    });
        }

        @Test
        @DisplayName("Should not publish Updated event when no fields changed")
        void shouldNotPublishWhenNoChanges() {
            // Given - create existing issue
            Long issueId = FIXTURE_ISSUE_ID;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(20);
            existing.setTitle("Same Title");
            existing.setBody("Same body");
            existing.setState(Issue.State.OPEN);
            existing.setCommentsCount(5);
            existing.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/20");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            existing.setCreatedAt(Instant.parse("2025-11-01T21:42:45Z"));
            existing.setUpdatedAt(Instant.parse("2025-11-01T21:42:45Z"));
            issueRepository.save(existing);

            eventListener.clear();

            // Same data
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    20,
                    "Same Title",
                    "Same body",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/20",
                    5,
                    Instant.parse("2025-11-01T21:42:45Z"),
                    Instant.parse("2025-11-01T21:42:45Z"),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            processor.process(dto, createContext());

            // Then - no Updated event (empty changedFields)
            // Note: Event is still published but with empty changedFields
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class)).allSatisfy(event -> {
                // changedFields should be empty
                assertThat(event.changedFields()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should be idempotent - processing same DTO twice")
        void shouldBeIdempotent() {
            Long issueId = 111222333L;
            GitHubIssueDTO dto = createBasicIssueDto(issueId, 10);

            // When - process twice
            processor.process(dto, createContext());
            long countAfterFirst = issueRepository.count();

            eventListener.clear();
            processor.process(dto, createContext());

            // Then - only one issue exists
            assertThat(issueRepository.count()).isEqualTo(countAfterFirst);
        }

        /**
         * Assignees live in a join table the atomic upsert cannot touch, so a change to them is reported
         * under one name for labels and assignees alike — which is what the review pipeline reads to
         * decide that a triage action moved evidence a practice can see.
         */
        @Test
        void shouldReportRelationshipsWhenAnIssueIsAssigned() {
            Long issueId = 888999111L;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(21);
            existing.setTitle("Issue");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://example.com/issues/21");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            Issue result = processor.process(
                    issueDto(issueId, 21, "Issue", false, 0, List.of(createAuthorDto())), createContext());
            assertNotNull(result);

            assertThat(result.getAssignees()).isNotEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .first()
                    .satisfies(event -> assertThat(event.changedFields()).contains("relationships"));
        }

        /**
         * Locking an issue and commenting on it move no field the issue evidence is built from. The
         * mirror still reports them, and reporting them under their own names is what lets the review
         * pipeline decline the occasion instead of spending a review on evidence that did not move.
         */
        @Test
        void shouldNameOnlyTheFieldsThatMovedWhenAnIssueIsLockedAndCommentedOn() {
            Long issueId = 888999222L;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(22);
            existing.setTitle("Issue");
            existing.setState(Issue.State.OPEN);
            existing.setLocked(false);
            existing.setCommentsCount(0);
            existing.setHtmlUrl("https://example.com/issues/22");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            Issue result = processor.process(issueDto(issueId, 22, "Issue", true, 3, null), createContext());
            assertNotNull(result);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .first()
                    .satisfies(event ->
                            assertThat(event.changedFields()).containsExactlyInAnyOrder("locked", "commentsCount"));
        }

        private GitHubIssueDTO issueDto(
                Long issueId,
                int number,
                String title,
                boolean locked,
                int commentsCount,
                @Nullable List<GitHubUserDTO> assignees) {
            return new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    number,
                    title,
                    null,
                    "open",
                    null,
                    "https://example.com/issues/" + number,
                    commentsCount,
                    Instant.now(),
                    Instant.now(),
                    null,
                    locked,
                    null,
                    assignees,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // subIssuesSummary
                    );
        }

        @Test
        void shouldUpdateMilestoneWhenChanged() {
            // Given - create issue without milestone
            Long issueId = 888999000L;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(8);
            existing.setTitle("Issue");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://example.com");
            existing.setRepository(testRepository);
            existing.setMilestone(null);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            // Now add milestone
            Long milestoneId = 14028563L;
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    2,
                    "New Milestone",
                    "Desc",
                    "open",
                    null,
                    "https://example.com/milestone/2",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    8,
                    "Issue",
                    null,
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    milestoneDto,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getMilestone()).isNotNull();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .first()
                    .satisfies(event -> assertThat(event.changedFields()).contains("milestone"));
        }

        @Test
        void shouldRemoveMilestoneWhenDemilestoned() {
            // Given - create milestone and issue with milestone
            Long milestoneId = 14028563L;
            Milestone milestone = new Milestone();
            milestone.setNativeId(milestoneId);
            milestone.setNumber(2);
            milestone.setTitle("Existing Milestone");
            milestone.setState(Milestone.State.OPEN);
            milestone.setHtmlUrl("https://example.com/milestone/2");
            milestone.setRepository(testRepository);
            milestone.setProvider(githubProvider);
            milestoneRepository.save(milestone);

            Long issueId = 999000111L;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(9);
            existing.setTitle("Issue");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://example.com");
            existing.setRepository(testRepository);
            existing.setMilestone(milestone);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            // Update with null milestone
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    9,
                    "Issue",
                    null,
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getMilestone()).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .first()
                    .satisfies(event -> assertThat(event.changedFields()).contains("milestone"));
        }
    }

    // State Transition Tests

    @Nested
    class StateTransitions {

        @Test
        void processClosedShouldPublishClosedEvent() {
            Long issueId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    20,
                    "Title",
                    "Body",
                    "closed",
                    "completed", // closed with reason
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.processClosed(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Issue.State.CLOSED);
            assertThat(result.getStateReason()).isEqualTo(Issue.StateReason.COMPLETED);

            // Verify Closed event
            assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.stateReason()).isEqualTo("completed");
                    });
        }

        @Test
        void processReopenedShouldPublishUpdatedEvent() {
            // Given - create closed issue first
            Long issueId = 222333444L;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(2);
            existing.setTitle("Closed Issue");
            existing.setState(Issue.State.CLOSED);
            existing.setStateReason(Issue.StateReason.COMPLETED);
            existing.setHtmlUrl("https://example.com");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    2,
                    "Reopened Issue",
                    null,
                    "open",
                    "reopened",
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.processReopened(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Issue.State.OPEN);

            // Verify Updated event with "state" in changedFields
            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .anySatisfy(event -> assertThat(event.changedFields()).contains("state"));
        }

        @Test
        void shouldHandleNotPlannedStateReason() {
            Long issueId = 333444555L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    3,
                    "Title",
                    "Body",
                    "closed",
                    "not_planned",
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    false, // locked
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.processClosed(dto, createContext());
            assertNotNull(result);

            assertThat(result.getStateReason()).isEqualTo(Issue.StateReason.NOT_PLANNED);
        }
    }

    // Label Event Tests

    @Nested
    class LabelEvents {

        @Test
        void processLabeledShouldPublishEvent() {
            // Given - create issue first
            Long issueId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO issueDto = createBasicIssueDto(issueId, 20);
            processor.process(issueDto, createContext());

            eventListener.clear();

            Long labelId = 9567656085L;
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(labelId, "LA_node", "enhancement", "Enhancement", "84b6eb", null, null);

            processor.processLabeled(issueDto, labelDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.IssueLabeled.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.label().name()).isEqualTo("enhancement");
                    });
        }

        @Test
        void processUnlabeledShouldPublishEvent() {
            // Given - create issue with label
            Long issueId = FIXTURE_ISSUE_ID;
            Long labelId = 9567656085L;
            GitHubLabelDTO labelDto = new GitHubLabelDTO(labelId, "LA_node", "bug", "Bug", "ff0000", null, null);
            GitHubIssueDTO issueDto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    20,
                    "Title",
                    "Body",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    List.of(labelDto),
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );
            processor.process(issueDto, createContext());

            eventListener.clear();

            processor.processUnlabeled(issueDto, labelDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUnlabeled.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.label().name()).isEqualTo("bug");
                    });
        }
    }

    // Issue Type Tests

    @Nested
    class IssueTypeEvents {

        @Test
        void processTypedShouldPublishEvent() {
            // Given - create issue first
            Long issueId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO issueDto = createBasicIssueDto(issueId, 20);

            GitHubIssueTypeDTO typeDto = new GitHubIssueTypeDTO(
                    27228861L, "IT_kwDODNYmp84Bn3q9", "Task", "A specific piece of work", "yellow", true);

            Issue result = processor.processTyped(issueDto, typeDto, FIXTURE_ORG_LOGIN, createContext());
            assertNotNull(result);

            assertThat(result.getIssueType()).isNotNull();
            assertThat(result.getIssueType().getName()).isEqualTo("Task");

            assertThat(eventListener.ofType(ScmDomainEvent.IssueTyped.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.issueType().name()).isEqualTo("Task");
                        assertThat(event.issue().id()).isEqualTo(result.getId());
                    });
        }

        /**
         * A native type is one of the fields the issue evidence is built from, so typing an issue that
         * already exists has to be reported as a moved field — that, not the IssueTyped event, is what
         * occasions the review of the retyped issue.
         */
        @Test
        void shouldReportIssueTypeWhenAnExistingIssueIsTyped() {
            Long issueId = 888999333L;
            processor.process(createBasicIssueDto(issueId, 23), createContext());
            eventListener.clear();

            GitHubIssueTypeDTO typeDto = new GitHubIssueTypeDTO(
                    27228861L, "IT_kwDODNYmp84Bn3q9", "Task", "A specific piece of work", "yellow", true);
            GitHubIssueDTO basic = createBasicIssueDto(issueId, 23);
            GitHubIssueDTO typed = new GitHubIssueDTO(
                    basic.id(),
                    basic.databaseId(),
                    basic.nodeId(),
                    basic.number(),
                    basic.title(),
                    basic.body(),
                    basic.state(),
                    basic.stateReason(),
                    basic.htmlUrl(),
                    basic.commentsCount(),
                    basic.createdAt(),
                    basic.updatedAt(),
                    basic.closedAt(),
                    basic.locked(),
                    basic.author(),
                    basic.assignees(),
                    basic.labels(),
                    basic.milestone(),
                    typeDto,
                    basic.repository(),
                    basic.pullRequest(),
                    null // subIssuesSummary
                    );

            Issue result = processor.processTyped(typed, typeDto, FIXTURE_ORG_LOGIN, createContext());
            assertNotNull(result);

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class))
                    .first()
                    .satisfies(event -> assertThat(event.changedFields()).contains("issueType"));
        }

        @Test
        void processUntypedShouldPublishEvent() {
            // Given - create issue type
            IssueType issueType = new IssueType();
            issueType.setId("IT_node_123");
            issueType.setName("Bug");
            issueType.setDescription("A bug");
            issueType.setColor(IssueType.Color.RED);
            issueType.setEnabled(true);
            issueType.setOrganization(testOrganization);
            issueType = issueTypeRepository.save(issueType);

            // Create issue with type
            Long issueId = FIXTURE_ISSUE_ID;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(20);
            existing.setTitle("Typed Issue");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://example.com");
            existing.setRepository(testRepository);
            existing.setIssueType(issueType);
            existing.setProvider(githubProvider);
            issueRepository.save(existing);

            eventListener.clear();

            GitHubIssueDTO issueDto = createBasicIssueDto(issueId, 20);

            Issue result = processor.processUntyped(issueDto, createContext());
            assertNotNull(result);

            assertThat(result.getIssueType()).isNull();

            assertThat(eventListener.ofType(ScmDomainEvent.IssueUntyped.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertNotNull(event.previousType());
                        assertThat(event.previousType().name()).isEqualTo("Bug");
                        assertThat(event.issue().id()).isEqualTo(result.getId());
                    });
        }
    }

    // Delete Tests

    @Nested
    class DeleteMethod {

        @Test
        void processDeletedShouldDeleteIssue() {
            // Given - create issue via process() to match real workflow
            Long issueId = FIXTURE_ISSUE_ID;
            GitHubIssueDTO createDto = createBasicIssueDto(issueId, 20);
            processor.process(createDto, createContext());

            Issue existing = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 20)
                    .orElseThrow();
            assertThat(existing.getNativeId()).isEqualTo(issueId);

            eventListener.clear();

            // When - delete via processDeleted (uses natural key lookup with synthetic PKs)
            GitHubIssueDTO deleteDto = createBasicIssueDto(issueId, 20);
            processor.processDeleted(deleteDto, createContext());

            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 20))
                    .isEmpty();
        }

        @Test
        void processDeletedShouldHandleNonExistent() {
            // Given - issue doesn't exist
            Long nonExistentId = 999999999L;
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 99))
                    .isEmpty();

            GitHubIssueDTO dto = createBasicIssueDto(nonExistentId, 99);

            // When/Then - should not throw
            assertThatCode(() -> processor.processDeleted(dto, createContext())).doesNotThrowAnyException();
        }

        @Test
        void processDeletedShouldHandleNullId() {
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    null,
                    null,
                    "node",
                    1,
                    "Title",
                    null,
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            // When/Then - should not throw
            assertThatCode(() -> processor.processDeleted(dto, createContext())).doesNotThrowAnyException();
        }

        @Test
        void processDeletedShouldSyncBidirectionalRelationships() {
            // Given - create issue with labels (ManyToMany relationship)
            Long issueId = FIXTURE_ISSUE_ID;
            Issue existing = new Issue();
            existing.setNativeId(issueId);
            existing.setNumber(21);
            existing.setTitle("Issue with labels");
            existing.setState(Issue.State.OPEN);
            existing.setHtmlUrl("https://example.com");
            existing.setRepository(testRepository);
            existing.setProvider(githubProvider);
            existing = issueRepository.save(existing);

            // Create a label and associate it with the issue
            Label label = new Label();
            label.setNativeId(100001L);
            label.setProvider(githubProvider);
            label.setName("bug");
            label.setColor("d73a4a");
            label.setRepository(testRepository);
            label = labelRepository.save(label);

            // Use helper method for proper bidirectional sync
            existing.addLabel(label);
            existing = issueRepository.save(existing);

            // Verify setup
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 21))
                    .isPresent();
            assertThat(labelRepository.findById(label.getId())).isPresent();

            // When - delete via processDeleted (uses natural key lookup with synthetic PKs)
            GitHubIssueDTO deleteDto = createBasicIssueDto(issueId, 21);
            assertThatCode(() -> processor.processDeleted(deleteDto, createContext()))
                    .doesNotThrowAnyException();

            // Then - issue deleted, label still exists
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), 21))
                    .isEmpty();
            assertThat(labelRepository.findById(label.getId())).isPresent();
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleNullDtoIds() {
            // When - DTO with null IDs (getDatabaseId() will return null)
            GitHubIssueDTO nullIdDto = new GitHubIssueDTO(
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(nullIdDto, createContext());

            assertThat(result).isNull();
        }

        @Test
        void shouldDefaultToOpenStateForNullState() {
            Long issueId = 111222333L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    1,
                    "Title",
                    null,
                    null, // null state
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Issue.State.OPEN);
        }

        @Test
        void shouldDefaultToOpenStateForUnknownState() {
            Long issueId = 222333444L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    2,
                    "Title",
                    null,
                    "UNKNOWN_STATE",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Issue.State.OPEN);
        }

        @Test
        void shouldHandleUnknownStateReason() {
            Long issueId = 333444555L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    3,
                    "Title",
                    null,
                    "closed",
                    "some_weird_reason",
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    false, // locked
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getStateReason()).isEqualTo(Issue.StateReason.UNKNOWN);
        }

        @Test
        void shouldHandleEmptyAssigneesList() {
            Long issueId = 444555666L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    4,
                    "Title",
                    null,
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    List.of(), // empty assignees
                    null,
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getAssignees()).isEmpty();
        }

        @Test
        void shouldHandleEmptyLabelsList() {
            Long issueId = 555666777L;
            GitHubIssueDTO dto = new GitHubIssueDTO(
                    issueId,
                    null,
                    "node",
                    5,
                    "Title",
                    null,
                    "open",
                    null,
                    "https://example.com",
                    0,
                    Instant.now(),
                    Instant.now(),
                    null,
                    false, // locked
                    createAuthorDto(),
                    null,
                    List.of(), // empty labels
                    null,
                    null,
                    null,
                    null // pullRequest
                    ,
                    null // subIssuesSummary
                    );

            Issue result = processor.process(dto, createContext());
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
