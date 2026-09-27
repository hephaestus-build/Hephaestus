package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Counts and lists ledger events for the Activity pages. Counting and listing share one predicate, so a
 * count always matches the list behind it. Time ranges are half-open: {@code [from, to)}.
 */
@org.springframework.stereotype.Repository
public interface ActivityQueryRepository extends Repository<ActivityEvent, UUID> {

    /** What counts: human actors in scope, the event types asked for, and never a review of one's own work. */
    String COUNTED = """
            e.workspace.id = :#{#scope.workspaceId()}
            AND e.actor.id IN :#{#scope.actorIds()}
            AND e.actor.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
            AND e.eventType IN :eventTypes
            AND e.occurredAt >= :#{#range.from()}
            AND e.occurredAt < :#{#range.to()}
            AND NOT (e.targetType = 'review' AND EXISTS (
                SELECT 1 FROM PullRequestReview ownReview
                WHERE ownReview.id = e.targetId
                AND ownReview.pullRequest.author.id = e.actor.id
            ))
            """;

    /**
     * A team's activity, as its workspace team settings define it: in repositories the team can access and has
     * not hidden, and reviews only on pull requests carrying one of the team's labels where it filters by label.
     */
    String IN_TEAMS = """
            AND (e.repository IS NULL OR EXISTS (
                SELECT 1 FROM TeamRepositoryPermission trp
                WHERE trp.repository = e.repository
                AND trp.team.id IN :#{#scope.teamIds()}
            ))
            AND (e.repository IS NULL OR NOT EXISTS (
                SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
                WHERE wtrs.repository = e.repository
                AND wtrs.workspace.id = :#{#scope.workspaceId()}
                AND wtrs.team.id IN :#{#scope.teamIds()}
                AND wtrs.hiddenFromContributions = true
            ))
            AND (e.targetType <> 'review' OR EXISTS (
                SELECT 1 FROM PullRequestReview prr
                WHERE prr.id = e.targetId
                AND (
                    NOT EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        WHERE wtlf.workspace.id = :#{#scope.workspaceId()}
                        AND wtlf.team.id IN :#{#scope.teamIds()}
                        AND wtlf.label.repository = prr.pullRequest.repository
                    )
                    OR EXISTS (
                        SELECT 1 FROM WorkspaceTeamLabelFilter wtlf
                        JOIN wtlf.label lbl
                        WHERE wtlf.workspace.id = :#{#scope.workspaceId()}
                        AND wtlf.team.id IN :#{#scope.teamIds()}
                        AND wtlf.label.repository = prr.pullRequest.repository
                        AND lbl MEMBER OF prr.pullRequest.labels
                    )
                )
            ))
            """;

    String COUNT_BY_ACTOR_AND_TYPE = "SELECT e.actor.id AS actorId, e.eventType AS eventType, COUNT(e) AS count"
            + " FROM ActivityEvent e WHERE ";
    String GROUP_BY_ACTOR_AND_TYPE = " GROUP BY e.actor.id, e.eventType";

    String TIMELINE = "SELECT e FROM ActivityEvent e JOIN FETCH e.actor WHERE ";

    /** The events that follow the cursor {@code after} in {@link #NEWEST_FIRST} order. */
    String AFTER_CURSOR = """
            AND (e.occurredAt < :#{#after.occurredAt()}
                OR (e.occurredAt = :#{#after.occurredAt()} AND e.id < :#{#after.id()}))
            """;

    String NEWEST_FIRST = " ORDER BY e.occurredAt DESC, e.id DESC";

    @Query(COUNT_BY_ACTOR_AND_TYPE + COUNTED + GROUP_BY_ACTOR_AND_TYPE)
    List<TypeCount> countByActorAndType(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") ActivityRange range);

    @Query(COUNT_BY_ACTOR_AND_TYPE + COUNTED + IN_TEAMS + GROUP_BY_ACTOR_AND_TYPE)
    List<TypeCount> countByActorAndTypeInTeams(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") ActivityRange range);

    @Query(TIMELINE + COUNTED + AFTER_CURSOR + NEWEST_FIRST)
    Slice<ActivityEvent> findTimeline(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") ActivityRange range,
            @Param("after") ActivityTimelineCursor after,
            Pageable pageable);

    @Query(TIMELINE + COUNTED + IN_TEAMS + AFTER_CURSOR + NEWEST_FIRST)
    Slice<ActivityEvent> findTimelineInTeams(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") ActivityRange range,
            @Param("after") ActivityTimelineCursor after,
            Pageable pageable);

    interface TypeCount {
        Long getActorId();

        ActivityEventType getEventType();

        Long getCount();
    }
}
