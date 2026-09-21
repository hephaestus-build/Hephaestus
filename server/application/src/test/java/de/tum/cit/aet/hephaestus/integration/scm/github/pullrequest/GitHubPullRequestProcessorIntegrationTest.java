package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for GitHubPullRequestProcessor.
 * <p>
 * Tests the processor independently from the webhook handler to verify:
 * - Pull request upsert logic (create vs update)
 * - Domain event publishing (Created, Updated, Closed, Labeled, etc.)
 * - PR-specific events (Merged, Ready, Drafted, Synchronized)
 * - Context handling and workspace association
 * - Author user association and creation
 * - Label and milestone associations
 * - Edge cases in DTO processing including the critical getDatabaseId() fallback
 */
class GitHubPullRequestProcessorIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final Long FIXTURE_PR_ID = 2969820636L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";

    @Autowired
    private GitHubPullRequestProcessor processor;

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
    private UserRepository userRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private IssueRepository issueRepository;

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
                null, // name
                null // email
                );
    }

    private GitHubPullRequestDTO createBasicPullRequestDto(Long id, int number) {
        return new GitHubPullRequestDTO(
                id, // id (webhook style - no databaseId)
                null, // databaseId (null for webhook payloads)
                "PR_node_" + id,
                number,
                "Test PR #" + number,
                "This is the body of test PR #" + number,
                "open",
                "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number,
                Instant.parse("2025-11-01T21:42:45Z"),
                Instant.parse("2025-11-01T21:42:45Z"),
                null, // closedAt
                null, // mergedAt
                null, // mergedBy
                null, // mergeCommitSha
                false, // isDraft
                false, // isMerged
                null, // mergeable
                false, // locked
                0, // additions
                0, // deletions
                0, // changedFiles
                1, // commits
                0, // commentsCount
                0, // reviewCommentsCount
                createAuthorDto(),
                null, // assignees
                null, // requestedReviewers
                null, // labels
                null, // milestone
                null, // head
                null, // base
                null, // repository
                null, // reviewDecision
                null, // mergeStateStatus
                null, // isMergeable
                false, // maintainerCanModify
                null, // mergeCommitInfo
                null, // headChecks
                null // closingIssueNumbers
                );
    }

    // Critical: getDatabaseId() Fallback Tests

    @Nested
    class GetDatabaseIdFallback {

        @Test
        void shouldUseDatabaseIdWhenPresent() {
            // Given - GraphQL style DTO with databaseId
            Long databaseId = 123456789L;
            GitHubPullRequestDTO dto = new GitHubPullRequestDTO(
                    999L, // id (node id as number, but databaseId is what matters)
                    databaseId, // databaseId
                    "PR_node_xyz",
                    1,
                    "GraphQL PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/1",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.process(dto, createContext());

            // Then - should use databaseId as native_id
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(databaseId);
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), 1))
                    .isPresent();
        }

        @Test
        void shouldFallbackToIdWhenDatabaseIdNull() {
            // Given - Webhook style DTO with only id
            Long webhookId = FIXTURE_PR_ID;
            GitHubPullRequestDTO dto = new GitHubPullRequestDTO(
                    webhookId, // id (this is the database ID in webhooks)
                    null, // databaseId is null in webhooks
                    "PR_kwDOO4CKW86xA93c",
                    26,
                    "Webhook PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            // Verify the fallback works
            assertThat(dto.getDatabaseId()).isEqualTo(webhookId);

            PullRequest result = processor.process(dto, createContext());

            // Then - should use id as fallback for native_id
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(webhookId);
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), 26))
                    .isPresent();
        }

        @Test
        void shouldReturnNullWhenBothIdsNull() {
            // Given - malformed DTO with no IDs
            GitHubPullRequestDTO dto = new GitHubPullRequestDTO(
                    null, // id
                    null, // databaseId
                    "PR_node_xyz",
                    1,
                    "No ID PR",
                    "Body",
                    "open",
                    "https://example.com",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            // Verify fallback returns null
            assertThat(dto.getDatabaseId()).isNull();

            PullRequest result = processor.process(dto, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .isEmpty();
        }
    }

    // Issue Type Promotion Tests

    @Nested
    class IssueTypePromotion {

        @Test
        void shouldPromoteIssueToPullRequestWhenPREventArrives() {
            // Arrange - insert an entity with issue_type='ISSUE' using the Issue upsert
            Long entityId = FIXTURE_PR_ID;
            int number = 26;
            Instant now = Instant.now();

            issueRepository.upsertCore(
                    entityId,
                    providerId(),
                    number,
                    "Original Issue Title",
                    "Original issue body",
                    "OPEN",
                    null, // stateReason
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/" + number,
                    false, // isLocked
                    null, // closedAt
                    0, // commentsCount
                    now, // lastSyncAt
                    now, // createdAt
                    now, // updatedAt
                    null, // authorId
                    testRepository.getId(), // use synthetic PK, not native ID
                    null, // milestoneId
                    null, // issueTypeId
                    null, // parentIssueId
                    null, // subIssuesTotal
                    null, // subIssuesCompleted
                    null // subIssuesPercentCompleted
                    );

            // Verify entity exists as an Issue but NOT as a PullRequest
            assertThat(issueRepository.findByRepositoryIdAndNumber(testRepository.getId(), number))
                    .isPresent();
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), number))
                    .isEmpty();

            // Act - process as a pull request
            GitHubPullRequestDTO dto = createBasicPullRequestDto(entityId, number);
            PullRequest result = processor.process(dto, createContext());

            // Assert - should succeed (no IllegalStateException) and return a valid PR
            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(entityId);
            assertThat(result.getNumber()).isEqualTo(number);
            assertThat(result.getTitle()).isEqualTo("Test PR #" + number);

            // Verify it's now findable as a PullRequest
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), number))
                    .isPresent();

            // Verify Created event was published (treated as new from PR perspective)
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
        }
    }

    // Process (Create/Update) Tests

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldCreateNewPullRequestAndPublishCreatedEvent() {
            GitHubPullRequestDTO dto = createBasicPullRequestDto(FIXTURE_PR_ID, 26);

            PullRequest result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(FIXTURE_PR_ID);
            assertThat(result.getNumber()).isEqualTo(26);
            assertThat(result.getTitle()).isEqualTo("Test PR #26");
            assertThat(result.getState()).isEqualTo(PullRequest.State.OPEN);
            assertThat(result.isDraft()).isFalse();
            assertThat(result.isMerged()).isFalse();
            assertThat(result.requireRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

            // Verify Created event
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .isEmpty();
        }

        @Test
        void shouldCreateAuthorWhenProcessingNewPullRequest() {
            assertThat(userRepository.count()).isZero();
            GitHubPullRequestDTO dto = createBasicPullRequestDto(FIXTURE_PR_ID, 26);

            PullRequest result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getAuthor()).isNotNull();
            assertThat(result.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(userRepository.findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, providerId()))
                    .isPresent();
        }

        @Test
        void shouldCreateLabelsWhenProcessingNewPullRequest() {
            Long labelId = 9568029313L;
            String labelName = "bug";
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(labelId, "LA_node", labelName, "Bug label", "d73a4a", null, null);

            GitHubPullRequestDTO dto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "PR with labels",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(labelDto),
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getLabels()).hasSize(1);
            Label label = result.getLabels().iterator().next();
            assertThat(label.getName()).isEqualTo(labelName);
            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                    .isPresent();
        }

        @Test
        void shouldCreateMilestoneWhenProcessingNewPullRequest() {
            Long milestoneId = 14028563L;
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    2,
                    "v1.0",
                    "First release",
                    "open",
                    Instant.now().plusSeconds(86400 * 30), // dueOn
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/2",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            GitHubPullRequestDTO dto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "PR with milestone",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    milestoneDto,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getMilestone()).isNotNull();
            assertThat(result.getMilestone().getTitle()).isEqualTo("v1.0");
            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent();
        }
    }

    // Process Closed Tests

    @Nested
    class ProcessClosed {

        @Test
        void shouldPublishClosedEventWhenPRClosedWithoutMerge() {
            // Given - create PR first
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 26), createContext());
            eventListener.clear();

            GitHubPullRequestDTO closedDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Closed PR",
                    "Body",
                    "closed",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    Instant.now().plusSeconds(60),
                    null, // closedAt set, mergedAt null
                    null,
                    null, // mergedBy, mergeCommitSha
                    false,
                    false, // not merged
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.processClosed(closedDto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(PullRequest.State.CLOSED);
            assertThat(result.isMerged()).isFalse();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestMerged.class))
                    .isEmpty();
        }

        @Test
        void shouldPublishMergedEventWhenPRIsMerged() {
            // Given - create PR first
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 26), createContext());
            eventListener.clear();

            Instant now = Instant.now();
            GitHubPullRequestDTO mergedDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Merged PR",
                    "Body",
                    "closed",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    now,
                    now.plusSeconds(60),
                    now.plusSeconds(60),
                    now.plusSeconds(60), // both closedAt and mergedAt set
                    null,
                    null, // mergedBy, mergeCommitSha
                    false,
                    true, // merged = true
                    null,
                    false,
                    10,
                    5,
                    3,
                    2,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.processClosed(mergedDto, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(PullRequest.State.CLOSED);
            assertThat(result.isMerged()).isTrue();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestMerged.class))
                    .hasSize(1);
        }
    }

    // Process Ready For Review Tests

    @Nested
    class ProcessReadyForReview {

        @Test
        void shouldPublishPullRequestReadyEvent() {
            // Given - create draft PR first
            GitHubPullRequestDTO draftDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Draft PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null, // mergedBy, mergeCommitSha
                    true,
                    false, // isDraft = true
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );
            processor.process(draftDto, createContext());
            eventListener.clear();

            GitHubPullRequestDTO readyDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Ready PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    null,
                    null,
                    null,
                    null, // mergedBy, mergeCommitSha
                    false,
                    false, // isDraft = false now
                    null,
                    false,
                    5,
                    2,
                    1,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.processReadyForReview(readyDto, createContext());
            assertNotNull(result);

            assertThat(result.isDraft()).isFalse();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReady.class))
                    .hasSize(1);
        }
    }

    // Process Converted To Draft Tests

    @Nested
    class ProcessConvertedToDraft {

        @Test
        void shouldPublishPullRequestDraftedEvent() {
            // Given - create PR first
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 26), createContext());
            eventListener.clear();

            GitHubPullRequestDTO draftDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Now Draft PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    null,
                    null,
                    null,
                    null, // mergedBy, mergeCommitSha
                    true,
                    false, // isDraft = true
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.processConvertedToDraft(draftDto, createContext());
            assertNotNull(result);

            assertThat(result.isDraft()).isTrue();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestDrafted.class))
                    .hasSize(1);
        }
    }

    // Process Synchronize Tests

    @Nested
    class ProcessSynchronize {

        @Test
        void shouldPublishPullRequestSynchronizedEvent() {
            // Given - create PR first
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 26), createContext());
            eventListener.clear();

            GitHubPullRequestDTO syncDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Synced PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    null,
                    null,
                    null,
                    null, // mergedBy, mergeCommitSha
                    false,
                    false,
                    null,
                    false,
                    15,
                    8,
                    5,
                    3, // More commits now
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            PullRequest result = processor.processSynchronize(syncDto, createContext());
            assertNotNull(result);

            assertThat(result.getCommits()).isEqualTo(3);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestSynchronized.class))
                    .hasSize(1);
        }
    }

    // Process Labeled/Unlabeled Tests

    @Nested
    class ProcessLabelEvents {

        @Test
        void shouldPublishLabeledEvent() {
            // Given - create PR first
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 26), createContext());
            eventListener.clear();

            Long labelId = 9568029313L;
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(labelId, "LA_node", "enhancement", "Enhancement", "0e8a16", null, null);

            GitHubPullRequestDTO labeledDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Labeled PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(labelDto),
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            processor.processLabeled(labeledDto, labelDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestLabeled.class))
                    .hasSize(1);
        }

        @Test
        void shouldPublishUnlabeledEvent() {
            // Given - create PR with label first
            Long labelId = 9568029313L;
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(labelId, "LA_node", "enhancement", "Enhancement", "0e8a16", null, null);

            GitHubPullRequestDTO withLabelDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Labeled PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(labelDto),
                    null,
                    null,
                    null,
                    null,
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );
            processor.process(withLabelDto, createContext());
            eventListener.clear();

            GitHubPullRequestDTO unlabeledDto = new GitHubPullRequestDTO(
                    FIXTURE_PR_ID,
                    null,
                    "PR_node",
                    26,
                    "Unlabeled PR",
                    "Body",
                    "open",
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/26",
                    Instant.now(),
                    Instant.now().plusSeconds(60),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    false,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    createAuthorDto(),
                    null,
                    null,
                    List.of(), // labels
                    null, // milestone
                    null, // head
                    null, // base
                    null, // repository
                    null, // reviewDecision
                    null, // mergeStateStatus
                    null, // isMergeable
                    false, // maintainerCanModify
                    null, // mergeCommitInfo
                    null, // headChecks
                    null // closingIssueNumbers
                    );

            processor.processUnlabeled(unlabeledDto, labelDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUnlabeled.class))
                    .hasSize(1);
        }
    }

    private Long providerId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}
