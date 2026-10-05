package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookProject;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabMergeRequestProcessorTest extends BaseUnitTest {

    private static final long REPO_ID = 1L;
    private static final long RAW_MR_ID = 999555L;
    private static final String APPROVAL_HEAD = "a".repeat(40);
    private static final long ENTITY_MR_ID = 100L;
    private static final int MR_IID = 5;
    private static final long RAW_USER_ID = 12345L;
    private static final long ENTITY_USER_ID = 200L;
    private static final Long PROVIDER_ID = 2L;
    private static final long RAW_APPROVER_ID = 11111L;
    private static final long ENTITY_APPROVER_ID = 300L;

    @Mock
    private GitLabUserService gitLabUserService;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewRepository reviewRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LabelRepository labelRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ScopeIdResolver scopeIdResolver;

    @Mock
    private RepositoryScopeFilter repositoryScopeFilter;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitLabMergeRequestProcessor processor;
    private Repository testRepo;
    private IdentityProvider gitLabProvider;

    @BeforeEach
    void setUp() {
        GitLabProperties properties = new GitLabProperties(
                "https://gitlab.com",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(200),
                Duration.ofMinutes(5));

        processor = new GitLabMergeRequestProcessor(
                gitLabUserService,
                pullRequestRepository,
                reviewRepository,
                milestoneRepository,
                issueRepository,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                properties,
                eventPublisher);

        gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(PROVIDER_ID);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl("https://gitlab.com");

        testRepo = new Repository();
        testRepo.setId(REPO_ID);
        testRepo.setNameWithOwner("gitlab-org/gitlab");
        testRepo.setProvider(gitLabProvider);
        testRepo.setDefaultBranch("main");

        // Default: upsertCore succeeds
        lenient()
                .when(pullRequestRepository.upsertCore(
                        anyLong(),
                        anyLong(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(1);

        // upsertUser is void - no stubbing needed
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "open, opened, OPEN",
                "close, closed, CLOSED",
                "merge, merged, MERGED",
                "open, locked, CLOSED",
                "open, some_unknown_state, OPEN",
                "open, NULL, OPEN"
            })
    void shouldStoreTheStateGitLabReportsWhenAHookArrives(String action, @Nullable String state, String stored) {
        when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                .thenReturn(Optional.empty());
        when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                .thenReturn(Optional.of(createPullRequestEntity()));

        processor.process(createEvent(action, state, false), createContext());

        verify(pullRequestRepository)
                .upsertCore(
                        eq(RAW_MR_ID),
                        eq(PROVIDER_ID),
                        eq(MR_IID),
                        any(),
                        any(),
                        eq(stored),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(REPO_ID),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Nested
    class WebhookProcessing {

        @Test
        void processCreatesNewPR() {
            PullRequest pr = createPullRequestEntity();
            // 1st: stale check + isNew (process) -> empty (new PR)
            // 2nd: post-upsert fetch (upsertMergeRequest) -> found
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("open", "opened", false);
            PullRequest result = processor.process(event, createContext());

            assertThat(result).isNotNull();
            assertThat(result.getProvider()).isEqualTo(gitLabProvider);

            ArgumentCaptor<ScmDomainEvent.PullRequestCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.PullRequestCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
        }

        @Test
        @DisplayName("a new merge request opened ready is Created only, as a GitHub pull request is")
        void shouldNotRaiseReadyWhenANonDraftMergeRequestIsOpened() {
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(createPullRequestEntity()));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.process(createEvent("open", "opened", false), createContext());

            verify(eventPublisher).publishEvent(any(ScmDomainEvent.PullRequestCreated.class));
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.PullRequestReady.class));
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.PullRequestSynchronized.class));
        }

        @Test
        @DisplayName(
                "an update that pushed commits raises Synchronized, whether GitLab names oldrev or only the head moved")
        void shouldRaiseSynchronizedWhenAnUpdateMovesTheHead() {
            PullRequest pr = createPullRequestEntity();
            pr.setHeadRefOid("a".repeat(40));
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.process(pushEvent("b".repeat(40), null), createContext());

            verify(eventPublisher).publishEvent(any(ScmDomainEvent.PullRequestSynchronized.class));
        }

        @Test
        @DisplayName("an update that pushed nothing, such as a title edit, raises no Synchronized")
        void shouldNotRaiseSynchronizedWhenTheHeadStays() {
            PullRequest pr = createPullRequestEntity();
            pr.setHeadRefOid("a".repeat(40));
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.process(pushEvent("a".repeat(40), null), createContext());

            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.PullRequestSynchronized.class));
        }

        private GitLabMergeRequestEventDTO pushEvent(String head, @Nullable String oldrev) {
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    RAW_MR_ID,
                    MR_IID,
                    "Add awesome feature",
                    "This MR adds an awesome feature",
                    "opened",
                    "update",
                    "feature/awesome-feature",
                    "main",
                    false,
                    RAW_USER_ID,
                    null,
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-16T10:00:00Z",
                    null,
                    null,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    new GitLabMergeRequestEventDTO.LastCommit(head, "Fix", "Fix"),
                    null,
                    oldrev,
                    null,
                    null);
            return new GitLabMergeRequestEventDTO(
                    "merge_request", "merge_request", createUser(), createProject(), attrs, List.of(), null, null);
        }

        @Test
        void processUpdatesExistingPR() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale check + isNew (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("update", "opened", false);
            PullRequest result = processor.process(event, createContext());

            assertThat(result).isNotNull();

            // No PullRequestCreated event since PR already existed
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.PullRequestCreated.class));
        }

        @Test
        void processUpdatesExistingPRPublishesPullRequestUpdated() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale check + isNew (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("update", "opened", false);
            PullRequest result = processor.process(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasPullRequestUpdated =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.PullRequestUpdated);
            assertThat(hasPullRequestUpdated).isTrue();
        }

        @Test
        void processClosedPublishesEvent() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("close", "closed", false);
            PullRequest result = processor.processClosed(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasPullRequestClosed = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof ScmDomainEvent.PullRequestClosed closed && !closed.wasMerged());
            assertThat(hasPullRequestClosed).isTrue();
        }

        @Test
        void processReopenedPublishesEvent() {
            PullRequest pr = createPullRequestEntity();
            pr.setState(Issue.State.CLOSED);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("reopen", "opened", false);
            PullRequest result = processor.processReopened(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasPullRequestReopened =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.PullRequestReopened);
            assertThat(hasPullRequestReopened).isTrue();
        }

        @Test
        @DisplayName(
                "processMerged() stores the merge and publishes PullRequestClosed(wasMerged=true), leaving the merge to offerMerge")
        void processMergedPublishesEvents() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));
            // The row as the upsert leaves it: merged.
            PullRequest merged = createPullRequestEntity();
            merged.setState(Issue.State.MERGED);
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(merged));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabMergeRequestEventDTO event = createEvent("merge", "merged", false);
            PullRequest result = processor.processMerged(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            List<Object> publishedEvents = eventCaptor.getAllValues();

            boolean hasPullRequestClosed = publishedEvents.stream()
                    .anyMatch(e -> e instanceof ScmDomainEvent.PullRequestClosed closed && closed.wasMerged());
            boolean hasPullRequestMerged =
                    publishedEvents.stream().anyMatch(e -> e instanceof ScmDomainEvent.PullRequestMerged);

            assertThat(hasPullRequestClosed).isTrue();
            assertThat(hasPullRequestMerged).isFalse();
        }

        /** The stored merge request at the hook's head, with its author resolvable and a decision on record. */
        private PullRequest storedForApprovalHook() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            pr.setHeadRefOid(APPROVAL_HEAD);
            pr.setReviewDecision(ReviewDecision.REVIEW_REQUIRED);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(createUserEntity()));
            return pr;
        }

        private PullRequestReview standingApproval(PullRequest pr, PullRequestReview.State state) {
            PullRequestReview review = new PullRequestReview();
            review.setNativeId(GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID));
            review.setState(state);
            review.setDismissed(state == PullRequestReview.State.DISMISSED);
            review.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            review.setAuthor(createApproverEntity());
            review.setPullRequest(pr);
            pr.getReviews().add(review);
            return review;
        }

        private List<Object> publishedEvents() {
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            return eventCaptor.getAllValues();
        }

        @Test
        void shouldRecordNoApprovalAndForgetTheDecisionWhenAnApprovalHookArrives() {
            PullRequest pr = storedForApprovalHook();

            PullRequest result = processor.processApproved(
                    createApprovalEvent("approved", "opened", APPROVAL_HEAD), createContext());

            assertThat(result).isNotNull();
            // GitLab fires the hook from the reloaded merge request: the approver may have withdrawn since, so the
            // approver list the readiness read takes next is what records the approval.
            assertThat(pr.getReviews()).isEmpty();
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
            assertThat(publishedEvents()).noneMatch(e -> e instanceof ScmDomainEvent.ReviewSubmitted);
            assertThat(pr.getReviewDecision()).isNull();
        }

        @Test
        void shouldDismissNoApprovalAndForgetTheDecisionWhenAnUnapprovalHookArrives() {
            PullRequest pr = storedForApprovalHook();
            PullRequestReview standing = standingApproval(pr, PullRequestReview.State.APPROVED);

            PullRequest result =
                    processor.processUnapproved(createApprovalEvent("unapproved", "opened"), createContext());

            assertThat(result).isNotNull();
            assertThat(standing.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(standing.isDismissed()).isFalse();
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
            assertThat(publishedEvents()).noneMatch(e -> e instanceof ScmDomainEvent.ReviewDismissed);
            assertThat(pr.getReviewDecision()).isNull();
        }

        @Test
        void processMergedResolvesMergeUser() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            // Create event with mergeUserId matching the event user's ID
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    RAW_MR_ID,
                    MR_IID,
                    "Add awesome feature",
                    "This MR adds an awesome feature",
                    "merged",
                    "merge",
                    "feature/awesome-feature",
                    "main",
                    false,
                    RAW_USER_ID,
                    RAW_USER_ID, // mergeUserId = event.user().id()
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T14:00:00Z",
                    null,
                    "2024-01-15T14:00:00Z",
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null,
                    null,
                    null);
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request",
                    "merge_request",
                    createUser(),
                    createProject(),
                    attrs,
                    List.of(new GitLabWebhookLabel(101L, "feature", "#0075ca")),
                    null,
                    null);

            PullRequest result = processor.processMerged(event, createContext());

            assertThat(result).isNotNull();
            // mergedById should be the author's entity ID (since mergeUser = event.user())
            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            eq("MERGED"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(author.getId()),
                            any());
        }

        @Test
        void processApprovedNullUserSkips() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            // Create event with null user (the approval actor)
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    RAW_MR_ID,
                    MR_IID,
                    "Add awesome feature",
                    "This MR adds an awesome feature",
                    "opened",
                    "approved",
                    "feature/awesome-feature",
                    "main",
                    false,
                    RAW_USER_ID,
                    null,
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T14:00:00Z",
                    null,
                    null,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null,
                    null,
                    null);
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request",
                    "merge_request",
                    null,
                    createProject(), // null user
                    attrs,
                    List.of(new GitLabWebhookLabel(101L, "feature", "#0075ca")),
                    null,
                    null);

            PullRequest result = processor.processApproved(event, createContext());

            assertThat(result).isNotNull();
            // No review should be saved when user is null
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
        }

        @Test
        void shouldNotGiveADismissedApprovalAgainWhenAnApprovalHookArrives() {
            PullRequest pr = storedForApprovalHook();
            PullRequestReview dismissed = standingApproval(pr, PullRequestReview.State.DISMISSED);

            processor.processApproved(createApprovalEvent("approved", "opened", APPROVAL_HEAD), createContext());

            assertThat(dismissed.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(dismissed.isDismissed()).isTrue();
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
            assertThat(publishedEvents()).noneMatch(e -> e instanceof ScmDomainEvent.ReviewSubmitted);
            assertThat(pr.getReviewDecision()).isNull();
        }

        @Test
        void shouldLeaveAStandingApprovalWithItsNativeDateAndUnknownHeadAloneWhenAnApprovalHookArrives() {
            PullRequest pr = storedForApprovalHook();
            // A standing approval the readiness read recorded with GitLab's own date and no head.
            Instant approvedAt = Instant.parse("2026-10-05T12:56:28.794Z");
            PullRequestReview standing = standingApproval(pr, PullRequestReview.State.APPROVED);
            standing.setSubmittedAt(approvedAt);

            processor.processApproved(createApprovalEvent("approved", "opened", APPROVAL_HEAD), createContext());

            assertThat(standing.getCommitId()).isNull();
            assertThat(standing.getSubmittedAt()).isEqualTo(approvedAt);
            assertThat(standing.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
        }

        @Test
        void shouldChangeNothingWhenAnOlderApprovalHookNamesAnotherHead() {
            PullRequest pr = createPullRequestEntity();
            pr.setHeadRefOid(APPROVAL_HEAD);
            pr.setReviewDecision(ReviewDecision.REVIEW_REQUIRED);
            pr.setUpdatedAt(Instant.parse("2024-01-15T14:00:01Z"));
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            processor.processApproved(createApprovalEvent("approved", "opened", "b".repeat(40)), createContext());

            assertThat(pr.getReviewDecision())
                    .as("an older snapshot of another head must not invalidate newer readiness")
                    .isEqualTo(ReviewDecision.REVIEW_REQUIRED);
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
        }

        @Test
        void processMissingIdSkips() {
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    null,
                    null,
                    "Title",
                    "desc",
                    "opened",
                    "open",
                    "feature/branch",
                    "main",
                    false,
                    12345L,
                    null,
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null,
                    null,
                    null);
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request", "merge_request", createUser(), createProject(), attrs, null, null, null);

            PullRequest result = processor.process(event, createContext());

            assertThat(result).isNull();
        }

        @Test
        void processSkipsStaleWebhookUpdate() {
            // The staleness check returns the existing entity without calling upsertCore.
            // This allows callers (processClosed, processMerged, etc.) to still publish
            // lifecycle events while preventing stale data from overwriting newer sync data.
            PullRequest pr = createPullRequestEntity();
            pr.setUpdatedAt(Instant.parse("2024-02-01T00:00:00Z"));
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            // Create an event with older updatedAt ("2024-01-15T10:00:00Z") than the existing entity
            GitLabMergeRequestEventDTO event = createEvent("update", "opened", false);
            PullRequest result = processor.process(event, createContext());

            // Stale webhooks return existing entity (not null) so lifecycle events can still fire
            assertThat(result).isSameAs(pr);

            // upsertCore is NEVER called because the staleness check short-circuits
            verify(pullRequestRepository, never())
                    .upsertCore(
                            anyLong(),
                            anyLong(),
                            anyInt(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());

            // No PullRequestCreated/PullRequestUpdated events for stale webhooks
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    class SyncProcessing {

        @Test
        void processFromSyncCreatesPR() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            var syncData = createSyncData();
            PullRequest result = processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            assertThat(result).isNotNull();
            assertThat(result.getProvider()).isEqualTo(gitLabProvider);

            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            eq("OPEN"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncPublishesCreatedEvent() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            ArgumentCaptor<ScmDomainEvent.PullRequestCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.PullRequestCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
        }

        @Test
        void processFromSyncPublishesUpdatedForExisting() {
            PullRequest pr = createPullRequestEntity();
            // PR already exists
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            var captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            boolean hasUpdated =
                    captor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.PullRequestUpdated);
            assertThat(hasUpdated)
                    .as("PullRequestUpdated event should be published for existing MR in sync")
                    .isTrue();

            boolean hasCreated =
                    captor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.PullRequestCreated);
            assertThat(hasCreated)
                    .as("PullRequestCreated should NOT be published for existing MR in sync")
                    .isFalse();
        }

        @Test
        void shouldPublishReadyWhenSyncFindsAMergeRequestThatLeftDraft() {
            // Detected as a diff against the prior row, before the upsert below overwrites it.
            PullRequest existingDraft = createPullRequestEntity();
            existingDraft.setDraft(true);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingDraft));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingDraft));

            processor.processFromSync(createSyncData(false), ProcessingContext.forSync(1L, testRepo));

            assertThat(publishedEvents()).anyMatch(e -> e instanceof ScmDomainEvent.PullRequestReady);
        }

        @Test
        void shouldPublishDraftedWhenSyncFindsAMergeRequestSentBackToDraft() {
            PullRequest existingReady = createPullRequestEntity();
            existingReady.setDraft(false);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));

            processor.processFromSync(createSyncData(true), ProcessingContext.forSync(1L, testRepo));

            assertThat(publishedEvents()).anyMatch(e -> e instanceof ScmDomainEvent.PullRequestDrafted);
        }

        @Test
        void shouldNotPublishADraftTransitionWhenNothingAboutTheDraftFlagMoved() {
            PullRequest existingReady = createPullRequestEntity();
            existingReady.setDraft(false);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));

            processor.processFromSync(createSyncData(false), ProcessingContext.forSync(1L, testRepo));

            assertThat(publishedEvents())
                    .noneMatch(e -> e instanceof ScmDomainEvent.PullRequestReady
                            || e instanceof ScmDomainEvent.PullRequestDrafted);
        }

        @Test
        void shouldNotInventATransitionForAMergeRequestSyncIsSeeingForTheFirstTime() {
            // No prior row to diff against, so a backfill must not read as a wave of new transitions.
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(createPullRequestEntity()));

            processor.processFromSync(createSyncData(false), ProcessingContext.forSync(1L, testRepo));

            assertThat(publishedEvents())
                    .noneMatch(e -> e instanceof ScmDomainEvent.PullRequestReady
                            || e instanceof ScmDomainEvent.PullRequestDrafted);
        }

        private List<Object> publishedEvents() {
            var captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
            return captor.getAllValues();
        }

        @Test
        void shouldRecordTheHeadPipelineAndTheClosingIssuesFromSync() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            Issue closed = new Issue();
            closed.setId(9001L);
            closed.setNumber(41);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 41)).thenReturn(Optional.of(closed));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.empty());
            when(pullRequestRepository.save(pr)).thenReturn(pr);

            processor.processFromSync(
                    syncDataWith(GitLabHeadPipeline.reported("FAILED", "d".repeat(40)), List.of(41, 42)),
                    ProcessingContext.forSync(1L, testRepo));

            assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
            assertThat(pr.getHeadCheckSha()).isEqualTo("d".repeat(40));
            assertThat(pr.getClosingIssues()).containsExactly(closed);
            verify(pullRequestRepository).save(pr);
        }

        @Test
        void shouldLeaveTheClosingIssuesAloneWhenTheSyncDidNotReadThem() {
            PullRequest pr = createPullRequestEntity();
            Issue earlier = new Issue();
            earlier.setId(9002L);
            pr.getClosingIssues().add(earlier);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.processFromSync(
                    syncDataWith(GitLabHeadPipeline.NOT_CAPTURED, null), ProcessingContext.forSync(1L, testRepo));

            assertThat(pr.getClosingIssues()).containsExactly(earlier);
            // A head pipeline the read did not capture is not an observation of no pipeline.
            assertThat(pr.getHeadCheckState()).isNull();
            assertThat(pr.getHeadCheckSha()).isNull();
        }

        @Test
        void shouldRecordThatTheHeadHasNoPipelineWhereGitLabSaidSo() {
            PullRequest pr = createPullRequestEntity();
            pr.setHeadRefOid("abc123");
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            when(pullRequestRepository.save(pr)).thenReturn(pr);

            processor.processFromSync(
                    syncDataWith(GitLabHeadPipeline.NO_PIPELINE, null), ProcessingContext.forSync(1L, testRepo));

            assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.NO_PIPELINE);
            assertThat(pr.getHeadCheckSha()).isEqualTo("abc123");
        }

        private GitLabMergeRequestProcessor.SyncMergeRequestData syncDataWith(
                GitLabHeadPipeline pipeline, @Nullable List<Integer> closing) {
            return new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Add awesome feature",
                    null,
                    "opened",
                    false,
                    null,
                    null,
                    false,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    "feature/awesome-feature",
                    "main",
                    "abc123",
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    "https://gitlab.com/uploads/avatar.png",
                    "https://gitlab.com/testuser",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    pipeline,
                    closing,
                    null);
        }

        @Test
        void processFromSyncLinksMilestone() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            Milestone milestone = new Milestone();
            milestone.setId(42L);
            milestone.setNumber(3);
            when(milestoneRepository.findByNumberAndRepositoryId(3, REPO_ID)).thenReturn(Optional.of(milestone));

            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Add awesome feature",
                    null,
                    "opened",
                    false,
                    null,
                    null,
                    false,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    "feature/awesome-feature",
                    "main",
                    null,
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    "https://gitlab.com/uploads/avatar.png",
                    "https://gitlab.com/testuser",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    3,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            eq(42L),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncMilestoneNotFound() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            when(milestoneRepository.findByNumberAndRepositoryId(99, REPO_ID)).thenReturn(Optional.empty());

            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Add awesome feature",
                    null,
                    "opened",
                    false,
                    null,
                    null,
                    false,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    "feature/awesome-feature",
                    "main",
                    null,
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    "https://gitlab.com/uploads/avatar.png",
                    "https://gitlab.com/testuser",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    99,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            eq((Long) null),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncNullMilestoneIid() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            verify(milestoneRepository, never()).findByNumberAndRepositoryId(anyInt(), anyLong());
        }

        @ParameterizedTest
        @CsvSource({"invalid-id, 5", "gid://gitlab/MergeRequest/999555, not-a-number"})
        void shouldSkipTheMergeRequestWhenItsGlobalIdOrIidIsMalformed(String globalId, String iid) {
            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    globalId,
                    iid,
                    "Title",
                    null,
                    "opened",
                    false,
                    null,
                    null,
                    false,
                    "https://example.com",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    "feature/branch",
                    "main",
                    null,
                    null,
                    null,
                    false,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            PullRequest result = processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            assertThat(result).isNull();
        }

        @Test
        void processFromSyncReconcileApprovals() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);

            // Existing stale approval review that should be removed
            User staleApprover = new User();
            staleApprover.setId(400L);
            staleApprover.setNativeId(99999L);
            staleApprover.setLogin("staleuser");

            long staleNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, 99999L);
            PullRequestReview staleReview = new PullRequestReview();
            staleReview.setNativeId(staleNativeId);
            staleReview.setProvider(gitLabProvider);
            staleReview.setState(PullRequestReview.State.APPROVED);
            staleReview.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            staleReview.setSubmittedAt(Instant.now());
            staleReview.setAuthor(staleApprover);
            staleReview.setPullRequest(pr);
            pr.getReviews().add(staleReview);

            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));

            // Stub the author user lookup (processFromSync resolves the MR author via gitLabUserService)
            User author = createUserEntity();
            lenient()
                    .when(gitLabUserService.findOrCreateUser(
                            argThat((GitLabUserLookup lookup) ->
                                    lookup != null && "gid://gitlab/User/12345".equals(lookup.globalId())),
                            eq(PROVIDER_ID)))
                    .thenReturn(author);

            // New approver from sync (reconcileApprovals resolves via gitLabUserService)
            // Lenient because the merge user call passes all nulls (unmatched invocation)
            User newApprover = createApproverEntity();
            lenient()
                    .when(gitLabUserService.findOrCreateUser(
                            argThat((GitLabUserLookup lookup) ->
                                    lookup != null && "gid://gitlab/User/11111".equals(lookup.globalId())),
                            eq(PROVIDER_ID)))
                    .thenReturn(newApprover);

            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Add awesome feature",
                    "This MR adds an awesome feature",
                    "opened",
                    false,
                    null,
                    null,
                    true,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    1,
                    10,
                    2,
                    3,
                    "feature/awesome-feature",
                    "main",
                    "abc123",
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    "https://gitlab.com/uploads/avatar.png",
                    "https://gitlab.com/testuser",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(new GitLabMergeRequestProcessor.SyncUserData(
                            "gid://gitlab/User/11111",
                            "reviewer1",
                            "Reviewer One",
                            "https://gitlab.com/uploads/avatar.png",
                            "https://gitlab.com/reviewer1",
                            null,
                            null)),
                    null,
                    null,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            // and stale review was dismissed (save called for stale review)
            verify(reviewRepository, atLeast(2)).save(any(PullRequestReview.class));

            // Verify stale approval was dismissed (not CHANGES_REQUESTED — unapproval is distinct)
            assertThat(staleReview.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        }

        @ParameterizedTest
        @CsvSource(
                nullValues = "none",
                value = {"false, none", "false, old-head", "true, old-head"})
        void shouldInventNoHeadAndKeepNoDateAnOpenMergeRequestsReadCannotHaveRecorded(
                boolean withdrawn, @Nullable String storedHead) {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            User approver = createApproverEntity();
            Instant notedAt = Instant.parse("2026-10-05T12:56:28.794Z");
            PullRequestReview noted = new PullRequestReview();
            noted.setNativeId(GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID));
            noted.setProvider(gitLabProvider);
            noted.setState(PullRequestReview.State.APPROVED);
            noted.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            noted.setSubmittedAt(notedAt);
            noted.setCommitId(storedHead);
            if (withdrawn) {
                noted.setState(PullRequestReview.State.DISMISSED);
                noted.setDismissed(true);
            }
            noted.setAuthor(approver);
            noted.setPullRequest(pr);
            pr.getReviews().add(noted);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            lenient()
                    .when(gitLabUserService.findOrCreateUser(
                            argThat((GitLabUserLookup lookup) ->
                                    lookup != null && "gid://gitlab/User/12345".equals(lookup.globalId())),
                            eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            lenient()
                    .when(gitLabUserService.findOrCreateUser(
                            argThat((GitLabUserLookup lookup) ->
                                    lookup != null && "gid://gitlab/User/11111".equals(lookup.globalId())),
                            eq(PROVIDER_ID)))
                    .thenReturn(approver);

            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Add awesome feature",
                    "This MR adds an awesome feature",
                    "opened",
                    false,
                    null,
                    null,
                    true,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    1,
                    10,
                    2,
                    3,
                    "feature/awesome-feature",
                    "main",
                    "abc123",
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    "https://gitlab.com/uploads/avatar.png",
                    "https://gitlab.com/testuser",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(new GitLabMergeRequestProcessor.SyncUserData(
                            "gid://gitlab/User/11111",
                            "reviewer1",
                            "Reviewer One",
                            "https://gitlab.com/uploads/avatar.png",
                            "https://gitlab.com/reviewer1",
                            null,
                            null)),
                    null,
                    null,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            // approvedBy lists who approves, not when nor which head. GitLab's own dates are read only once merged, so
            // a date on an open merge request is no native one and goes. A head stored for the approval, renewed or
            // unchanged, was a guess and goes too.
            assertThat(noted.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(noted.getSubmittedAt()).isNull();
            assertThat(noted.getCommitId()).isNull();
        }
    }

    @Nested
    class ConfidentialFiltering {

        @Test
        void processSkipsConfidential() {
            GitLabMergeRequestEventDTO event = createConfidentialEvent("open", "opened");
            ProcessingContext ctx = createContext();

            PullRequest result = processor.process(event, ctx);

            assertThat(result).isNull();
            verify(pullRequestRepository, never())
                    .upsertCore(
                            anyLong(),
                            anyLong(),
                            anyInt(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            anyBoolean(),
                            anyBoolean(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class MergedApprovalDates {
        private final Instant materialVersion = Instant.parse("2026-10-01T14:43:40.080Z");
        private final Instant readAt = Instant.parse("2026-10-01T17:33:52Z");
        private final Instant actualApproval = Instant.parse("2026-10-01T14:11:19.087Z");
        private PullRequest pr;
        private PullRequestReview review;

        @BeforeEach
        void setUpMergedApproval() {
            testRepo.setNativeId(273327L);
            pr = createPullRequestEntity();
            pr.setState(Issue.State.MERGED);
            pr.setHeadRefOid(APPROVAL_HEAD);
            pr.setUpdatedAt(materialVersion);
            var approver = createApproverEntity();
            approver.setProvider(gitLabProvider);
            pr.setBody("Why this change is needed");
            review = new PullRequestReview();
            review.setId(500L);
            review.setNativeId(GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID));
            review.setProvider(gitLabProvider);
            review.setAuthor(approver);
            review.setState(PullRequestReview.State.APPROVED);
            review.setPullRequest(pr);
            review.setCommitId(APPROVAL_HEAD);
            pr.addReview(review);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
        }

        @Test
        void shouldCorrectAStandingDateWithoutPublishingAnotherApprovalAndKeepItThroughAReadFailure() {
            stubSnapshotWrite();
            review.setSubmittedAt(materialVersion);
            assertThat(read(snapshot(actualApproval), readAt)).isTrue();
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
            assertThat(review.getCommitId())
                    .as("a head an earlier version guessed for the approval goes with the read")
                    .isNull();
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.ReviewSubmitted.class));

            assertThat(read(null, readAt.plusSeconds(1))).isTrue();
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
            assertThat(read(snapshot(null), readAt.plusSeconds(2))).isTrue();
            assertThat(review.getSubmittedAt()).isNull();
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.ReviewSubmitted.class));
        }

        @Test
        void shouldKeepTheNativeDateOfAHeadlessApprovalThroughALaterReadWithoutDates() {
            stubSnapshotWrite();
            review.setCommitId(null);
            assertThat(read(snapshot(actualApproval), readAt)).isTrue();
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);

            assertThat(read(null, readAt.plusSeconds(1))).isTrue();

            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
            assertThat(review.getCommitId())
                    .as("GitLab's approval date names no approved revision")
                    .isNull();
        }

        @Test
        void shouldNotCarryAWithdrawnApprovalDateIntoAnUndatedRenewal() {
            stubSnapshotWrite();
            review.setSubmittedAt(actualApproval);
            review.setState(PullRequestReview.State.DISMISSED);
            review.setDismissed(true);
            assertThat(read(null, readAt)).isTrue();
            assertThat(review.getSubmittedAt()).isNull();
            assertThat(review.isDismissed()).isFalse();
            assertThat(review.getState()).isEqualTo(PullRequestReview.State.APPROVED);
        }

        @Test
        void shouldNotReplaceADateUsingAnOldSnapshotOrAnotherHead() {
            stubSnapshotWrite();
            review.setSubmittedAt(actualApproval);
            assertThat(processor.applyReadiness(
                            testRepo,
                            MR_IID,
                            facts(snapshot(materialVersion), "b".repeat(40), materialVersion),
                            readAt,
                            ProcessingContext.forSync(1L, testRepo)))
                    .isFalse();
            assertThat(processor.applyReadiness(
                            testRepo,
                            MR_IID,
                            facts(
                                    snapshot(materialVersion, materialVersion.minusSeconds(1)),
                                    APPROVAL_HEAD,
                                    materialVersion.minusSeconds(1)),
                            readAt,
                            ProcessingContext.forSync(1L, testRepo)))
                    .isFalse();
            read(null, readAt);
            read(snapshot(materialVersion), readAt.minusSeconds(1));
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
        }

        @Test
        void shouldRecoverOnlyTheStandingDateAcrossWebhookPrecisionAndOrderAcceptedDateReads() {
            stubSnapshotWrite();
            pr.setMergeable(false);
            pr.setReviewDecision(ReviewDecision.REVIEW_REQUIRED);
            var seconds = materialVersion.truncatedTo(ChronoUnit.SECONDS);
            assertThat(processor.applyReadiness(
                            testRepo,
                            MR_IID,
                            facts(snapshot(actualApproval), APPROVAL_HEAD, seconds),
                            readAt,
                            ProcessingContext.forSync(1L, testRepo)))
                    .isTrue();
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
            assertThat(review.getCommitId()).isNull();
            assertThat(pr.getMergeable()).isFalse();
            assertThat(pr.getReviewDecision()).isEqualTo(ReviewDecision.REVIEW_REQUIRED);
            assertThat(pr.getReviewersObservedAt()).isEqualTo(readAt);
            assertThat(processor.applyReadiness(
                            testRepo,
                            MR_IID,
                            facts(snapshot(materialVersion), APPROVAL_HEAD, seconds),
                            readAt.minusSeconds(1),
                            ProcessingContext.forSync(1L, testRepo)))
                    .isFalse();
            assertThat(review.getSubmittedAt()).isEqualTo(actualApproval);
            assertThat(read(snapshot(actualApproval), readAt.plusSeconds(1))).isTrue();
            assertThat(pr.getMergeable()).isTrue();
            assertThat(pr.getReviewDecision()).isEqualTo(ReviewDecision.APPROVED);
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.ReviewSubmitted.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"metadata", "version", "title", "body", "missingBody", "actors", "withdrawn"})
        void shouldLeaveUnboundDateReadsAndTheirAuthorityUnknown(String mismatch) {
            var nativeVersion = "version".equals(mismatch) ? materialVersion.minusSeconds(1) : materialVersion;
            var rows = new GitLabApprovalClient.Snapshot(
                    "metadata".equals(mismatch) ? null : RAW_MR_ID,
                    MR_IID,
                    testRepo.getNativeId(),
                    "merged",
                    nativeVersion,
                    "title".equals(mismatch) ? "Another title" : pr.getTitle(),
                    "missingBody".equals(mismatch) ? null : "body".equals(mismatch) ? "Another reason" : pr.getBody(),
                    List.of(new GitLabApprovalClient.Approval(
                            new GitLabApprovalClient.Approver(
                                    "actors".equals(mismatch) ? RAW_APPROVER_ID + 1 : RAW_APPROVER_ID),
                            actualApproval)));
            if ("withdrawn".equals(mismatch)) review.setDismissed(true);
            assertThat(processor.applyReadiness(
                            testRepo,
                            MR_IID,
                            facts(rows, APPROVAL_HEAD, materialVersion.truncatedTo(ChronoUnit.SECONDS)),
                            readAt,
                            ProcessingContext.forSync(1L, testRepo)))
                    .isFalse();
            assertThat(review.getSubmittedAt()).isNull();
            assertThat(pr.getReviewersObservedAt()).isNull();
        }

        private void stubSnapshotWrite() {
            when(pullRequestRepository.save(pr)).thenReturn(pr);
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(Objects.requireNonNull(review.getAuthor()));
        }

        private boolean read(GitLabApprovalClient.@Nullable Snapshot rows, Instant observedAt) {
            return processor.applyReadiness(
                    testRepo,
                    MR_IID,
                    facts(rows, APPROVAL_HEAD, materialVersion),
                    observedAt,
                    ProcessingContext.forSync(1L, testRepo));
        }

        private GitLabMergeRequestReadinessReader.Facts facts(
                GitLabApprovalClient.@Nullable Snapshot rows, String head, Instant updatedAt) {
            return new GitLabMergeRequestReadinessReader.Facts(
                    testRepo.getNativeId(),
                    RAW_MR_ID,
                    "merged",
                    updatedAt,
                    head,
                    true,
                    "mergeable",
                    true,
                    GitLabHeadPipeline.NOT_CAPTURED,
                    List.of(),
                    List.of(new GitLabMergeRequestProcessor.SyncUserData(
                            "gid://gitlab/User/11111", "approver", null, null, null, null, false)),
                    GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                    rows);
        }

        private GitLabApprovalClient.Snapshot snapshot(@Nullable Instant date) {
            return snapshot(date, materialVersion);
        }

        private GitLabApprovalClient.Snapshot snapshot(@Nullable Instant date, Instant version) {
            return new GitLabApprovalClient.Snapshot(
                    RAW_MR_ID,
                    MR_IID,
                    testRepo.getNativeId(),
                    "merged",
                    version,
                    pr.getTitle(),
                    pr.getBody(),
                    List.of(new GitLabApprovalClient.Approval(
                            new GitLabApprovalClient.Approver(RAW_APPROVER_ID), date)));
        }
    }

    @Nested
    class DetailedMergeStatusMapping {

        @Test
        void mergeableMapsToClean() {
            assertMergeStatusMapping("mergeable", "CLEAN");
        }

        @Test
        void conflictMapsToDirty() {
            assertMergeStatusMapping("conflict", "DIRTY");
        }

        @Test
        void needRebaseMapsToDirty() {
            assertMergeStatusMapping("need_rebase", "DIRTY");
        }

        @Test
        void ciMustPassMapsToUnstable() {
            assertMergeStatusMapping("ci_must_pass", "UNSTABLE");
        }

        @Test
        void notApprovedMapsToBlocked() {
            assertMergeStatusMapping("not_approved", "BLOCKED");
        }

        @Test
        void checkingMapsToUnknown() {
            assertMergeStatusMapping("checking", "UNKNOWN");
        }

        @Test
        void unknownDefaultsToUnknown() {
            assertMergeStatusMapping("some_future_status", "UNKNOWN");
        }

        @Test
        void nullMapsToNull() {
            assertMergeStatusMapping(null, null);
        }

        private void assertMergeStatusMapping(@Nullable String detailedStatus, @Nullable String expectedMapping) {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty());
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr));
            lenient().when(pullRequestRepository.save(pr)).thenReturn(pr);

            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    null,
                    detailedStatus,
                    false,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    1,
                    10,
                    2,
                    3,
                    "feature/branch",
                    "main",
                    "abc123",
                    null,
                    null,
                    false,
                    0,
                    "gid://gitlab/User/12345",
                    "testuser",
                    "Test User",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                    null // closingIssueNumbers
                    ,
                    null);
            processor.processFromSync(syncData, ProcessingContext.forSync(1L, testRepo));

            // Recorded with the review snapshot the page was read at, not through the upsert.
            MergeStateStatus recorded = pr.getMergeStateStatus();
            assertThat(recorded == null ? null : recorded.name()).isEqualTo(expectedMapping);
        }
    }

    @Nested
    class ApprovalReviewIdGeneration {

        @Test
        void uniqueIdsForDifferentPairs() {
            long id1 = GitLabMergeRequestProcessor.generateApprovalNativeId(100, 200);
            long id2 = GitLabMergeRequestProcessor.generateApprovalNativeId(100, 201);
            long id3 = GitLabMergeRequestProcessor.generateApprovalNativeId(101, 200);

            assertThat(id1).isPositive();
            assertThat(id2).isPositive();
            assertThat(id3).isPositive();
            assertThat(id1).isNotEqualTo(id2).isNotEqualTo(id3);
            assertThat(id2).isNotEqualTo(id3);
        }

        @Test
        void deterministicOutput() {
            long id1 = GitLabMergeRequestProcessor.generateApprovalNativeId(999555, 12345);
            long id2 = GitLabMergeRequestProcessor.generateApprovalNativeId(999555, 12345);
            assertThat(id1).isEqualTo(id2);
        }

        @Test
        void alwaysPositiveForMax32BitMrId() {
            long maxSafe = (1L << 32) - 1; // 4294967295
            long id = GitLabMergeRequestProcessor.generateApprovalNativeId(maxSafe, 1);
            // (0xFFFFFFFF << 32) | 1 would set sign bit, but & Long.MAX_VALUE clears it
            assertThat(id).isPositive();
        }

        @Test
        void positiveForMax32BitUserId() {
            long maxUser = (1L << 32) - 1; // 4294967295
            long id = GitLabMergeRequestProcessor.generateApprovalNativeId(1, maxUser);
            // (1 << 32) | 0xFFFFFFFF = 0x1_FFFFFFFF which is positive
            assertThat(id).isPositive();
        }
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forWebhook(1L, testRepo, "open");
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "FAILED, FAILURE",
        "CANCELED, CANCELLED",
        "CANCELING, CANCELLED",
        "SKIPPED, SKIPPED",
        "CREATED, PENDING",
        "WAITING_FOR_RESOURCE, PENDING",
        "PREPARING, PENDING",
        "PENDING, PENDING",
        "RUNNING, PENDING",
        "MANUAL, PENDING",
        "SCHEDULED, PENDING",
        "failed, FAILURE",
    })
    void shouldMapAPipelineStatusToOneCheckState(String status, CheckState expected) {
        assertThat(GitLabMergeRequestProcessor.mapPipelineStatus(status)).isEqualTo(expected);
    }

    private PullRequest createPullRequestEntity() {
        PullRequest pr = new PullRequest();
        pr.setId(ENTITY_MR_ID);
        pr.setNativeId(RAW_MR_ID);
        pr.setNumber(MR_IID);
        pr.setTitle("Add awesome feature");
        pr.setState(Issue.State.OPEN);
        pr.setRepository(testRepo);
        pr.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5");
        pr.setLabels(new HashSet<>());
        pr.setAssignees(new HashSet<>());
        pr.setReviews(new HashSet<>());
        return pr;
    }

    private User createUserEntity() {
        User user = new User();
        user.setId(ENTITY_USER_ID);
        user.setNativeId(RAW_USER_ID);
        user.setLogin("testuser");
        user.setName("Test User");
        return user;
    }

    private User createApproverEntity() {
        User user = new User();
        user.setId(ENTITY_APPROVER_ID);
        user.setNativeId(RAW_APPROVER_ID);
        user.setLogin("reviewer1");
        user.setName("Reviewer One");
        return user;
    }

    private GitLabMergeRequestEventDTO createEvent(String action, @Nullable String state, boolean confidential) {
        var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                RAW_MR_ID,
                MR_IID,
                "Add awesome feature",
                "This MR adds an awesome feature",
                state,
                action,
                "feature/awesome-feature",
                "main",
                false,
                RAW_USER_ID,
                null,
                null,
                "2024-01-15T10:00:00Z",
                "2024-01-15T10:00:00Z",
                null,
                null,
                "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                null,
                null,
                null,
                null,
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request",
                confidential ? "confidential_merge_request" : "merge_request",
                createUser(),
                createProject(),
                attrs,
                List.of(new GitLabWebhookLabel(101L, "feature", "#0075ca")),
                null,
                null);
    }

    private GitLabMergeRequestEventDTO createConfidentialEvent(String action, String state) {
        var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                RAW_MR_ID,
                MR_IID,
                "Secret MR",
                "Confidential description",
                state,
                action,
                "feature/secret",
                "main",
                false,
                RAW_USER_ID,
                null,
                null,
                "2024-01-15T10:00:00Z",
                "2024-01-15T10:00:00Z",
                null,
                null,
                "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                null,
                null,
                null,
                null,
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request", "confidential_merge_request", createUser(), createProject(), attrs, null, null, null);
    }

    private GitLabMergeRequestEventDTO createApprovalEvent(String action, String state) {
        return createApprovalEvent(action, state, null);
    }

    /** An approval hook of MR !5 naming {@code head} as its last commit, or none. */
    private GitLabMergeRequestEventDTO createApprovalEvent(String action, String state, @Nullable String head) {
        var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                RAW_MR_ID,
                MR_IID,
                "Add awesome feature",
                "This MR adds an awesome feature",
                state,
                action,
                "feature/awesome-feature",
                "main",
                false,
                RAW_USER_ID,
                null,
                null,
                "2024-01-15T10:00:00Z",
                "2024-01-15T14:00:00Z",
                null,
                null,
                "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                head == null ? null : new GitLabMergeRequestEventDTO.LastCommit(head, "Fix", "Fix"),
                null,
                null,
                null,
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request",
                "merge_request",
                createApproverUser(),
                createProject(),
                attrs,
                List.of(new GitLabWebhookLabel(101L, "feature", "#0075ca")),
                null,
                null);
    }

    private GitLabWebhookUser createUser() {
        return new GitLabWebhookUser(
                RAW_USER_ID,
                "testuser",
                "Test User",
                "https://gitlab.com/uploads/-/system/user/avatar/12345/avatar.png",
                null);
    }

    private GitLabWebhookUser createApproverUser() {
        return new GitLabWebhookUser(
                RAW_APPROVER_ID,
                "reviewer1",
                "Reviewer One",
                "https://gitlab.com/uploads/-/system/user/avatar/11111/avatar.png",
                null);
    }

    private GitLabWebhookProject createProject() {
        return new GitLabWebhookProject(278964L, "gitlab", "https://gitlab.com/gitlab-org/gitlab", "gitlab-org/gitlab");
    }

    private GitLabMergeRequestProcessor.SyncMergeRequestData createSyncData() {
        return createSyncData(false);
    }

    private GitLabMergeRequestProcessor.SyncMergeRequestData createSyncData(boolean draft) {
        return new GitLabMergeRequestProcessor.SyncMergeRequestData(
                "gid://gitlab/MergeRequest/999555",
                "5",
                "Add awesome feature",
                "This MR adds an awesome feature",
                "opened",
                draft,
                null,
                null,
                false,
                "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                "2024-01-15T10:00:00Z",
                "2024-01-15T10:00:00Z",
                null,
                null,
                1,
                10,
                2,
                3,
                "feature/awesome-feature",
                "main",
                null,
                null,
                null,
                false,
                0,
                "gid://gitlab/User/12345",
                "testuser",
                "Test User",
                "https://gitlab.com/uploads/avatar.png",
                "https://gitlab.com/testuser",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                GitLabHeadPipeline.NOT_CAPTURED, // headPipeline
                null // closingIssueNumbers
                ,
                null);
    }
}
