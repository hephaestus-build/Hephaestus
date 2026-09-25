package de.tum.cit.aet.hephaestus.leaderboard;

import static de.tum.cit.aet.hephaestus.leaderboard.LeaguePointsConstants.POINTS_DEFAULT;
import static java.util.function.Function.identity;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScope;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for building leaderboard data.
 *
 * <p>XP scores are read from the activity event ledger, NOT recalculated on-the-fly.
 * This ensures consistency with the persisted XP values.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final UserRepository userRepository;
    private final LeaderboardReviewQueryRepository leaderboardReviewQueryRepository;
    private final LeaderboardXpQueryService leaderboardXpQueryService;
    private final TeamRepository teamRepository;
    private final TeamPathResolver teamPathResolver;
    private final LeaguePointsService leaguePointsService;
    private final WorkspaceMembershipService workspaceMembershipService;
    private final WorkspaceTeamSettingsService workspaceTeamSettingsService;
    private final WorkspaceTeamScopeResolver workspaceTeamScopeResolver;

    public LeaderboardService(
            UserRepository userRepository,
            LeaderboardReviewQueryRepository leaderboardReviewQueryRepository,
            LeaderboardXpQueryService leaderboardXpQueryService,
            TeamRepository teamRepository,
            TeamPathResolver teamPathResolver,
            LeaguePointsService leaguePointsService,
            WorkspaceMembershipService workspaceMembershipService,
            WorkspaceTeamSettingsService workspaceTeamSettingsService,
            WorkspaceTeamScopeResolver workspaceTeamScopeResolver) {
        this.userRepository = userRepository;
        this.leaderboardReviewQueryRepository = leaderboardReviewQueryRepository;
        this.leaderboardXpQueryService = leaderboardXpQueryService;
        this.teamRepository = teamRepository;
        this.teamPathResolver = teamPathResolver;
        this.leaguePointsService = leaguePointsService;
        this.workspaceMembershipService = workspaceMembershipService;
        this.workspaceTeamSettingsService = workspaceTeamSettingsService;
        this.workspaceTeamScopeResolver = workspaceTeamScopeResolver;
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntryDTO> createLeaderboard(
            Workspace workspace,
            Instant after,
            Instant before,
            String team,
            LeaderboardSortType sort,
            LeaderboardMode mode) {
        return buildLeaderboard(workspace, after, before, team, sort, mode);
    }

    private List<LeaderboardEntryDTO> buildLeaderboard(
            Workspace workspace,
            Instant after,
            Instant before,
            String team,
            LeaderboardSortType sort,
            LeaderboardMode mode) {
        if (mode == LeaderboardMode.INDIVIDUAL) {
            Optional<Team> resolvedTeam =
                    "all".equals(team) ? Optional.empty() : teamPathResolver.resolveByPath(workspace, team);
            return createIndividualLeaderboard(workspace, after, before, resolvedTeam, sort);
        }

        // Team mode aggregates across all visible teams; the team filter is ignored on purpose (documented in OpenAPI).
        return createTeamLeaderboard(workspace, after, before, sort);
    }

    private List<LeaderboardEntryDTO> createIndividualLeaderboard(
            Workspace workspace, Instant after, Instant before, Optional<Team> team, LeaderboardSortType sort) {
        return createIndividualLeaderboard(workspace, after, before, team, sort, null);
    }

    private List<LeaderboardEntryDTO> createIndividualLeaderboard(
            Workspace workspace,
            Instant after,
            Instant before,
            Optional<Team> team,
            LeaderboardSortType sort,
            @Nullable Map<Long, List<Team>> teamHierarchy) {
        if (workspace == null || workspace.getId() == null) {
            log.warn("Skipped leaderboard creation: reason=missingWorkspaceId");
            return Collections.emptyList();
        }

        log.debug(
                "Prepared leaderboard query: workspaceId={}, after={}, before={}, team={}",
                workspace.getId(),
                after,
                before,
                team.isEmpty() ? "all" : team.get().getName());

        Long workspaceId = workspace.getId();

        // Collect team IDs for filtering (if team specified)
        Set<Long> teamIds = team.map(t -> teamPathResolver.collectDescendantIds(
                        t,
                        Objects.requireNonNullElseGet(teamHierarchy, () -> teamPathResolver.buildHierarchy(workspace))))
                .orElse(Collections.emptySet());

        // XP and breakdown from activity events (source of truth)
        Map<Long, LeaderboardUserXp> activityData =
                new HashMap<>(leaderboardXpQueryService.getLeaderboardData(workspaceId, after, before, teamIds));

        // Pad with zero-activity members, enumerated by workspace membership rather than the org-login
        // string (which leaks between workspaces sharing an account_login).
        List<User> allTeamMembers;
        if (team.isPresent() && !teamIds.isEmpty()) {
            allTeamMembers = userRepository.findAllByTeamIds(teamIds);
        } else {
            allTeamMembers = workspaceMembershipService.getHumanMembersWithTeams(workspaceId);
        }

        // Add zero-score entries for team members without activity
        for (User member : allTeamMembers) {
            if (member.getId() != null && !activityData.containsKey(member.getId())) {
                activityData.put(
                        member.getId(), new LeaderboardUserXp(member, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            }
        }

        // Remove hidden members from the leaderboard
        Set<Long> hiddenMemberIds = workspaceMembershipService.getHiddenMemberIds(workspaceId);
        activityData.keySet().removeAll(hiddenMemberIds);

        if (activityData.isEmpty()) {
            log.debug("Found no team members for leaderboard: workspaceId={}", workspaceId);
            return Collections.emptyList();
        }

        // Fetch PR data for display (not for scoring)
        Set<Long> actorIds = activityData.keySet();

        List<PullRequestReview> reviews;
        if (actorIds.isEmpty()) {
            reviews = Collections.emptyList();
        } else if (team.isPresent() && !teamIds.isEmpty()) {
            reviews = leaderboardReviewQueryRepository.findAllInTimeframeByActorsOfTeams(
                    after, before, actorIds, teamIds, workspaceId);
        } else {
            reviews = leaderboardReviewQueryRepository.findAllInTimeframeByActors(after, before, actorIds, workspaceId);
        }

        Map<Long, List<PullRequestReview>> reviewsByUserId = reviews.stream()
                .filter(r -> r.getAuthor() != null && r.getAuthor().getId() != null)
                .collect(Collectors.groupingBy(r -> r.getAuthor().getId()));

        // Get league points for ranking
        Collection<User> users =
                activityData.values().stream().map(LeaderboardUserXp::user).toList();

        Map<Long, Integer> leaguePointsByUserId =
                workspaceMembershipService.getLeaguePointsSnapshot(users, workspaceId);

        // Sort by specified criteria
        Comparator<Map.Entry<Long, LeaderboardUserXp>> comparator =
                comparatorForActivityData(sort, leaguePointsByUserId);

        List<Long> ranking = activityData.entrySet().stream()
                .sorted(comparator)
                .map(Map.Entry::getKey)
                .toList();

        // Build DTOs
        List<LeaderboardEntryDTO> result = new ArrayList<>();
        for (int index = 0; index < ranking.size(); index++) {
            Long userId = ranking.get(index);
            LeaderboardUserXp data = activityData.get(userId);
            if (data == null) {
                continue;
            }

            User user = data.user();
            int score = data.totalScore();

            UserInfoDTO userDto = UserInfoDTO.fromUser(user, leaguePointsByUserId.getOrDefault(userId, POINTS_DEFAULT));

            List<PullRequestReview> userReviews = reviewsByUserId.getOrDefault(userId, Collections.emptyList());

            // Extract reviewed PRs for display popover
            List<PullRequestInfoDTO> reviewedPullRequests = userReviews.stream()
                    .map(PullRequestReview::getPullRequest)
                    .filter(Objects::nonNull)
                    .filter(pr -> pr.getAuthor() == null
                            || !Objects.equals(pr.getAuthor().getId(), userId))
                    .collect(Collectors.toMap(PullRequest::getId, pr -> pr, (a, b) -> a))
                    .values()
                    .stream()
                    .map(PullRequestInfoDTO::fromPullRequest)
                    .collect(Collectors.toList());

            // Stats come from activity events (pre-computed, not recalculated)
            LeaderboardEntryDTO entry = new LeaderboardEntryDTO(
                    index + 1,
                    score,
                    userDto,
                    null,
                    reviewedPullRequests,
                    data.reviewedPrCount(),
                    data.approvals(),
                    data.changeRequests(),
                    data.comments(),
                    data.unknowns(),
                    data.codeComments(),
                    data.ownReplies(),
                    data.openPullRequests(),
                    data.mergedPullRequests(),
                    data.closedPullRequests(),
                    data.openedIssues(),
                    data.closedIssues());
            result.add(entry);
        }

        return result;
    }

    /**
     * Comparator for activity-based leaderboard data.
     */
    private Comparator<Map.Entry<Long, LeaderboardUserXp>> comparatorForActivityData(
            LeaderboardSortType sort, Map<Long, Integer> leaguePointsByUserId) {
        return (a, b) -> {
            LeaderboardUserXp dataA = a.getValue();
            LeaderboardUserXp dataB = b.getValue();

            return switch (sort) {
                case SCORE -> Integer.compare(dataB.totalScore(), dataA.totalScore());
                case LEAGUE_POINTS -> {
                    int lpA = leaguePointsByUserId.getOrDefault(a.getKey(), POINTS_DEFAULT);
                    int lpB = leaguePointsByUserId.getOrDefault(b.getKey(), POINTS_DEFAULT);
                    // Tie-breaker: use score if league points are equal
                    int cmp = Integer.compare(lpB, lpA);
                    if (cmp != 0) {
                        yield cmp;
                    }
                    yield Integer.compare(dataB.totalScore(), dataA.totalScore());
                }
            };
        };
    }

    private List<LeaderboardEntryDTO> createTeamLeaderboard(
            Workspace workspace, Instant after, Instant before, LeaderboardSortType sort) {
        log.debug(
                "Prepared team leaderboard query: workspaceId={}, after={}, before={}",
                workspace.getId(),
                after,
                before);

        Map<Long, List<Team>> teamHierarchy = teamPathResolver.buildHierarchy(workspace);
        // Use workspace-scoped hidden settings instead of deprecated Team.hidden field
        Set<Long> hiddenTeamIds = workspaceTeamSettingsService.getHiddenTeamIds(workspace.getId());
        WorkspaceTeamScope scope = workspaceTeamScopeResolver.resolve(workspace).orElse(null);
        List<Team> targetTeams = scope == null
                ? List.of()
                : teamRepository
                        .findAllByOrganizationIgnoreCaseAndProviderId(scope.accountLogin(), scope.providerId())
                        .stream()
                        .filter(team -> team.getId() != null && !hiddenTeamIds.contains(team.getId()))
                        .toList();

        if (targetTeams.isEmpty()) {
            log.debug("Found no teams for team leaderboard: workspaceId={}", workspace.getId());
            return Collections.emptyList();
        }

        Map<Team, TeamStats> teamStatsById = targetTeams.stream().collect(Collectors.toMap(identity(), teamEntity -> {
            List<LeaderboardEntryDTO> entries = createIndividualLeaderboard(
                    workspace, after, before, Optional.of(teamEntity), LeaderboardSortType.SCORE, teamHierarchy);
            return aggregateTeamStats(entries);
        }));

        List<Map.Entry<Team, TeamStats>> sorted = teamStatsById.entrySet().stream()
                .sorted((e1, e2) -> {
                    int cmp;
                    if (sort == LeaderboardSortType.SCORE) {
                        cmp = Integer.compare(
                                e2.getValue().score(), e1.getValue().score());
                    } else {
                        cmp = Integer.compare(
                                e2.getValue().leaguePoints(), e1.getValue().leaguePoints());
                    }
                    if (cmp != 0) {
                        return cmp;
                    }
                    return e1.getKey().getName().compareTo(e2.getKey().getName());
                })
                .toList();

        List<LeaderboardEntryDTO> result = new ArrayList<>();
        Long workspaceId = workspace.getId();

        // Batch fetch all team settings to avoid N+1 queries
        Set<Long> allTeamIds =
                sorted.stream().map(entry -> entry.getKey().getId()).collect(Collectors.toSet());
        Map<Long, Set<Label>> labelFiltersByTeam =
                workspaceTeamSettingsService.getTeamLabelFiltersForTeams(workspaceId, allTeamIds);
        Map<Long, Set<Long>> hiddenRepoIdsByTeam =
                workspaceTeamSettingsService.getHiddenRepositoryIdsByTeamsMap(workspaceId, allTeamIds);
        Set<Long> hiddenMemberIds = workspaceMembershipService.getHiddenMemberIds(workspaceId);

        for (int i = 0; i < sorted.size(); i++) {
            Team teamEntity = sorted.get(i).getKey();
            TeamStats stats = sorted.get(i).getValue();
            int score = sort == LeaderboardSortType.SCORE ? stats.score() : stats.leaguePoints();

            // Get workspace-scoped settings for this team from pre-fetched maps
            boolean isHiddenInWorkspace = hiddenTeamIds.contains(teamEntity.getId());
            Set<Label> workspaceLabels = labelFiltersByTeam.getOrDefault(teamEntity.getId(), Set.of());
            Set<Long> hiddenRepoIds = hiddenRepoIdsByTeam.getOrDefault(teamEntity.getId(), Set.of());

            LeaderboardEntryDTO entry = new LeaderboardEntryDTO(
                    i + 1,
                    score,
                    null,
                    TeamInfoDTO.fromTeamWithScopeSettings(
                            teamEntity, isHiddenInWorkspace, workspaceLabels, hiddenRepoIds, hiddenMemberIds),
                    stats.reviewedPullRequests(),
                    stats.numberOfReviewedPRs(),
                    stats.numberOfApprovals(),
                    stats.numberOfChangeRequests(),
                    stats.numberOfComments(),
                    stats.numberOfUnknowns(),
                    stats.numberOfCodeComments(),
                    stats.numberOfOwnReplies(),
                    stats.numberOfOpenPullRequests(),
                    stats.numberOfMergedPullRequests(),
                    stats.numberOfClosedPullRequests(),
                    stats.numberOfOpenedIssues(),
                    stats.numberOfClosedIssues());
            result.add(entry);
        }
        return result;
    }

    /**
     * Compute league-change statistics for a single user.
     *
     * <p>The method always generates the <strong>global</strong> (workspace-wide, unfiltered)
     * leaderboard for the given timeframe and locates the user's entry in it.  This ensures
     * that projected league point changes are consistent regardless of which team filter
     * the caller is currently viewing.
     *
     * @param workspace the workspace context
     * @param login     the user's git-provider login
     * @param after     start of the timeframe (inclusive)
     * @param before    end of the timeframe (exclusive)
     * @return projected league point change
     */
    @Transactional(readOnly = true)
    public LeagueChangeDTO computeUserLeagueStats(Workspace workspace, String login, Instant after, Instant before) {
        if (workspace == null || workspace.getId() == null) {
            throw new IllegalStateException("Workspace context is required to compute league stats");
        }

        Long workspaceId = workspace.getId();
        User user = workspaceMembershipService
                .findMemberByLogin(workspaceId, login)
                .flatMap(member -> userRepository.findByIdWithEagerMergedPullRequests(member.getId()))
                .orElseThrow(() -> new EntityNotFoundException("User", login));

        // Always use the GLOBAL leaderboard (team = "all") so that league points are
        // independent of any team filter the caller might be viewing.
        List<LeaderboardEntryDTO> globalLeaderboard = buildLeaderboard(
                workspace, after, before, "all", LeaderboardSortType.SCORE, LeaderboardMode.INDIVIDUAL);

        LeaderboardEntryDTO entry = globalLeaderboard.stream()
                .filter(e -> e.user() != null && user.getId().equals(e.user().id()))
                .findFirst()
                .orElse(null);

        if (entry == null) {
            // User has no activity in this timeframe — no projected change
            return new LeagueChangeDTO(login, 0);
        }

        int currentLeaguePoints = workspaceMembershipService.getCurrentLeaguePoints(workspaceId, user);
        int projectedNewPoints = leaguePointsService.calculateNewPoints(user, currentLeaguePoints, entry);
        return new LeagueChangeDTO(user.getLogin(), projectedNewPoints - currentLeaguePoints);
    }

    private TeamStats aggregateTeamStats(List<LeaderboardEntryDTO> entries) {
        List<PullRequestInfoDTO> reviewedPullRequests = new ArrayList<>(entries.stream()
                .flatMap(e -> e.reviewedPullRequests().stream())
                .collect(Collectors.toMap(PullRequestInfoDTO::id, p -> p, (a, b) -> a))
                .values());

        int score = entries.stream().mapToInt(LeaderboardEntryDTO::score).sum();
        int leaguePoints = entries.stream()
                .mapToInt(e -> e.user() == null ? 0 : e.user().leaguePoints())
                .sum();
        int numberOfReviewedPRs = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfReviewedPRs)
                .sum();
        int numberOfApprovals = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfApprovals)
                .sum();
        int numberOfChangeRequests = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfChangeRequests)
                .sum();
        int numberOfComments =
                entries.stream().mapToInt(LeaderboardEntryDTO::numberOfComments).sum();
        int numberOfUnknowns =
                entries.stream().mapToInt(LeaderboardEntryDTO::numberOfUnknowns).sum();
        int numberOfCodeComments = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfCodeComments)
                .sum();
        int numberOfOwnReplies = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfOwnReplies)
                .sum();
        int numberOfOpenPullRequests = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfOpenPullRequests)
                .sum();
        int numberOfMergedPullRequests = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfMergedPullRequests)
                .sum();
        int numberOfClosedPullRequests = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfClosedPullRequests)
                .sum();
        int numberOfOpenedIssues = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfOpenedIssues)
                .sum();
        int numberOfClosedIssues = entries.stream()
                .mapToInt(LeaderboardEntryDTO::numberOfClosedIssues)
                .sum();

        return new TeamStats(
                score,
                leaguePoints,
                reviewedPullRequests,
                numberOfReviewedPRs,
                numberOfApprovals,
                numberOfChangeRequests,
                numberOfComments,
                numberOfUnknowns,
                numberOfCodeComments,
                numberOfOwnReplies,
                numberOfOpenPullRequests,
                numberOfMergedPullRequests,
                numberOfClosedPullRequests,
                numberOfOpenedIssues,
                numberOfClosedIssues);
    }

    private record TeamStats(
            int score,
            int leaguePoints,
            List<PullRequestInfoDTO> reviewedPullRequests,
            int numberOfReviewedPRs,
            int numberOfApprovals,
            int numberOfChangeRequests,
            int numberOfComments,
            int numberOfUnknowns,
            int numberOfCodeComments,
            int numberOfOwnReplies,
            int numberOfOpenPullRequests,
            int numberOfMergedPullRequests,
            int numberOfClosedPullRequests,
            int numberOfOpenedIssues,
            int numberOfClosedIssues) {}
}
