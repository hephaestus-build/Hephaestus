package de.tum.cit.aet.hephaestus.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyViolationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileActivityMonitorDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileActivityStatsDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileReviewActivityDTO;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceContributionActivityService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for UserProfileService.
 *
 * <p>Tests verify the CQRS pattern: ActivityEvent is the source of truth for
 * what activity exists (same as leaderboard), with entity details hydrated
 * from integration.scm tables.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    private static final Long WORKSPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final String USER_LOGIN = "alice";
    private static final Instant AFTER = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2024-01-08T00:00:00Z");

    @Mock
    private ProfileRepositoryQueryRepository profileRepositoryQueryRepository;

    @Mock
    private ProfilePullRequestQueryRepository profilePullRequestQueryRepository;

    @Mock
    private PullRequestReviewRepository pullRequestReviewRepository;

    @Mock
    private PullRequestReviewCommentRepository pullRequestReviewCommentRepository;

    @Mock
    private IssueCommentRepository issueCommentRepository;

    @Mock
    private ProfileReviewActivityAssembler reviewActivityAssembler;

    @Mock
    private WorkspaceMembershipService workspaceMembershipService;

    @Mock
    private WorkspaceContributionActivityService workspaceContributionActivityService;

    @Mock
    private ProfileActivityQueryService profileActivityQueryService;

    @Mock
    private ActivityEventRepository activityEventRepository;

    private static final ProfileActivityStatsDTO STATS_STUB =
            new ProfileActivityStatsDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private UserProfileService service;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(
                profileRepositoryQueryRepository,
                profilePullRequestQueryRepository,
                pullRequestReviewRepository,
                pullRequestReviewCommentRepository,
                issueCommentRepository,
                reviewActivityAssembler,
                workspaceMembershipService,
                workspaceContributionActivityService,
                profileActivityQueryService,
                activityEventRepository);
    }

    @Nested
    class CqrsArchitectureTests {

        @Test
        void queriesActivityEventFirstThenHydrates() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(200L);
            PullRequest pr = createPullRequest(300L, user, repo);
            PullRequestReview review = createReview(400L, user, pr);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            // ActivityEvent is the source of truth
            ActivityEvent event = createActivityEvent(WORKSPACE_ID, USER_ID, 400L, ActivityTargetType.REVIEW, 15.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event));

            // Hydrate review details from integration.scm
            when(pullRequestReviewRepository.findAllByIdWithRelations(Set.of(400L)))
                    .thenReturn(List.of(review));

            // Mock assembler
            when(reviewActivityAssembler.assemble(eq(review), eq(15))).thenReturn(createProfileReviewDTO(400L, 15));

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();

            // Verify ActivityEvent queried first (source of truth)
            verify(activityEventRepository)
                    .findProfileActivityByActorInTimeframe(eq(WORKSPACE_ID), eq(USER_ID), any(), any());

            // Verify entity hydrated from integration.scm
            verify(pullRequestReviewRepository).findAllByIdWithRelations(Set.of(400L));

            // Verify assembler was called with XP from ActivityEvent
            verify(reviewActivityAssembler).assemble(eq(review), eq(15));
        }

        @Test
        void returnsEmptyWhenNoActivityEvents() {
            User user = createUser(USER_ID, USER_LOGIN);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            // No ActivityEvents in ledger
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of());

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).isEmpty();

            // Verify no hydration queries (efficiency)
            verifyNoInteractions(pullRequestReviewRepository);
            verifyNoInteractions(pullRequestReviewCommentRepository);
            verifyNoInteractions(issueCommentRepository);
        }

        @Test
        void batchFetchesEntitiesEfficiently() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(200L);
            PullRequest pr = createPullRequest(300L, user, repo);
            PullRequestReview review1 = createReview(400L, user, pr);
            PullRequestReview review2 = createReview(401L, user, pr);
            IssueComment comment = createIssueComment(500L, user, pr);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            // ActivityEvents for 2 reviews + 1 comment
            ActivityEvent event1 = createActivityEvent(WORKSPACE_ID, USER_ID, 400L, ActivityTargetType.REVIEW, 10.0);
            ActivityEvent event2 = createActivityEvent(WORKSPACE_ID, USER_ID, 401L, ActivityTargetType.REVIEW, 20.0);
            ActivityEvent event3 =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 500L, ActivityTargetType.ISSUE_COMMENT, 5.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event1, event2, event3));

            // Batch hydration
            when(pullRequestReviewRepository.findAllByIdWithRelations(Set.of(400L, 401L)))
                    .thenReturn(List.of(review1, review2));
            when(issueCommentRepository.findAllByIdWithRelations(Set.of(500L))).thenReturn(List.of(comment));

            // Mock assembler returns
            when(reviewActivityAssembler.assemble(eq(review1), eq(10))).thenReturn(createProfileReviewDTO(400L, 10));
            when(reviewActivityAssembler.assemble(eq(review2), eq(20))).thenReturn(createProfileReviewDTO(401L, 20));
            when(reviewActivityAssembler.assemble(eq(comment), eq(5))).thenReturn(createProfileReviewDTO(500L, 5));

            service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            // Assert: Single batch query for each entity type (no N+1)
            verify(pullRequestReviewRepository, times(1)).findAllByIdWithRelations(any());
            verifyNoInteractions(pullRequestReviewCommentRepository);
            verify(issueCommentRepository, times(1)).findAllByIdWithRelations(any());

            // Verify each item assembled with its correct XP from ActivityEvent
            verify(reviewActivityAssembler).assemble(eq(review1), eq(10));
            verify(reviewActivityAssembler).assemble(eq(review2), eq(20));
            verify(reviewActivityAssembler).assemble(eq(comment), eq(5));
        }

        @Test
        void skipsMissingEntitiesGracefully() {
            User user = createUser(USER_ID, USER_LOGIN);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            // ActivityEvent exists but entity was deleted
            ActivityEvent event = createActivityEvent(WORKSPACE_ID, USER_ID, 999L, ActivityTargetType.REVIEW, 15.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event));

            // Entity not found in integration.scm
            when(pullRequestReviewRepository.findAllByIdWithRelations(Set.of(999L)))
                    .thenReturn(List.of());

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).isEmpty();

            // Assembler should not be called for missing entity
            verifyNoInteractions(reviewActivityAssembler);
        }

        @Test
        void hydratesReviewCommentsOnOtherUsersPullRequestsFromActivityEvents() {
            User actor = createUser(USER_ID, USER_LOGIN);
            User prAuthor = createUser(99L, "pr-author");
            Repository repo = createRepository(200L);
            PullRequest pr = createPullRequest(300L, prAuthor, repo);
            PullRequestReviewComment reviewComment = createReviewComment(600L, actor, pr);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(actor));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            ActivityEvent event =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 600L, ActivityTargetType.REVIEW_COMMENT, 0.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event));

            when(pullRequestReviewCommentRepository.findAllByIdWithRelations(Set.of(600L)))
                    .thenReturn(List.of(reviewComment));
            when(reviewActivityAssembler.assemble(eq(reviewComment), eq(0)))
                    .thenReturn(createProfileReviewDTO(600L, 0));
            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).hasSize(1);
            verify(pullRequestReviewCommentRepository).findAllByIdWithRelations(Set.of(600L));
            verify(reviewActivityAssembler).assemble(eq(reviewComment), eq(0));
        }

        @Test
        void skipsOwnPullRequestReviewCommentsFromScoredActivityFeed() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(200L);
            PullRequest pr = createPullRequest(300L, user, repo);
            PullRequestReviewComment reviewComment = createReviewComment(600L, user, pr);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            ActivityEvent event =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 600L, ActivityTargetType.REVIEW_COMMENT, 0.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event));

            when(pullRequestReviewCommentRepository.findAllByIdWithRelations(Set.of(600L)))
                    .thenReturn(List.of(reviewComment));

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).isEmpty();
            verify(pullRequestReviewCommentRepository).findAllByIdWithRelations(Set.of(600L));
            verify(reviewActivityAssembler, never()).assemble(eq(reviewComment), eq(0));
        }

        @Test
        void skipsIssueCommentsOnRegularIssues() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(200L);
            Issue issue = createIssue(300L, user, repo);
            IssueComment comment = createIssueComment(500L, user, issue);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            ActivityEvent event =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 500L, ActivityTargetType.ISSUE_COMMENT, 0.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(event));

            when(issueCommentRepository.findAllByIdWithRelations(Set.of(500L))).thenReturn(List.of(comment));

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).isEmpty();
            verify(issueCommentRepository).findAllByIdWithRelations(Set.of(500L));
            verify(reviewActivityAssembler, never()).assemble(eq(comment), anyInt());
        }

        @Test
        void keepsDifferentTargetTypesWhenIdsCollide() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(200L);
            PullRequest pr = createPullRequest(300L, user, repo);
            IssueComment issueComment = createIssueComment(700L, user, pr);
            PullRequestReviewComment reviewComment = createReviewComment(700L, user, pr);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(STATS_STUB);

            ActivityEvent issueCommentEvent =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 700L, ActivityTargetType.ISSUE_COMMENT, 0.0);
            ActivityEvent reviewCommentEvent =
                    createActivityEvent(WORKSPACE_ID, USER_ID, 700L, ActivityTargetType.REVIEW_COMMENT, 0.0);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(
                            eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(List.of(issueCommentEvent, reviewCommentEvent));

            when(issueCommentRepository.findAllByIdWithRelations(Set.of(700L))).thenReturn(List.of(issueComment));
            when(pullRequestReviewCommentRepository.findAllByIdWithRelations(Set.of(700L)))
                    .thenReturn(List.of(reviewComment));

            ProfileReviewActivityDTO issueCommentDto = createProfileReviewDTO(700L, 0);
            when(reviewActivityAssembler.assemble(eq(issueComment), eq(0))).thenReturn(issueCommentDto);

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null);

            assertThat(result).isPresent();
            assertThat(result.get().reviewActivity()).hasSize(1);
            verify(issueCommentRepository).findAllByIdWithRelations(Set.of(700L));
            verify(pullRequestReviewCommentRepository).findAllByIdWithRelations(Set.of(700L));
            verify(reviewActivityAssembler).assemble(eq(issueComment), eq(0));
            verify(reviewActivityAssembler, never()).assemble(eq(reviewComment), eq(0));
        }
    }

    @Nested
    @DisplayName("getUserProfile")
    class UserProfileTests {

        @Test
        @DisplayName("lists contributed repositories without loading their labels")
        void listsContributedRepositoriesWithoutLoadingLabels() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repository = spy(createRepository(200L));
            repository.setName("hephaestus");
            // A lazy label load runs on a workspace-scoped table outside any scoped query, which
            // tenancy enforcement rejects with exactly this exception.
            lenient()
                    .doThrow(new TenancyViolationException(Set.of("label")))
                    .when(repository)
                    .getLabels();

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(workspaceContributionActivityService.findFirstContributionInstant(eq(WORKSPACE_ID), any()))
                    .thenReturn(Optional.empty());
            when(profileRepositoryQueryRepository.findContributedByUserId(eq(USER_ID), eq(WORKSPACE_ID)))
                    .thenReturn(List.of(repository));

            Optional<ProfileDTO> result = service.getUserProfile(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE);

            assertThat(result).isPresent();
            assertThat(result.get().contributedRepositories()).singleElement().satisfies(dto -> {
                assertThat(dto.name()).isEqualTo("hephaestus");
                assertThat(dto.labels()).isNull();
            });
            verify(repository, never()).getLabels();
        }
    }

    @Nested
    @DisplayName("getActivityMonitor")
    class ActivityMonitorTests {

        @Test
        @DisplayName("returns empty when login is unknown")
        void returnsEmptyWhenLoginUnknown() {
            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, "ghost"))
                    .thenReturn(Optional.empty());

            assertThat(service.getActivityMonitor("ghost", WORKSPACE_ID, AFTER, BEFORE, null, null))
                    .isEmpty();
            verifyNoInteractions(profileActivityQueryService);
        }

        @Test
        @DisplayName("filters authored PRs and totals by repositoryIds while keeping workspace-wide stats")
        void filtersByRepositoryAndReusesStats() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repoIncluded = createRepository(10L);
            repoIncluded.setNameWithOwner("org/included");
            Repository repoExcluded = createRepository(20L);
            repoExcluded.setNameWithOwner("org/excluded");
            PullRequest included = createOpenPullRequest(1001L, user, repoIncluded);
            PullRequest excluded = createOpenPullRequest(1002L, user, repoExcluded);

            ProfileActivityStatsDTO stats = sampleStats();

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(
                            eq(USER_ID), eq(Set.of(Issue.State.OPEN)), eq(WORKSPACE_ID), any(), any()))
                    .thenReturn(List.of(included, excluded));
            when(activityEventRepository.findProfileActivityByActorInTimeframe(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(eq(WORKSPACE_ID), eq(USER_ID), any(), any()))
                    .thenReturn(stats);

            Optional<ProfileActivityMonitorDTO> result =
                    service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, Set.of(10L), null);

            assertThat(result).isPresent();
            ProfileActivityMonitorDTO monitor = result.get();
            assertThat(monitor.authoredPullRequests()).extracting("id").containsExactly(1001L);
            assertThat(monitor.totalAuthoredPullRequestCount()).isEqualTo(1);
            assertThat(monitor.activityStats()).isSameAs(stats);
            // Repository list covers BOTH repos that had activity in the window (not just the selected one)
            assertThat(monitor.repositories()).extracting("id").containsExactlyInAnyOrder(10L, 20L);
        }

        @Test
        @DisplayName("clamps and defaults the limit and reports pre-limit totals")
        void clampsLimitAndReportsTotals() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(30L);
            List<PullRequest> sevenPrs = IntStream.rangeClosed(1, 7)
                    .mapToObj(i -> createOpenPullRequest(2000L + i, user, repo))
                    .toList();

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(sevenPrs);
            when(activityEventRepository.findProfileActivityByActorInTimeframe(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(sampleStats());

            // null limit → DEFAULT (5)
            assertThat(service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null)
                            .get()
                            .authoredPullRequests())
                    .hasSize(5);
            // 0 → clamped to 1
            assertThat(service.getActivityMonitor(USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, 0)
                            .get()
                            .authoredPullRequests())
                    .hasSize(1);
            // 9999 → clamped to MAX (100), but only 7 items exist
            ProfileActivityMonitorDTO big = service.getActivityMonitor(
                            USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, 9999)
                    .get();
            assertThat(big.authoredPullRequests()).hasSize(7);
            // Total reflects pre-limit count
            assertThat(big.totalAuthoredPullRequestCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("deduplicates repositories when same repo appears in multiple PRs")
        void deduplicatesRepositories() {
            User user = createUser(USER_ID, USER_LOGIN);
            Repository repo = createRepository(40L);
            repo.setNameWithOwner("org/repo");
            PullRequest first = createOpenPullRequest(3001L, user, repo);
            PullRequest second = createOpenPullRequest(3002L, user, repo);

            when(workspaceMembershipService.findMemberByLogin(WORKSPACE_ID, USER_LOGIN))
                    .thenReturn(Optional.of(user));
            when(profilePullRequestQueryRepository.findAuthoredByUserIdAndStates(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(first, second));
            when(activityEventRepository.findProfileActivityByActorInTimeframe(any(), any(), any(), any()))
                    .thenReturn(List.of());
            when(profileActivityQueryService.getActivityStats(any(), any(), any(), any()))
                    .thenReturn(sampleStats());

            ProfileActivityMonitorDTO monitor = service.getActivityMonitor(
                            USER_LOGIN, WORKSPACE_ID, AFTER, BEFORE, null, null)
                    .get();

            assertThat(monitor.repositories()).hasSize(1);
            assertThat(monitor.repositories().get(0).id()).isEqualTo(40L);
        }

        private ProfileActivityStatsDTO sampleStats() {
            return new ProfileActivityStatsDTO(42, 3, 2, 1, 0, 5, 0, 0, 7, 1, 0, 0, 0);
        }
    }

    // ========================================================================
    // Helper Methods

    private PullRequest createOpenPullRequest(Long id, User author, Repository repo) {
        PullRequest pr = createPullRequest(id, author, repo);
        pr.setState(Issue.State.OPEN);
        return pr;
    }

    private User createUser(Long id, String login) {
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        return user;
    }

    private Repository createRepository(Long id) {
        Repository repo = new Repository();
        repo.setId(id);
        repo.setName("test-repo");
        return repo;
    }

    private PullRequest createPullRequest(Long id, User author, Repository repo) {
        PullRequest pr = new PullRequest();
        pr.setId(id);
        pr.setAuthor(author);
        pr.setRepository(repo);
        pr.setNumber(1);
        pr.setTitle("Test PR");
        pr.setHtmlUrl("https://github.com/test/pr/1");
        return pr;
    }

    private Issue createIssue(Long id, User author, Repository repo) {
        Issue issue = new Issue();
        issue.setId(id);
        issue.setAuthor(author);
        issue.setRepository(repo);
        issue.setNumber(2);
        issue.setTitle("Test Issue");
        issue.setHtmlUrl("https://github.com/test/issues/2");
        issue.setState(Issue.State.OPEN);
        return issue;
    }

    private PullRequestReview createReview(Long id, User author, PullRequest pr) {
        PullRequestReview review = new PullRequestReview();
        review.setId(id);
        review.setAuthor(author);
        review.setPullRequest(pr);
        review.setState(PullRequestReview.State.APPROVED);
        review.setSubmittedAt(Instant.now());
        review.setHtmlUrl("https://github.com/test/review/1");
        return review;
    }

    private IssueComment createIssueComment(Long id, User author, Issue issue) {
        IssueComment comment = new IssueComment();
        comment.setId(id);
        comment.setAuthor(author);
        comment.setIssue(issue);
        comment.setCreatedAt(Instant.now());
        comment.setHtmlUrl("https://github.com/test/comment/1");
        return comment;
    }

    private PullRequestReviewComment createReviewComment(Long id, User author, PullRequest pullRequest) {
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setId(id);
        comment.setAuthor(author);
        comment.setPullRequest(pullRequest);
        comment.setCreatedAt(Instant.now());
        comment.setHtmlUrl("https://github.com/test/review-comment/1");
        comment.setBody("Reply to tutor note");
        return comment;
    }

    private ActivityEvent createActivityEvent(
            Long workspaceId, Long actorId, Long targetId, ActivityTargetType targetType, Double xp) {
        return ActivityEvent.builder()
                .targetId(targetId)
                .targetType(targetType.getValue())
                .xp(xp)
                .occurredAt(Instant.now())
                .eventType(
                        targetType == ActivityTargetType.REVIEW
                                ? ActivityEventType.REVIEW_APPROVED
                                : targetType == ActivityTargetType.REVIEW_COMMENT
                                        ? ActivityEventType.REVIEW_COMMENT_CREATED
                                        : ActivityEventType.COMMENT_CREATED)
                .eventKey(ActivityEvent.buildKey(
                        targetType == ActivityTargetType.REVIEW
                                ? ActivityEventType.REVIEW_APPROVED
                                : targetType == ActivityTargetType.REVIEW_COMMENT
                                        ? ActivityEventType.REVIEW_COMMENT_CREATED
                                        : ActivityEventType.COMMENT_CREATED,
                        targetId,
                        Instant.now()))
                .build();
    }

    private ProfileReviewActivityDTO createProfileReviewDTO(Long id, int score) {
        return new ProfileReviewActivityDTO(
                id,
                false,
                PullRequestReview.State.APPROVED,
                0,
                null,
                null,
                "https://github.com/test/review/" + id,
                score,
                Instant.now());
    }
}
