package de.tum.cit.aet.hephaestus.profile;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.scoring.XpPrecision;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestBaseInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileActivityMonitorDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileActivityStatsDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileReviewActivityDTO;
import de.tum.cit.aet.hephaestus.profile.dto.ProfileXpRecordDTO;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceContributionActivityService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates user profile data from the ActivityEvent ledger so profile and
 * leaderboard views stay consistent.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final Duration DEFAULT_ACTIVITY_WINDOW = Duration.ofDays(7);
    private static final int DEFAULT_ACTIVITY_MONITOR_LIMIT = 5;
    private static final int MAX_ACTIVITY_MONITOR_LIMIT = 100;

    private final ProfileRepositoryQueryRepository profileRepositoryQueryRepository;
    private final ProfilePullRequestQueryRepository profilePullRequestQueryRepository;
    private final PullRequestReviewRepository pullRequestReviewRepository;
    private final PullRequestReviewCommentRepository pullRequestReviewCommentRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final ProfileReviewActivityAssembler reviewActivityAssembler;
    private final WorkspaceMembershipService workspaceMembershipService;
    private final WorkspaceContributionActivityService workspaceContributionActivityService;
    private final ProfileActivityQueryService profileActivityQueryService;
    private final ActivityEventRepository activityEventRepository;

    /**
     * Get the profile header for a user: identity, league standing, contribution surface, XP.
     *
     * <p>Time-windowed activity (review activity, open PRs, badges) is served by
     * {@link #getActivityMonitor} so the header doesn't refetch when filters change.
     *
     * @param login       GitHub login
     * @param workspaceId workspace to scope contributions to (null for global view)
     * @param after       start of activity window (null for default 7 days before 'before')
     * @param before      end of activity window (null for now)
     */
    @Transactional(readOnly = true)
    public Optional<ProfileDTO> getUserProfile(String login, Long workspaceId, Instant after, Instant before) {
        String safeLogin = LoggingUtils.sanitizeForLog(login);
        TimeRange timeRange = resolveTimeRange(login, after, before);
        log.debug(
                "Getting user profile for login: {} in workspace: {} with timeframe {} - {}",
                safeLogin,
                workspaceId,
                timeRange.after(),
                timeRange.before());

        Optional<User> optionalUser = workspaceMembershipService.findMemberByLogin(workspaceId, login);
        if (optionalUser.isEmpty()) {
            return Optional.empty();
        }

        User userEntity = optionalUser.get();
        int leaguePoints = workspaceMembershipService.getCurrentLeaguePoints(workspaceId, userEntity);
        UserInfoDTO user = Objects.requireNonNull(UserInfoDTO.fromUser(userEntity, leaguePoints));

        Instant firstContribution = workspaceId == null
                ? null
                : workspaceContributionActivityService
                        .findFirstContributionInstant(workspaceId, userEntity.getId())
                        .orElse(null);

        List<RepositoryInfoDTO> contributedRepositories = workspaceId == null
                ? List.of()
                : profileRepositoryQueryRepository.findContributedByUserId(userEntity.getId(), workspaceId).stream()
                        .map(RepositoryInfoDTO::fromRepositoryWithoutLabels)
                        .sorted(Comparator.comparing(RepositoryInfoDTO::name))
                        .toList();

        ProfileXpRecordDTO xpRecord = buildUserXpRecord(workspaceId, user.id());

        return Optional.of(new ProfileDTO(user, firstContribution, contributedRepositories, xpRecord));
    }

    @Transactional(readOnly = true)
    public Optional<ProfileActivityMonitorDTO> getActivityMonitor(
            String login,
            Long workspaceId,
            @Nullable Instant after,
            @Nullable Instant before,
            @Nullable Set<Long> repositoryIds,
            @Nullable Integer limit) {
        Optional<User> optionalUser = workspaceMembershipService.findMemberByLogin(workspaceId, login);
        if (optionalUser.isEmpty()) {
            return Optional.empty();
        }

        User user = optionalUser.get();
        TimeRange timeRange = resolveTimeRange(login, after, before);
        Set<Long> repoFilter = repositoryIds == null ? Set.of() : repositoryIds;
        int resolvedLimit = limit == null
                ? DEFAULT_ACTIVITY_MONITOR_LIMIT
                : Math.max(1, Math.min(limit, MAX_ACTIVITY_MONITOR_LIMIT));

        List<ProfileReviewActivityDTO> allReviewActivity = buildReviewActivity(user.getId(), workspaceId, timeRange);
        List<PullRequestInfoDTO> allAuthoredPullRequests = profilePullRequestQueryRepository
                .findAuthoredByUserIdAndStates(
                        user.getId(), Set.of(Issue.State.OPEN), workspaceId, timeRange.after(), timeRange.before())
                .stream()
                .map(PullRequestInfoDTO::fromPullRequest)
                .toList();

        List<RepositoryInfoDTO> repositories = collectMonitorRepositories(allReviewActivity, allAuthoredPullRequests);

        List<ProfileReviewActivityDTO> filteredReviewActivity =
                filterByRepository(allReviewActivity, activity -> repositoryIdOf(activity.pullRequest()), repoFilter);
        List<PullRequestInfoDTO> filteredAuthoredPullRequests =
                filterByRepository(allAuthoredPullRequests, pr -> repositoryIdOf(pr.repository()), repoFilter);

        // Aggregate stats come from the workspace-wide ledger so the badges reflect the user's
        // full activity, while the lists are filtered to the selected repositories.
        ProfileActivityStatsDTO activityStats = profileActivityQueryService.getActivityStats(
                workspaceId, user.getId(), timeRange.after(), timeRange.before());

        return Optional.of(new ProfileActivityMonitorDTO(
                activityStats,
                filteredReviewActivity.stream().limit(resolvedLimit).toList(),
                filteredAuthoredPullRequests.stream().limit(resolvedLimit).toList(),
                repositories,
                filteredReviewActivity.size(),
                filteredAuthoredPullRequests.size()));
    }

    private TimeRange resolveTimeRange(String login, @Nullable Instant after, @Nullable Instant before) {
        Instant resolvedBefore = before == null ? Instant.now() : before;
        Instant resolvedAfter = after == null ? resolvedBefore.minus(DEFAULT_ACTIVITY_WINDOW) : after;

        if (resolvedAfter.isAfter(resolvedBefore)) {
            log.debug(
                    "Clamping activity window for user {} because after > before", LoggingUtils.sanitizeForLog(login));
            resolvedAfter = resolvedBefore;
        }

        return new TimeRange(resolvedAfter, resolvedBefore);
    }

    /**
     * Build review activity by querying ActivityEvent table (same source as leaderboard).
     *
     * <p>This queries the ActivityEvent table first to get review/comment events,
     * then hydrates entity details. This ensures consistency with leaderboard counts
     * since both use ActivityEvent as the source of truth.
     *
     * <p>The ActivityEvent table uses direct workspace.id scoping (not RepositoryToMonitor),
     * and applies the same filters as leaderboard queries:
     * <ul>
     *   <li>Hidden repo exclusion via WorkspaceTeamRepositorySettings</li>
     *   <li>Human users only (type = USER)</li>
     * </ul>
     *
     * <p><strong>Time range convention:</strong> Uses half-open interval [since, until)
     * consistent with leaderboard queries.
     */
    private List<ProfileReviewActivityDTO> buildReviewActivity(Long userId, Long workspaceId, TimeRange timeRange) {
        if (workspaceId == null || userId == null) {
            return List.of();
        }

        // Query ActivityEvent table (same source as leaderboard)
        List<ActivityEvent> activityEvents = activityEventRepository.findProfileActivityByActorInTimeframe(
                workspaceId, userId, timeRange.after(), timeRange.before());

        if (activityEvents.isEmpty()) {
            return List.of();
        }

        // Separate review events from comment events
        Set<Long> reviewIds = activityEvents.stream()
                .filter(e -> ActivityTargetType.REVIEW.getValue().equals(e.getTargetType()))
                .map(ActivityEvent::getTargetId)
                .collect(Collectors.toSet());

        Set<Long> commentIds = activityEvents.stream()
                .filter(e -> ActivityTargetType.ISSUE_COMMENT.getValue().equals(e.getTargetType()))
                .map(ActivityEvent::getTargetId)
                .collect(Collectors.toSet());

        Set<Long> reviewCommentIds = activityEvents.stream()
                .filter(e -> ActivityTargetType.REVIEW_COMMENT.getValue().equals(e.getTargetType()))
                .map(ActivityEvent::getTargetId)
                .collect(Collectors.toSet());

        // Batch-fetch entity details
        Map<Long, PullRequestReview> reviewsById = reviewIds.isEmpty()
                ? Map.of()
                : pullRequestReviewRepository.findAllByIdWithRelations(reviewIds).stream()
                        .collect(Collectors.toMap(PullRequestReview::getId, Function.identity()));

        Map<Long, IssueComment> commentsById = commentIds.isEmpty()
                ? Map.of()
                : issueCommentRepository.findAllByIdWithRelations(commentIds).stream()
                        .collect(Collectors.toMap(IssueComment::getId, Function.identity()));

        Map<Long, PullRequestReviewComment> reviewCommentsById = reviewCommentIds.isEmpty()
                ? Map.of()
                : pullRequestReviewCommentRepository.findAllByIdWithRelations(reviewCommentIds).stream()
                        .collect(Collectors.toMap(PullRequestReviewComment::getId, Function.identity()));

        Map<ActivityTargetKey, Double> xpByTarget = activityEvents.stream()
                .collect(Collectors.toMap(ActivityTargetKey::from, ActivityEvent::getXp, Double::sum));

        List<ActivityEvent> distinctActivityEvents = new ArrayList<>(activityEvents.stream()
                .collect(Collectors.toMap(
                        ActivityTargetKey::from,
                        Function.identity(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new))
                .values());

        return distinctActivityEvents.stream()
                .map(event -> {
                    int xp = XpPrecision.roundToInt(xpByTarget.getOrDefault(ActivityTargetKey.from(event), 0.0));
                    if (ActivityTargetType.REVIEW.getValue().equals(event.getTargetType())) {
                        PullRequestReview review = reviewsById.get(event.getTargetId());
                        if (review != null) {
                            return reviewActivityAssembler.assemble(review, xp);
                        }
                    } else if (ActivityTargetType.ISSUE_COMMENT.getValue().equals(event.getTargetType())) {
                        IssueComment comment = commentsById.get(event.getTargetId());
                        if (comment != null && isPullRequestComment(comment)) {
                            return reviewActivityAssembler.assemble(comment, xp);
                        }
                    } else if (ActivityTargetType.REVIEW_COMMENT.getValue().equals(event.getTargetType())) {
                        PullRequestReviewComment comment = reviewCommentsById.get(event.getTargetId());
                        if (comment != null && !isOwnPullRequestComment(comment)) {
                            return reviewActivityAssembler.assemble(comment, xp);
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ProfileReviewActivityDTO::submittedAt)
                        .reversed())
                .toList();
    }

    private static @Nullable Long repositoryIdOf(@Nullable PullRequestBaseInfoDTO pullRequest) {
        return pullRequest != null && pullRequest.repository() != null
                ? pullRequest.repository().id()
                : null;
    }

    private static @Nullable Long repositoryIdOf(@Nullable RepositoryInfoDTO repository) {
        return repository != null ? repository.id() : null;
    }

    private static <T> List<T> filterByRepository(
            List<T> items, Function<T, @Nullable Long> repositoryIdExtractor, Set<Long> repositoryIds) {
        if (repositoryIds.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> repositoryIds.contains(repositoryIdExtractor.apply(item)))
                .toList();
    }

    private List<RepositoryInfoDTO> collectMonitorRepositories(
            List<ProfileReviewActivityDTO> reviewActivity, List<PullRequestInfoDTO> authoredPullRequests) {
        Map<Long, RepositoryInfoDTO> byId = new LinkedHashMap<>();
        reviewActivity.stream()
                .map(activity -> activity.pullRequest() == null
                        ? null
                        : activity.pullRequest().repository())
                .filter(Objects::nonNull)
                .forEach(repo -> byId.putIfAbsent(repo.id(), repo));
        authoredPullRequests.stream()
                .map(PullRequestInfoDTO::repository)
                .filter(Objects::nonNull)
                .forEach(repo -> byId.putIfAbsent(repo.id(), repo));
        return byId.values().stream()
                .sorted(Comparator.comparing(RepositoryInfoDTO::nameWithOwner))
                .toList();
    }

    private ProfileXpRecordDTO buildUserXpRecord(Long workspaceId, Long userId) {
        if (workspaceId == null) {
            return ProfileXpRecordDTO.empty();
        }

        return XpSystem.getLevelProgress(activityEventRepository.findTotalXpByWorkspaceAndActor(workspaceId, userId));
    }

    private boolean isPullRequestComment(IssueComment comment) {
        Issue issue = comment.getIssue();
        if (issue == null) {
            return false;
        }
        // Unproxy to resolve the concrete SINGLE_TABLE subtype; a Hibernate proxy of
        // Issue cannot be instanceof PullRequest even when the row IS a PullRequest.
        return Hibernate.unproxy(issue) instanceof PullRequest;
    }

    private boolean isOwnPullRequestComment(PullRequestReviewComment comment) {
        return (comment.getAuthor() != null
                && comment.getPullRequest() != null
                && comment.getPullRequest().getAuthor() != null
                && Objects.equals(
                        comment.getAuthor().getId(),
                        comment.getPullRequest().getAuthor().getId()));
    }

    private record ActivityTargetKey(String targetType, Long targetId) {
        private static ActivityTargetKey from(ActivityEvent event) {
            return new ActivityTargetKey(event.getTargetType(), event.getTargetId());
        }
    }

    private record TimeRange(Instant after, Instant before) {}
}
