package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity event repository — single source of truth for pre-computed XP.
 *
 * <p>Timeframe queries use half-open intervals [since, until): inclusive start, exclusive end.
 */
@Repository
public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {
    /**
     * Atomically inserts an activity event if absent.
     *
     * <p>ON CONFLICT DO NOTHING avoids the race where exists() passes but save() fails
     * with DataIntegrityViolationException at commit.
     *
     * @return 1 if inserted, 0 if duplicate (conflict on workspace_id + event_key)
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO activity_event (
            id, event_key, event_type, occurred_at, actor_id,
            workspace_id, repository_id, target_type, target_id, xp, ingested_at
        )
        VALUES (
            :id, :eventKey, :eventType, :occurredAt, :actorId,
            :workspaceId, :repositoryId, :targetType, :targetId, :xp, CURRENT_TIMESTAMP
        )
        ON CONFLICT (workspace_id, event_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventKey") String eventKey,
            @Param("eventType") String eventType,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorId") @Nullable Long actorId,
            @Param("workspaceId") Long workspaceId,
            @Param("repositoryId") @Nullable Long repositoryId,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId,
            @Param("xp") double xp);

    /**
     * Backfills {@code actor_id} and {@code xp} for COMMIT_CREATED events whose actor
     * was unresolved at ingest. Without this, commits ingested before their GitLab authors
     * are resolved via email match stay orphaned and never award XP.
     */
    @WorkspaceAgnostic("Scoped by repository_id (repository belongs to one workspace)")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE activity_event
        SET actor_id = gc.author_id,
            xp = :xpPerCommit
        FROM git_commit gc
        WHERE activity_event.target_type = 'commit'
          AND activity_event.event_type = 'COMMIT_CREATED'
          AND activity_event.actor_id IS NULL
          AND activity_event.target_id = gc.id
          AND gc.author_id IS NOT NULL
          AND gc.repository_id = :repositoryId
        """, nativeQuery = true)
    int backfillCommitActors(@Param("repositoryId") Long repositoryId, @Param("xpPerCommit") double xpPerCommit);

    // Leaderboard Aggregation Queries

    /**
     * Workspace-level XP aggregation. Does NOT apply per-team hidden-repo settings; use
     * {@link #findExperiencePointsByWorkspaceAndTeamsAndTimeframe} for team-filtered results.
     */
    @Query("""
        SELECT e.actor.id as actorId,
               SUM(e.xp) as totalExperiencePoints,
               COUNT(e) as eventCount
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.actor IS NOT NULL
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        GROUP BY e.actor.id
        """)
    List<ActivityXpProjection> findExperiencePointsByWorkspaceAndTimeframe(
            @Param("workspaceId") Long workspaceId, @Param("since") Instant since, @Param("until") Instant until);

    /**
     * Team-filtered XP aggregation. Applies label filtering to review events only:
     * if a team has label filters for a repo, review events are kept only when the PR
     * has at least one matching label. Non-review events are not label-filtered.
     */
    @Query("""
        SELECT e.actor.id as actorId,
               SUM(e.xp) as totalExperiencePoints,
               COUNT(e) as eventCount
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.actor IS NOT NULL
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND EXISTS (
            SELECT 1 FROM TeamMembership tm
            WHERE tm.user = e.actor
            AND tm.team.id IN :teamIds
        )
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND (e.repository IS NULL OR EXISTS (
            SELECT 1 FROM TeamRepositoryPermission trp
            WHERE trp.repository = e.repository
            AND trp.team.id IN :teamIds
        ))
        AND (e.repository IS NULL OR NOT EXISTS (
            SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
            WHERE wtrs.repository = e.repository
            AND wtrs.workspace.id = :workspaceId
            AND wtrs.team.id IN :teamIds
            AND wtrs.hiddenFromContributions = true
        ))
        AND (
            e.targetType <> 'review'
            OR EXISTS (
                SELECT 1 FROM PullRequestReview prr
                WHERE prr.id = e.targetId
                AND (
                    NOT EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        WHERE wtlf.workspace.id = :workspaceId
                        AND wtlf.team.id IN :teamIds
                        AND wtlf.label.repository = prr.pullRequest.repository
                    )
                    OR EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        JOIN wtlf.label lbl
                        WHERE wtlf.workspace.id = :workspaceId
                        AND wtlf.team.id IN :teamIds
                        AND wtlf.label.repository = prr.pullRequest.repository
                        AND lbl MEMBER OF prr.pullRequest.labels
                    )
                )
            )
        )
        GROUP BY e.actor.id
        """)
    List<ActivityXpProjection> findExperiencePointsByWorkspaceAndTeamsAndTimeframe(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    /** Workspace-level activity breakdown by event type. Does NOT apply per-team hidden-repo settings. */
    @Query("""
        SELECT e.actor.id as actorId,
               e.eventType as eventType,
               COUNT(e) as count,
               SUM(e.xp) as experiencePoints
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND NOT (
            e.targetType = 'review_comment'
            AND EXISTS (
                SELECT 1 FROM PullRequestReviewComment prrc
                WHERE prrc.id = e.targetId
                AND prrc.author.id = prrc.pullRequest.author.id
            )
        )
        GROUP BY e.actor.id, e.eventType
        """)
    List<ActivityBreakdownProjection> findActivityBreakdown(
            @Param("workspaceId") Long workspaceId,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT e.actor.id as actorId, COUNT(e) as count
        FROM ActivityEvent e
        JOIN IssueComment c ON c.id = e.targetId
        JOIN PullRequest pr ON pr.id = c.issue.id
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.targetType = 'issue_comment'
        AND e.eventType = de.tum.cit.aet.hephaestus.activity.ActivityEventType.COMMENT_CREATED
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND c.author.id = pr.author.id
        GROUP BY e.actor.id
        """)
    List<ActorCountProjection> findOwnPullRequestConversationReplyCounts(
            @Param("workspaceId") Long workspaceId,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT e.actor.id as actorId, COUNT(e) as count
        FROM ActivityEvent e
        JOIN PullRequestReviewComment c ON c.id = e.targetId
        JOIN PullRequest pr ON pr.id = c.pullRequest.id
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.targetType = 'review_comment'
        AND e.eventType = de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENT_CREATED
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND c.author.id = pr.author.id
        GROUP BY e.actor.id
        """)
    List<ActorCountProjection> findOwnPullRequestInlineReplyCounts(
            @Param("workspaceId") Long workspaceId,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    default Map<Long, Long> countOwnPullRequestRepliesByActors(
            Long workspaceId, Set<Long> actorIds, Instant since, Instant until) {
        Map<Long, Long> counts = new HashMap<>();
        for (ActorCountProjection projection :
                findOwnPullRequestConversationReplyCounts(workspaceId, actorIds, since, until)) {
            counts.merge(projection.getActorId(), projection.getCount(), Long::sum);
        }
        for (ActorCountProjection projection :
                findOwnPullRequestInlineReplyCounts(workspaceId, actorIds, since, until)) {
            counts.merge(projection.getActorId(), projection.getCount(), Long::sum);
        }
        return counts;
    }

    @Query("""
        SELECT e.actor.id as actorId,
               e.eventType as eventType,
               COUNT(e) as count,
               SUM(e.xp) as experiencePoints
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND EXISTS (
            SELECT 1 FROM TeamMembership tm
            WHERE tm.user = e.actor
            AND tm.team.id IN :teamIds
        )
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND (e.repository IS NULL OR EXISTS (
            SELECT 1 FROM TeamRepositoryPermission trp
            WHERE trp.repository = e.repository
            AND trp.team.id IN :teamIds
        ))
        AND (e.repository IS NULL OR NOT EXISTS (
            SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
            WHERE wtrs.repository = e.repository
            AND wtrs.workspace.id = :workspaceId
            AND wtrs.team.id IN :teamIds
            AND wtrs.hiddenFromContributions = true
        ))
        AND (
            e.targetType <> 'review'
            OR EXISTS (
                SELECT 1 FROM PullRequestReview prr
                WHERE prr.id = e.targetId
                AND (
                    NOT EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        WHERE wtlf.workspace.id = :workspaceId
                        AND wtlf.team.id IN :teamIds
                        AND wtlf.label.repository = prr.pullRequest.repository
                    )
                    OR EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        JOIN wtlf.label lbl
                        WHERE wtlf.workspace.id = :workspaceId
                        AND wtlf.team.id IN :teamIds
                        AND wtlf.label.repository = prr.pullRequest.repository
                        AND lbl MEMBER OF prr.pullRequest.labels
                    )
                )
            )
        )
        AND NOT (
            e.targetType = 'review_comment'
            AND EXISTS (
                SELECT 1 FROM PullRequestReviewComment prrc
                WHERE prrc.id = e.targetId
                AND prrc.author.id = prrc.pullRequest.author.id
            )
        )
        GROUP BY e.actor.id, e.eventType
        """)
    List<ActivityBreakdownProjection> findActivityBreakdownByWorkspaceAndTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT e.actor.id as actorId, COUNT(e) as count
        FROM ActivityEvent e
        JOIN IssueComment c ON c.id = e.targetId
        JOIN PullRequest pr ON pr.id = c.issue.id
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND EXISTS (
            SELECT 1 FROM TeamMembership tm
            WHERE tm.user = e.actor
            AND tm.team.id IN :teamIds
        )
        AND e.targetType = 'issue_comment'
        AND e.eventType = de.tum.cit.aet.hephaestus.activity.ActivityEventType.COMMENT_CREATED
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND c.author.id = pr.author.id
        AND EXISTS (
            SELECT 1 FROM TeamRepositoryPermission trp
            WHERE trp.repository = pr.repository
            AND trp.team.id IN :teamIds
        )
        AND NOT EXISTS (
            SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
            WHERE wtrs.repository = pr.repository
            AND wtrs.workspace.id = :workspaceId
            AND wtrs.team.id IN :teamIds
            AND wtrs.hiddenFromContributions = true
        )
        GROUP BY e.actor.id
        """)
    List<ActorCountProjection> findOwnPullRequestConversationReplyCountsByTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT e.actor.id as actorId, COUNT(e) as count
        FROM ActivityEvent e
        JOIN PullRequestReviewComment c ON c.id = e.targetId
        JOIN PullRequest pr ON pr.id = c.pullRequest.id
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND EXISTS (
            SELECT 1 FROM TeamMembership tm
            WHERE tm.user = e.actor
            AND tm.team.id IN :teamIds
        )
        AND e.targetType = 'review_comment'
        AND e.eventType = de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENT_CREATED
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND c.author.id = pr.author.id
        AND EXISTS (
            SELECT 1 FROM TeamRepositoryPermission trp
            WHERE trp.repository = pr.repository
            AND trp.team.id IN :teamIds
        )
        AND NOT EXISTS (
            SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
            WHERE wtrs.repository = pr.repository
            AND wtrs.workspace.id = :workspaceId
            AND wtrs.team.id IN :teamIds
            AND wtrs.hiddenFromContributions = true
        )
        GROUP BY e.actor.id
        """)
    List<ActorCountProjection> findOwnPullRequestInlineReplyCountsByTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    default Map<Long, Long> countOwnPullRequestRepliesByActorsAndTeams(
            Long workspaceId, Set<Long> teamIds, Set<Long> actorIds, Instant since, Instant until) {
        Map<Long, Long> counts = new HashMap<>();
        for (ActorCountProjection projection :
                findOwnPullRequestConversationReplyCountsByTeams(workspaceId, teamIds, actorIds, since, until)) {
            counts.merge(projection.getActorId(), projection.getCount(), Long::sum);
        }
        for (ActorCountProjection projection :
                findOwnPullRequestInlineReplyCountsByTeams(workspaceId, teamIds, actorIds, since, until)) {
            counts.merge(projection.getActorId(), projection.getCount(), Long::sum);
        }
        return counts;
    }

    /**
     * Count DISTINCT PRs reviewed per actor (workspace-level).
     * Self-reviews (reviewer == PR author) are excluded.
     */
    @Query("""
        SELECT e.actor.id as actorId, COUNT(DISTINCT r.pullRequest.id) as prCount
        FROM ActivityEvent e
        JOIN PullRequestReview r ON r.id = e.targetId
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.targetType = 'review'
        AND e.eventType IN (
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_APPROVED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_CHANGES_REQUESTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_UNKNOWN
        )
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND (r.pullRequest.author IS NULL OR r.pullRequest.author.id <> e.actor.id)
        GROUP BY e.actor.id
        """)
    List<DistinctPrCountProjection> findDistinctReviewedPullRequestCountsByActors(
            @Param("workspaceId") Long workspaceId,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    default Map<Long, Long> countDistinctReviewedPullRequestsByActors(
            Long workspaceId, Set<Long> actorIds, Instant since, Instant until) {
        return findDistinctReviewedPullRequestCountsByActors(workspaceId, actorIds, since, until).stream()
                .collect(
                        Collectors.toMap(DistinctPrCountProjection::getActorId, DistinctPrCountProjection::getPrCount));
    }

    @Query("""
        SELECT e.actor.id as actorId, COUNT(DISTINCT r.pullRequest.id) as prCount
        FROM ActivityEvent e
        JOIN PullRequestReview r ON r.id = e.targetId
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id IN :actorIds
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND EXISTS (
            SELECT 1 FROM TeamMembership tm
            WHERE tm.user = e.actor
            AND tm.team.id IN :teamIds
        )
        AND e.targetType = 'review'
        AND e.eventType IN (
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_APPROVED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_CHANGES_REQUESTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_UNKNOWN
        )
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND (r.pullRequest.author IS NULL OR r.pullRequest.author.id <> e.actor.id)
        AND EXISTS (
            SELECT 1 FROM TeamRepositoryPermission trp
            WHERE trp.repository = r.pullRequest.repository
            AND trp.team.id IN :teamIds
        )
        AND NOT EXISTS (
            SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
            WHERE wtrs.repository = r.pullRequest.repository
            AND wtrs.workspace.id = :workspaceId
            AND wtrs.team.id IN :teamIds
            AND wtrs.hiddenFromContributions = true
        )
        AND (
            NOT EXISTS (
                SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                JOIN wtlf.label l
                WHERE wtlf.workspace.id = :workspaceId
                AND wtlf.team.id IN :teamIds
                AND l.repository = r.pullRequest.repository
            )
            OR EXISTS (
                SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                JOIN wtlf.label l
                WHERE wtlf.workspace.id = :workspaceId
                AND wtlf.team.id IN :teamIds
                AND l.repository = r.pullRequest.repository
                AND l MEMBER OF r.pullRequest.labels
            )
        )
        GROUP BY e.actor.id
        """)
    List<DistinctPrCountProjection> findDistinctReviewedPullRequestCountsByActorsAndTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("actorIds") Set<Long> actorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    default Map<Long, Long> countDistinctReviewedPullRequestsByActorsAndTeams(
            Long workspaceId, Set<Long> teamIds, Set<Long> actorIds, Instant since, Instant until) {
        return findDistinctReviewedPullRequestCountsByActorsAndTeams(workspaceId, teamIds, actorIds, since, until)
                .stream()
                .collect(
                        Collectors.toMap(DistinctPrCountProjection::getActorId, DistinctPrCountProjection::getPrCount));
    }

    /**
     * DISTINCT PR IDs reviewed by a single actor for profile display.
     * Unlike leaderboard queries, does NOT apply hidden-repo settings (profile shows all
     * of the user's work). Self-reviews are still excluded.
     */
    @Query("""
        SELECT DISTINCT r.pullRequest.id
        FROM ActivityEvent e
        JOIN PullRequestReview r ON r.id = e.targetId
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id = :actorId
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.targetType = 'review'
        AND e.eventType IN (
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_APPROVED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_CHANGES_REQUESTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_UNKNOWN
        )
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND (r.pullRequest.author IS NULL OR r.pullRequest.author.id <> e.actor.id)
        """)
    List<Long> findDistinctReviewedPullRequestIdsByActor(
            @Param("workspaceId") Long workspaceId,
            @Param("actorId") Long actorId,
            @Param("since") Instant since,
            @Param("until") Instant until);

    interface DistinctPrCountProjection {
        Long getActorId();

        Long getPrCount();
    }

    interface ActorCountProjection {
        Long getActorId();

        Long getCount();
    }

    // Profile XP Lookups

    /** Total lifetime XP for an actor in a workspace. Returns 0 if no events exist. */
    @Query("""
        SELECT COALESCE(SUM(e.xp), 0)
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.actor IS NOT NULL
        AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
        AND e.actor.id = :actorId
        AND e.xp > 0
        """)
    long findTotalXpByWorkspaceAndActor(@Param("workspaceId") Long workspaceId, @Param("actorId") Long actorId);

    @Query("""
        SELECT e.targetId as targetId, e.xp as xp
        FROM ActivityEvent e
        WHERE e.workspace.id = :workspaceId
        AND e.targetId IN :targetIds
        AND e.targetType IN :targetTypes
        """)
    List<TargetXpProjection> findXpByTargetIdsAndTypesInternal(
            @Param("workspaceId") Long workspaceId,
            @Param("targetIds") Set<Long> targetIds,
            @Param("targetTypes") Set<String> targetTypes);

    default List<TargetXpProjection> findXpByTargetIdsAndTypes(
            Long workspaceId, Set<Long> targetIds, Set<ActivityTargetType> targetTypes) {
        Set<String> typeValues =
                targetTypes.stream().map(ActivityTargetType::getValue).collect(Collectors.toSet());
        return findXpByTargetIdsAndTypesInternal(workspaceId, targetIds, typeValues);
    }

    interface TargetXpProjection {
        Long getTargetId();

        Double getXp();
    }

    @Query(value = "SELECT COUNT(*) FROM activity_event WHERE workspace_id = :workspaceId", nativeQuery = true)
    long countByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM activity_event WHERE workspace_id = :workspaceId", nativeQuery = true)
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    // Profile Activity Queries (ActivityEvent as source of truth)

    /**
     * Profile activity history (review + comment events, PR events excluded).
     * Does NOT apply hidden-repo settings: "hidden from contributions" only affects team
     * leaderboard ranking, not personal profile visibility.
     */
    @Query("""
        SELECT e
        FROM ActivityEvent e
        LEFT JOIN FETCH e.actor
        LEFT JOIN FETCH e.repository
        WHERE e.workspace.id = :workspaceId
        AND e.actor.id = :actorId
        AND e.occurredAt >= :since
        AND e.occurredAt < :until
        AND e.eventType IN (
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_APPROVED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_CHANGES_REQUESTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENTED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_UNKNOWN,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_DISMISSED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.REVIEW_COMMENT_CREATED,
            de.tum.cit.aet.hephaestus.activity.ActivityEventType.COMMENT_CREATED
        )
        ORDER BY e.occurredAt DESC
        """)
    List<ActivityEvent> findProfileActivityByActorInTimeframe(
            @Param("workspaceId") Long workspaceId,
            @Param("actorId") Long actorId,
            @Param("since") Instant since,
            @Param("until") Instant until);
}
