package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabMergeRequestProcessorTest extends BaseUnitTest {

    private static final long REPO_ID = 1L;
    private static final long RAW_MR_ID = 999555L;
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

    // State Mapping

    @Nested
    class StateMapping {

        @Test
        void mapState_opened() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEvent("open", "opened", false);
            processor.process(event, createContext());

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
        void mapState_closed() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEvent("close", "closed", false);
            processor.process(event, createContext());

            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
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
        void mapState_merged() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEvent("merge", "merged", false);
            processor.process(event, createContext());

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
                            any(),
                            any());
        }

        @Test
        void mapState_null() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEventWithState("open", null);
            processor.process(event, createContext());

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
        void mapState_unknown() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEventWithState("open", "some_unknown_state");
            processor.process(event, createContext());

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
        void mapState_locked() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            GitLabMergeRequestEventDTO event = createEventWithState("open", "locked");
            processor.process(event, createContext());

            verify(pullRequestRepository)
                    .upsertCore(
                            eq(RAW_MR_ID),
                            eq(PROVIDER_ID),
                            eq(MR_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
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
    }

    // Webhook Event Processing

    @Nested
    class WebhookProcessing {

        @Test
        void processCreatesNewPR() {
            PullRequest pr = createPullRequestEntity();
            // 1st: stale check + isNew (process) -> empty (new PR)
            // 2nd: post-upsert fetch (upsertMergeRequest) -> found
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
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
                    oldrev);
            return new GitLabMergeRequestEventDTO(
                    "merge_request", "merge_request", createUser(), createProject(), attrs, List.of(), null, null);
        }

        @Test
        void processUpdatesExistingPR() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale check + isNew (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
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
                "processMerged() sets state to MERGED, isMerged=true, publishes PullRequestClosed(wasMerged=true) and PullRequestMerged")
        void processMergedPublishesEvents() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

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
            assertThat(hasPullRequestMerged).isTrue();
        }

        @Test
        void processApprovedCreatesReview() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            long expectedReviewId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            when(reviewRepository.findByNativeIdAndProviderId(expectedReviewId, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            GitLabMergeRequestEventDTO event = createApprovalEvent("approved", "opened");
            PullRequest result = processor.processApproved(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<PullRequestReview> reviewCaptor = ArgumentCaptor.forClass(PullRequestReview.class);
            verify(reviewRepository).save(reviewCaptor.capture());

            PullRequestReview savedReview = reviewCaptor.getValue();
            assertThat(savedReview.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            assertThat(savedReview.getAuthor()).isEqualTo(approver);
            assertThat(savedReview.getNativeId()).isEqualTo(expectedReviewId);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasReviewSubmitted =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.ReviewSubmitted);
            assertThat(hasReviewSubmitted).isTrue();
        }

        @Test
        void processUnapprovedDismissesReview() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            long expectedNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setNativeId(expectedNativeId);
            existingReview.setState(PullRequestReview.State.APPROVED);
            existingReview.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            existingReview.setSubmittedAt(Instant.now());
            existingReview.setAuthor(approver);
            existingReview.setPullRequest(pr);
            pr.getReviews().add(existingReview);

            when(reviewRepository.findByNativeIdAndProviderId(expectedNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            GitLabMergeRequestEventDTO event = createApprovalEvent("unapproved", "opened");
            PullRequest result = processor.processUnapproved(event, createContext());

            assertThat(result).isNotNull();

            // Verify the review was dismissed and saved
            assertThat(existingReview.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
            assertThat(existingReview.isDismissed()).isTrue();
            verify(reviewRepository).save(existingReview);

            // ReviewDismissed is published (not ReviewSubmitted)
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

            boolean hasReviewDismissed =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.ReviewDismissed);
            assertThat(hasReviewDismissed).isTrue();
        }

        @Test
        void processApprovedIdempotent() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            // Review ALREADY exists — simulates duplicate approval webhook
            long expectedNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setNativeId(expectedNativeId);
            existingReview.setState(PullRequestReview.State.APPROVED);
            when(reviewRepository.findByNativeIdAndProviderId(expectedNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            GitLabMergeRequestEventDTO event = createApprovalEvent("approved", "opened");
            PullRequest result = processor.processApproved(event, createContext());

            assertThat(result).isNotNull();
            // Review should NOT be saved again
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
        }

        @Test
        void processUnapprovedNoExistingReview() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            // No existing review — findById returns empty
            long expectedReviewId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            when(reviewRepository.findByNativeIdAndProviderId(expectedReviewId, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            GitLabMergeRequestEventDTO event = createApprovalEvent("unapproved", "opened");
            PullRequest result = processor.processUnapproved(event, createContext());

            assertThat(result).isNotNull();
            // No save should happen (no review to update)
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
            // Only PullRequestUpdated from process(), no ReviewSubmitted
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasReviewSubmitted =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.ReviewSubmitted);
            assertThat(hasReviewSubmitted).isFalse();
        }

        @Test
        void processMergedResolvesMergeUser() {
            PullRequest pr = createPullRequestEntity();
            // 2 calls: stale+isNew check (process), post-upsert fetch (upsertMergeRequest)
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
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
        void processUnapproved_alreadyDismissed_isIdempotent() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            // Review already in DISMISSED state
            long expectedNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setNativeId(expectedNativeId);
            existingReview.setState(PullRequestReview.State.DISMISSED);
            existingReview.setDismissed(true);
            existingReview.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            existingReview.setSubmittedAt(Instant.now());
            existingReview.setAuthor(approver);
            existingReview.setPullRequest(pr);
            pr.getReviews().add(existingReview);

            when(reviewRepository.findByNativeIdAndProviderId(expectedNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            GitLabMergeRequestEventDTO event = createApprovalEvent("unapproved", "opened");
            PullRequest result = processor.processUnapproved(event, createContext());

            assertThat(result).isNotNull();
            // Already DISMISSED — save() should NOT be called
            verify(reviewRepository, never()).save(any(PullRequestReview.class));
            assertThat(existingReview.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        }

        @Test
        void processApproved_reApproval_fromChangesRequested_updatesToApproved() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(userRepository.findByNativeIdAndProviderId(RAW_USER_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(author));

            User approver = createApproverEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(approver);

            // Existing review in CHANGES_REQUESTED state (from a prior unapproval)
            long expectedNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_APPROVER_ID);
            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setNativeId(expectedNativeId);
            existingReview.setState(PullRequestReview.State.CHANGES_REQUESTED);
            existingReview.setHtmlUrl("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5#approvals");
            existingReview.setSubmittedAt(Instant.now().minusSeconds(3600));
            existingReview.setAuthor(approver);
            existingReview.setPullRequest(pr);
            pr.getReviews().add(existingReview);

            when(reviewRepository.findByNativeIdAndProviderId(expectedNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            GitLabMergeRequestEventDTO event = createApprovalEvent("approved", "opened");
            PullRequest result = processor.processApproved(event, createContext());

            assertThat(result).isNotNull();

            // Review should be updated to APPROVED and saved
            assertThat(existingReview.getState()).isEqualTo(PullRequestReview.State.APPROVED);
            verify(reviewRepository).save(existingReview);

            // ReviewSubmitted event should be emitted for the state change
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasReviewSubmitted =
                    eventCaptor.getAllValues().stream().anyMatch(e -> e instanceof ScmDomainEvent.ReviewSubmitted);
            assertThat(hasReviewSubmitted).isTrue();
        }

        @Test
        void processRequestedChangesFromNote_updatesExistingApproval() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);

            User reviewer = createUserEntity();
            reviewer.setNativeId(RAW_USER_ID);

            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setState(PullRequestReview.State.APPROVED);
            existingReview.setAuthor(reviewer);
            existingReview.setPullRequest(pr);

            long approvalNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_USER_ID);
            when(reviewRepository.findByNativeIdAndProviderId(approvalNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            processor.processRequestedChangesFromNote(pr, reviewer, createContext());

            assertThat(existingReview.getState()).isEqualTo(PullRequestReview.State.CHANGES_REQUESTED);
            verify(reviewRepository).save(existingReview);
            verify(eventPublisher, atLeastOnce()).publishEvent(any(ScmDomainEvent.ReviewSubmitted.class));
        }

        @Test
        void processRequestedChangesFromNote_idempotent() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);

            User reviewer = createUserEntity();
            reviewer.setNativeId(RAW_USER_ID);

            PullRequestReview existingReview = new PullRequestReview();
            existingReview.setState(PullRequestReview.State.CHANGES_REQUESTED);

            long approvalNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_USER_ID);
            when(reviewRepository.findByNativeIdAndProviderId(approvalNativeId, PROVIDER_ID))
                    .thenReturn(Optional.of(existingReview));

            processor.processRequestedChangesFromNote(pr, reviewer, createContext());

            verify(reviewRepository, never()).save(any());
        }

        @Test
        void processRequestedChangesFromNote_noExistingReview_skips() {
            PullRequest pr = createPullRequestEntity();
            pr.setNativeId(RAW_MR_ID);

            User reviewer = createUserEntity();
            reviewer.setNativeId(RAW_USER_ID);

            long approvalNativeId = GitLabMergeRequestProcessor.generateApprovalNativeId(RAW_MR_ID, RAW_USER_ID);
            when(reviewRepository.findByNativeIdAndProviderId(approvalNativeId, PROVIDER_ID))
                    .thenReturn(Optional.empty());

            processor.processRequestedChangesFromNote(pr, reviewer, createContext());

            verify(reviewRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.ReviewSubmitted.class));
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
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

    // GraphQL Sync Processing

    @Nested
    class SyncProcessing {

        @Test
        void processFromSyncCreatesPR() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            var syncData = createSyncData();
            PullRequest result = processor.processFromSync(syncData, testRepo, 1L);

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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, testRepo, 1L);

            ArgumentCaptor<ScmDomainEvent.PullRequestCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.PullRequestCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
        }

        @Test
        void processFromSyncPublishesUpdatedForExisting() {
            PullRequest pr = createPullRequestEntity();
            // PR already exists
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, testRepo, 1L);

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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingDraft));

            processor.processFromSync(createSyncData(false), testRepo, 1L);

            assertThat(publishedEvents()).anyMatch(e -> e instanceof ScmDomainEvent.PullRequestReady);
        }

        @Test
        void shouldPublishDraftedWhenSyncFindsAMergeRequestSentBackToDraft() {
            PullRequest existingReady = createPullRequestEntity();
            existingReady.setDraft(false);
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));

            processor.processFromSync(createSyncData(true), testRepo, 1L);

            assertThat(publishedEvents()).anyMatch(e -> e instanceof ScmDomainEvent.PullRequestDrafted);
        }

        @Test
        void shouldNotPublishADraftTransitionWhenNothingAboutTheDraftFlagMoved() {
            PullRequest existingReady = createPullRequestEntity();
            existingReady.setDraft(false);
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(existingReady));

            processor.processFromSync(createSyncData(false), testRepo, 1L);

            assertThat(publishedEvents())
                    .noneMatch(e -> e instanceof ScmDomainEvent.PullRequestReady
                            || e instanceof ScmDomainEvent.PullRequestDrafted);
        }

        @Test
        void shouldNotInventATransitionForAMergeRequestSyncIsSeeingForTheFirstTime() {
            // No prior row to diff against, so a backfill must not read as a wave of new transitions.
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createPullRequestEntity()));

            processor.processFromSync(createSyncData(false), testRepo, 1L);

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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            Issue closed = new Issue();
            closed.setId(9001L);
            closed.setNumber(41);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 41)).thenReturn(Optional.of(closed));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.empty());
            when(pullRequestRepository.save(pr)).thenReturn(pr);

            processor.processFromSync(syncDataWith("FAILED", "d".repeat(40), List.of(41, 42)), testRepo, 1L);

            // The pipeline's verdict on the head, for that head; the one closing issue this repository holds.
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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            when(pullRequestRepository.save(pr)).thenReturn(pr);

            processor.processFromSync(syncDataWith(null, null, null), testRepo, 1L);

            assertThat(pr.getClosingIssues()).containsExactly(earlier);
            // No pipeline on the head is a fact about the head: no checks.
            assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.NONE);
            assertThat(pr.getHeadCheckSha()).isEqualTo("abc123");
        }

        private GitLabMergeRequestProcessor.SyncMergeRequestData syncDataWith(
                @Nullable String pipelineStatus, @Nullable String pipelineSha, @Nullable List<Integer> closing) {
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
                    null,
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
                    pipelineStatus,
                    pipelineSha,
                    closing);
        }

        @Test
        void processFromSyncLinksMilestone() {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
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
                    3,
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            processor.processFromSync(syncData, testRepo, 1L);

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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
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
                    99,
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            processor.processFromSync(syncData, testRepo, 1L);

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
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

            var syncData = createSyncData();
            processor.processFromSync(syncData, testRepo, 1L);

            verify(milestoneRepository, never()).findByNumberAndRepositoryId(anyInt(), anyLong());
        }

        @Test
        void processFromSyncInvalidGlobalId() {
            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "invalid-id",
                    "5",
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
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            PullRequest result = processor.processFromSync(syncData, testRepo, 1L);

            assertThat(result).isNull();
        }

        @Test
        void processFromSyncInvalidIid() {
            var syncData = new GitLabMergeRequestProcessor.SyncMergeRequestData(
                    "gid://gitlab/MergeRequest/999555",
                    "not-a-number",
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
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            PullRequest result = processor.processFromSync(syncData, testRepo, 1L);

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

            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.of(pr))
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
                    List.of(new GitLabMergeRequestProcessor.SyncUserData(
                            "gid://gitlab/User/11111",
                            "reviewer1",
                            "Reviewer One",
                            "https://gitlab.com/uploads/avatar.png",
                            "https://gitlab.com/reviewer1",
                            null)),
                    null,
                    null,
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            processor.processFromSync(syncData, testRepo, 1L);

            // Verify new approval was created (save called for new review)
            // and stale review was dismissed (save called for stale review)
            verify(reviewRepository, Mockito.atLeast(2)).save(any(PullRequestReview.class));

            // Verify stale approval was dismissed (not CHANGES_REQUESTED — unapproval is distinct)
            assertThat(staleReview.getState()).isEqualTo(PullRequestReview.State.DISMISSED);
        }
    }

    // Confidential Filtering

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

    // Detailed Merge Status Mapping

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

        private void assertMergeStatusMapping(
                @org.jspecify.annotations.Nullable String detailedStatus,
                @org.jspecify.annotations.Nullable String expectedMapping) {
            PullRequest pr = createPullRequestEntity();
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(pr));

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
                    null,
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
                    null, // headPipelineStatus
                    null, // headPipelineSha
                    null // closingIssueNumbers
                    );
            processor.processFromSync(syncData, testRepo, 1L);

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
                            eq(expectedMapping),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    // Approval Review ID Generation

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

    // Helpers

    private ProcessingContext createContext() {
        return ProcessingContext.forWebhook(1L, testRepo, "open");
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "FAILED, FAILURE",
        "CANCELED, CANCELLED",
        "CANCELING, CANCELLED",
        "SKIPPED, NONE",
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

    @Test
    void shouldCallNoPipelineNoChecks() {
        assertThat(GitLabMergeRequestProcessor.mapPipelineStatus(null)).isEqualTo(CheckState.NONE);
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
        pr.setRequestedReviewers(new HashSet<>());
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

    private GitLabMergeRequestEventDTO createEvent(String action, String state, boolean confidential) {
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

    private GitLabMergeRequestEventDTO createEventWithState(String action, @Nullable String state) {
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
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request",
                "merge_request",
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
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request", "confidential_merge_request", createUser(), createProject(), attrs, null, null, null);
    }

    private GitLabMergeRequestEventDTO createApprovalEvent(String action, String state) {
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
                null, // headPipelineStatus
                null, // headPipelineSha
                null // closingIssueNumbers
                );
    }
}
