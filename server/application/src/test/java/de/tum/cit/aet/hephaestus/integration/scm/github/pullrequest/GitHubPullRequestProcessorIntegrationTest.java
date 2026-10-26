package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHReviewRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

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
    private GitHubIssueCommentProcessor commentProcessor;

    @Autowired
    private IssueCommentRepository commentRepository;

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
    private MeterRegistry meters;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("hephaestus-test");
        testWorkspace.setDisplayName("Hephaestus Test");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganizationId(testOrganization.getId());
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
        return createBasicPullRequestDto(
                id, number, "This is the body of test PR #" + number, Instant.parse("2025-11-01T21:42:45Z"));
    }

    private GitHubPullRequestDTO createBasicPullRequestDto(
            Long id, int number, @Nullable String body, Instant updatedAt) {
        return new GitHubPullRequestDTO(
                id, // id (webhook style - no databaseId)
                null, // databaseId (null for webhook payloads)
                "PR_node_" + id,
                number,
                "Test PR #" + number,
                body,
                "open",
                "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number,
                Instant.parse("2025-11-01T21:42:45Z"),
                updatedAt,
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
                null, // closingIssueNumbers
                null // requestedTeams
                );
    }

    @Nested
    class GetDatabaseIdFallback {

        @Test
        void shouldUseDatabaseIdWhenPresent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            PullRequest result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(databaseId);
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), 1))
                    .isPresent();
        }

        @Test
        void shouldFallbackToIdWhenDatabaseIdNull() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            assertThat(dto.getDatabaseId()).isEqualTo(webhookId);

            PullRequest result = processor.process(dto, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(webhookId);
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), 26))
                    .isPresent();
        }

        @Test
        void shouldReturnNullWhenBothIdsNull() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            assertThat(dto.getDatabaseId()).isNull();

            PullRequest result = processor.process(dto, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .isEmpty();
        }
    }

    @Nested
    class IssueTypePromotion {

        @Test
        void shouldPromoteIssueToPullRequestWhenPREventArrives() {
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

            GitHubPullRequestDTO dto = createBasicPullRequestDto(entityId, number);
            PullRequest result = processor.process(dto, createContext());

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

    @Nested
    class CanonicalNativeId {

        private static final long ISSUE_RESOURCE_ID = 880_001L;
        private static final long PULL_REQUEST_ID = 990_001L;
        private final Instant stubUpdatedAt = Instant.parse("2025-11-01T21:42:45Z");

        private GitHubCommentDTO comment(int number) {
            return new GitHubCommentDTO(
                    770_000L + number,
                    "comment-" + number,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number + "#issuecomment-" + number,
                    "Comment first",
                    null,
                    "OWNER",
                    stubUpdatedAt,
                    stubUpdatedAt);
        }

        private GitHubIssueDTO issueEnvelope(int number) {
            return new GitHubIssueDTO(
                    ISSUE_RESOURCE_ID + number,
                    ISSUE_RESOURCE_ID + number,
                    "issue-" + number,
                    number,
                    "Comment-first title",
                    "stub body",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number,
                    0,
                    stubUpdatedAt,
                    stubUpdatedAt,
                    null,
                    false,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    new GitHubIssueDTO.PullRequestRef(
                            "https://api.github.com/repos/" + FIXTURE_REPO_FULL_NAME + "/pulls/" + number,
                            "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number),
                    null);
        }

        private PullRequest stub(int number) {
            assertNotNull(commentProcessor.processWithParentCreation(
                    comment(number), issueEnvelope(number), createContext()));
            return stored(number);
        }

        private PullRequest stored(int number) {
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), number)
                    .orElseThrow();
        }

        @ParameterizedTest
        @ValueSource(longs = {-60, 0, 60})
        void shouldAdoptThePullRequestIdAtAnyAgeAndKeepTheRowAndItsNewerContent(long seconds) {
            PullRequest stubbed = stub(30);
            Long commentId = Objects.requireNonNull(commentRepository
                    .findByNativeIdAndProviderId(770_030L, providerId())
                    .orElseThrow()
                    .getId());
            stubbed.setState(Issue.State.CLOSED);
            stubbed.setHeadRefOid("a".repeat(40));
            pullRequestRepository.saveAndFlush(stubbed);
            eventListener.clear();

            var full = createBasicPullRequestDto(PULL_REQUEST_ID, 30, "full body", stubUpdatedAt.plusSeconds(seconds));
            processor.process(full, createContext());
            processor.process(full, createContext());

            PullRequest hydrated = stored(30);
            assertThat(hydrated.getId()).isEqualTo(stubbed.getId());
            assertThat(hydrated.getNativeId()).isEqualTo(PULL_REQUEST_ID);
            commentProcessor.processWithParentCreation(comment(30), issueEnvelope(30), createContext());
            var retainedComment = commentRepository
                    .findByNativeIdAndProviderId(770_030L, providerId())
                    .orElseThrow();
            assertThat(retainedComment.getId()).isEqualTo(commentId);
            assertThat(Objects.requireNonNull(retainedComment.getIssue()).getId())
                    .isEqualTo(stubbed.getId());
            assertThat(stored(30).getNativeId()).isEqualTo(PULL_REQUEST_ID);
            // An older or equally old payload repairs the id and leaves the content it would have
            // overwritten.
            assertThat(hydrated.getBody()).isEqualTo(seconds > 0 ? "full body" : "stub body");
            assertThat(hydrated.getTitle()).isEqualTo(seconds > 0 ? "Test PR #30" : "Comment-first title");
            assertThat(hydrated.getState()).isEqualTo(seconds > 0 ? Issue.State.OPEN : Issue.State.CLOSED);
            assertThat(hydrated.getHeadRefOid()).isEqualTo("a".repeat(40));
            assertThat(hydrated.getUpdatedAt())
                    .isEqualTo(seconds > 0 ? stubUpdatedAt.plusSeconds(seconds) : stubUpdatedAt);
            if (seconds <= 0) {
                assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                        .isEmpty();
            }
        }

        @Test
        void shouldKeepAPromotedIssueRowWhenItsPullRequestIdDiffers() {
            Instant now = Instant.now();
            issueRepository.upsertCore(
                    ISSUE_RESOURCE_ID,
                    providerId(),
                    31,
                    "Issue first",
                    "Issue body",
                    "OPEN",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/issues/31",
                    false,
                    null,
                    0,
                    now,
                    now,
                    now,
                    null,
                    testRepository.getId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            Long issueRowId = Objects.requireNonNull(issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 31)
                    .orElseThrow()
                    .getId());

            var originalComment = Objects.requireNonNull(commentProcessor.process(comment(31), 31, createContext()));
            processor.process(createBasicPullRequestDto(PULL_REQUEST_ID, 31), createContext());

            PullRequest promoted = stored(31);
            assertThat(promoted.getId()).isEqualTo(issueRowId);
            assertThat(promoted.getNativeId()).isEqualTo(PULL_REQUEST_ID);
            var retainedComment = commentRepository
                    .findById(Objects.requireNonNull(originalComment.getId()))
                    .orElseThrow();
            assertThat(Objects.requireNonNull(retainedComment.getIssue()).getId())
                    .isEqualTo(issueRowId);
        }

        @Test
        void shouldKeepTheSameNumericIdOwnedByAnotherProviderSeparate() {
            IdentityProvider otherProvider = gitProviderRepository.save(
                    new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com"));
            Repository otherRepository = new Repository();
            otherRepository.setProvider(otherProvider);
            otherRepository.setNativeId(730_001L);
            otherRepository.setName("OtherRepository");
            otherRepository.setNameWithOwner("other/OtherRepository");
            otherRepository.setHtmlUrl("https://gitlab.example.com/other/OtherRepository");
            otherRepository.setVisibility(Repository.Visibility.PUBLIC);
            otherRepository = repositoryRepository.save(otherRepository);
            Issue otherIssue = new Issue();
            otherIssue.setProvider(otherProvider);
            otherIssue.setNativeId(PULL_REQUEST_ID);
            otherIssue.setRepository(otherRepository);
            otherIssue.setNumber(30);
            otherIssue.setTitle("Other provider issue");
            otherIssue.setState(Issue.State.OPEN);
            otherIssue.setHtmlUrl("https://gitlab.example.com/other/OtherRepository/-/issues/30");
            otherIssue = issueRepository.saveAndFlush(otherIssue);
            PullRequest stubbed = stub(30);

            processor.process(createBasicPullRequestDto(PULL_REQUEST_ID, 30), createContext());

            assertThat(stored(30).getId()).isEqualTo(stubbed.getId());
            assertThat(stored(30).getNativeId()).isEqualTo(PULL_REQUEST_ID);
            assertThat(issueRepository
                            .findById(Objects.requireNonNull(otherIssue.getId()))
                            .orElseThrow()
                            .getNativeId())
                    .isEqualTo(PULL_REQUEST_ID);
            assertThat(stored(30).getProvider().getId()).isEqualTo(providerId());
        }

        @Test
        void shouldRefuseAPullRequestIdAnotherRowOfTheProviderOwns() {
            PullRequest owner = Objects.requireNonNull(
                    processor.process(createBasicPullRequestDto(PULL_REQUEST_ID, 32), createContext()));
            PullRequest stubbed = stub(33);

            assertThatThrownBy(() -> processor.process(
                            createBasicPullRequestDto(PULL_REQUEST_ID, 33, "full body", stubUpdatedAt.plusSeconds(60)),
                            createContext()))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(stored(32).getId()).isEqualTo(owner.getId());
            assertThat(stored(32).getNativeId()).isEqualTo(PULL_REQUEST_ID);
            assertThat(stored(33).getId()).isEqualTo(stubbed.getId());
            assertThat(stored(33).getNativeId()).isEqualTo(ISSUE_RESOURCE_ID + 33);
            assertThat(stored(33).getBody()).isEqualTo("stub body");
            assertThat(commentRepository
                            .findByNativeIdAndProviderId(770_033L, providerId())
                            .orElseThrow()
                            .getIssue())
                    .isNotNull()
                    .extracting(Issue::getId)
                    .isEqualTo(stubbed.getId());
        }

        private Repository staleRepository() {
            Repository renamed = new Repository();
            renamed.setNativeId(FIXTURE_REPO_ID + 1);
            renamed.setName("TestRepository-archive");
            renamed.setNameWithOwner(FIXTURE_REPO_FULL_NAME + "-archive");
            renamed.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "-archive");
            renamed.setVisibility(Repository.Visibility.PUBLIC);
            renamed.setDefaultBranch("main");
            renamed.setCreatedAt(Instant.now());
            renamed.setUpdatedAt(Instant.now());
            renamed.setPushedAt(Instant.now());
            renamed.setOrganization(testOrganization);
            renamed.setProvider(githubProvider);
            return repositoryRepository.save(renamed);
        }

        @Test
        void shouldMoveAPullRequestFiledUnderAStaleRepositoryNameToTheRepositoryGitHubReports() {
            Repository stale = staleRepository();
            double moved = misfiledCount("moved", "none");
            PullRequest misfiled = Objects.requireNonNull(processor.process(
                    createBasicPullRequestDto(PULL_REQUEST_ID, 40),
                    ProcessingContext.forSync(testWorkspace.getId(), stale)));

            PullRequest synced = processor.process(
                    createBasicPullRequestDto(PULL_REQUEST_ID, 40, "synced body", stubUpdatedAt), createContext());

            assertThat(synced).isNotNull().extracting(PullRequest::getId).isEqualTo(misfiled.getId());
            assertThat(stored(40).getId()).isEqualTo(misfiled.getId());
            assertThat(stored(40).getNativeId()).isEqualTo(PULL_REQUEST_ID);
            assertThat(stored(40).getBody()).isEqualTo("synced body");
            assertThat(pullRequestRepository.findByRepositoryIdAndNumber(stale.getId(), 40))
                    .isEmpty();
            assertThat(misfiledCount("moved", "none")).isEqualTo(moved + 1);
        }

        @Test
        void shouldSkipOnlyThatPullRequestWhenItsRepositoryAlreadyHasARowForTheNumber() {
            Repository stale = staleRepository();
            double skipped = misfiledCount("skipped", "idHeldByAnotherRepository");
            PullRequest misfiled = Objects.requireNonNull(processor.process(
                    createBasicPullRequestDto(PULL_REQUEST_ID, 41),
                    ProcessingContext.forSync(testWorkspace.getId(), stale)));
            PullRequest stubbed = stub(41);

            assertThat(processor.process(
                            createBasicPullRequestDto(PULL_REQUEST_ID, 41, "synced body", stubUpdatedAt),
                            createContext()))
                    .isNull();

            assertThat(pullRequestRepository
                            .findByRepositoryIdAndNumber(stale.getId(), 41)
                            .orElseThrow()
                            .getId())
                    .isEqualTo(misfiled.getId());
            assertThat(stored(41).getId()).isEqualTo(stubbed.getId());
            assertThat(stored(41).getBody()).isEqualTo("stub body");
            assertThat(misfiledCount("skipped", "idHeldByAnotherRepository")).isEqualTo(skipped + 1);
        }

        private double misfiledCount(String outcome, String reason) {
            var counter = meters.find("github.misfiled.work")
                    .tag("type", "pull_request")
                    .tag("outcome", outcome)
                    .tag("reason", reason)
                    .counter();
            return counter == null ? 0 : counter.count();
        }
    }

    @Nested
    class ProcessMethodCreate {

        @Test
        void shouldAcceptClosingIssuesOnlyFromSyncWhenTheWebhookStoredTheSameUpdatedAt() {
            // The webhook wrote GitHub's updated_at first ...
            GitHubPullRequestDTO fromWebhook = createBasicPullRequestDto(FIXTURE_PR_ID, 26);
            processor.process(
                    fromWebhook, ProcessingContext.forWebhook(testWorkspace.getId(), testRepository, "edited"));
            Issue closed = new Issue();
            closed.setNativeId(9_100L);
            closed.setNumber(3);
            closed.setTitle("Issue #3");
            closed.setState(Issue.State.OPEN);
            closed.setRepository(testRepository);
            closed.setProvider(githubProvider);
            closed = issueRepository.save(closed);

            // ... and the sync row carries the same moment, with what only it reads.
            GitHubPullRequestDTO fromSync = new GitHubPullRequestDTO(
                    fromWebhook.id(),
                    fromWebhook.databaseId(),
                    fromWebhook.nodeId(),
                    fromWebhook.number(),
                    fromWebhook.title(),
                    fromWebhook.body(),
                    fromWebhook.state(),
                    fromWebhook.htmlUrl(),
                    fromWebhook.createdAt(),
                    fromWebhook.updatedAt(),
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
                    ReviewDecision.APPROVED,
                    MergeStateStatus.CLEAN,
                    true,
                    false,
                    null,
                    new GitHubPullRequestDTO.HeadChecks("f".repeat(40), CheckState.SUCCESS),
                    List.of(3), // closingIssueNumbers
                    null // requestedTeams
                    );

            processor.process(fromSync, ProcessingContext.forWebhook(testWorkspace.getId(), testRepository, "edited"));
            PullRequest webhookStored = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 26)
                    .orElseThrow();
            assertThat(pullRequestRepository.findClosingIssuesById(webhookStored.getId()))
                    .isEmpty();
            eventListener.clear();

            PullRequest result = processor.process(fromSync, createContext());

            assertNotNull(result);
            PullRequest stored =
                    pullRequestRepository.findByIdWithAllForGate(result.getId()).orElseThrow();
            assertThat(stored.getReviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            assertThat(stored.getMergeStateStatus()).isEqualTo(MergeStateStatus.CLEAN);
            assertThat(stored.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
            assertThat(stored.getHeadCheckSha()).isEqualTo("f".repeat(40));
            assertThat(pullRequestRepository.findClosingIssuesById(stored.getId()))
                    .extracting(Issue::getId)
                    .containsExactly(closed.getId());
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .filteredOn(event -> event.changedFields().contains("closingIssues"))
                    .singleElement()
                    .satisfies(event -> assertThat(event.context().isSync()).isTrue());
        }

        @Test
        void shouldStoreOnlyTheSyncedTeamsWhenTheSyncReadsTeamsAmongTheReviewRequests() {
            Long syncedId = saveSyncedTeam();
            GHPullRequest read = readBySync(27, 0);

            PullRequest result = processor.process(
                    Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(read)), createContext());

            assertNotNull(result);
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest stored =
                        pullRequestRepository.findById(result.getId()).orElseThrow();
                assertThat(stored.getRequestedTeams())
                        .as("the unsynced team is left out")
                        .extracting(request -> request.getTeam().getId())
                        .containsExactly(syncedId);
                assertThat(stored.getRequestedReviewers())
                        .as("the bot and the mannequin are not people asked to review")
                        .extracting(request -> request.getUser().getLogin())
                        .containsExactly("FelixTJDietrich");
            });
        }

        @Test
        void shouldKeepTheStoredReviewRequestsWhenASyncCannotReadThemWhole() {
            Long syncedId = saveSyncedTeam();
            processor.process(
                    Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(readBySync(28, 0))), createContext());
            GHPullRequest truncated = readBySync(28, 60);
            GHReviewRequestConnection requests = truncated.getReviewRequests();
            requests.setNodes(requests.getNodes().subList(2, 4));
            requests.setTotalCount(101);

            PullRequest result = processor.process(
                    Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(truncated)), createContext());

            assertNotNull(result);
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest stored =
                        pullRequestRepository.findById(result.getId()).orElseThrow();
                assertThat(stored.getRequestedTeams())
                        .extracting(request -> request.getTeam().getId())
                        .containsExactly(syncedId);
                assertThat(stored.getRequestedReviewers())
                        .extracting(request -> request.getUser().getLogin())
                        .containsExactly("FelixTJDietrich");
            });
        }

        enum Page {
            OLDER(Duration.ofMinutes(-1)),
            EQUAL(Duration.ZERO),
            NEWER(Duration.ofSeconds(1));

            private final Duration offset;

            Page(Duration offset) {
                this.offset = offset;
            }
        }

        @ParameterizedTest
        @EnumSource(Page.class)
        void shouldApplyASyncPagesReviewRequestsUnlessItWasReadBeforeTheStoredWebhook(Page page) {
            Instant storedAt = Instant.parse("2025-11-01T21:43:00Z");
            Long syncedId = saveSyncedTeam();
            processor.process(
                    Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(readBySync(29, 0))),
                    ProcessingContext.forWebhook(testWorkspace.getId(), testRepository, "review_requested")
                            .withObservedAt(storedAt));
            GHPullRequest fewer = readBySync(29, 0);
            GHReviewRequestConnection requests = fewer.getReviewRequests();
            requests.setNodes(requests.getNodes().subList(2, 4));
            requests.setTotalCount(2);
            GitHubPullRequestDTO read = Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(fewer));
            ProcessingContext sync = createContext().withObservedAt(storedAt.plus(page.offset));

            processor.process(read, sync);
            PullRequest result = processor.process(read, sync);

            assertNotNull(result);
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest stored =
                        pullRequestRepository.findById(result.getId()).orElseThrow();
                if (page == Page.OLDER) {
                    assertThat(stored.getRequestedTeams())
                            .extracting(request -> request.getTeam().getId())
                            .containsExactly(syncedId);
                    assertThat(stored.getRequestedReviewers())
                            .extracting(request -> request.getUser().getLogin())
                            .containsExactly("FelixTJDietrich");
                } else {
                    assertThat(stored.getRequestedTeams()).isEmpty();
                    assertThat(stored.getRequestedReviewers()).isEmpty();
                }
            });
        }

        /** The team {@code Hephaetus} among the review requests, as the team sync stores it. */
        private Long saveSyncedTeam() {
            Team synced = new Team();
            synced.setNativeId(13459148L);
            synced.setName("Hephaetus");
            synced.setSlug("hephaetus");
            synced.setOrganization(FIXTURE_ORG_LOGIN);
            synced.setHtmlUrl("https://github.com/orgs/" + FIXTURE_ORG_LOGIN + "/teams/hephaetus");
            synced.setPrivacy(Team.Privacy.VISIBLE);
            synced.setProvider(githubProvider);
            return teamRepository.save(synced).getId();
        }

        private GHPullRequest readBySync(int number, int minutesLater) {
            GHPullRequest read = GitHubPullRequestDTOReviewRequestsTest.pullRequestAskingForReviews(number);
            read.setFullDatabaseId(BigInteger.valueOf(FIXTURE_PR_ID + number));
            read.setId("PR_kwDOO4CKW86_" + number);
            read.setTitle("Asks for reviews");
            read.setState(GHPullRequestState.OPEN);
            read.setUrl(URI.create("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/pull/" + number));
            read.setCreatedAt(OffsetDateTime.parse("2025-11-01T21:42:45Z"));
            read.setUpdatedAt(OffsetDateTime.parse("2025-11-01T21:42:45Z").plusMinutes(minutesLater));
            read.setAuthor(GitHubGraphQlTestMapper.create().readValue("""
                {"__typename": "User", "id": "U_kgDOAAB5Fw", "databaseId": 31031,
                 "login": "pull-request-author"}
                """, GHActor.class));
            return read;
        }

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
                    null, // closingIssueNumbers
                    null // requestedTeams
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            PullRequest result = processor.process(dto, createContext());
            assertNotNull(result);

            assertThat(result.getMilestone()).isNotNull();
            assertThat(result.getMilestone().getTitle()).isEqualTo("v1.0");
            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent();
        }
    }

    @Nested
    class ProcessClosed {

        @Test
        void shouldPublishClosedEventWhenPRClosedWithoutMerge() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
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
                    null, // closingIssueNumbers
                    null // requestedTeams
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

    @Nested
    class ProcessReadyForReview {

        @Test
        void shouldPublishReadyWhenSyncStoredAnEmptyDescriptionAndTheWebhookSendsNone() {
            Instant updatedAt = Instant.parse("2025-11-02T09:00:00Z");
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 28, "", updatedAt), createContext());
            eventListener.clear();

            // The webhook for the same change sends no description as null.
            processor.processReadyForReview(
                    createBasicPullRequestDto(FIXTURE_PR_ID, 28, null, updatedAt),
                    ProcessingContext.forWebhook(testWorkspace.getId(), testRepository, "ready_for_review"));

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReady.class))
                    .hasSize(1);
        }

        @Test
        void shouldPublishPullRequestReadyEvent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            PullRequest result = processor.processReadyForReview(readyDto, createContext());
            assertNotNull(result);

            assertThat(result.isDraft()).isFalse();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReady.class))
                    .hasSize(1);
        }
    }

    @Nested
    class ProcessConvertedToDraft {

        @Test
        void shouldPublishPullRequestDraftedEvent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            PullRequest result = processor.processConvertedToDraft(draftDto, createContext());
            assertNotNull(result);

            assertThat(result.isDraft()).isTrue();
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestDrafted.class))
                    .hasSize(1);
        }
    }

    @Nested
    class ProcessSynchronize {

        @Test
        void shouldPublishPullRequestSynchronizedEvent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            PullRequest result = processor.processSynchronize(syncDto, createContext());
            assertNotNull(result);

            assertThat(result.getCommits()).isEqualTo(3);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestSynchronized.class))
                    .hasSize(1);
        }
    }

    @Nested
    class ProcessEdited {

        @Test
        void shouldReportAnEditedBodyButNotAnUpdateThatLeftTheTextAlone() {
            processor.process(createBasicPullRequestDto(FIXTURE_PR_ID, 27), createContext());
            eventListener.clear();
            String repaired = "Adds the thing so reviewers can tell why. Closes #5";

            processor.process(
                    createBasicPullRequestDto(FIXTURE_PR_ID, 27, repaired, Instant.parse("2025-11-01T21:50:00Z")),
                    createContext());
            processor.process(
                    createBasicPullRequestDto(FIXTURE_PR_ID, 27, repaired, Instant.parse("2025-11-01T21:55:00Z")),
                    createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUpdated.class))
                    .extracting(ScmDomainEvent.PullRequestUpdated::changedFields)
                    .containsExactly(Set.of("body"));
        }
    }

    @Nested
    class ProcessLabelEvents {

        @Test
        void shouldPublishLabeledEvent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
                    );

            processor.processLabeled(labeledDto, labelDto, createContext());

            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestLabeled.class))
                    .hasSize(1);
        }

        @Test
        void shouldPublishUnlabeledEvent() {
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
                    null, // closingIssueNumbers
                    null // requestedTeams
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
                    null, // closingIssueNumbers
                    null // requestedTeams
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
