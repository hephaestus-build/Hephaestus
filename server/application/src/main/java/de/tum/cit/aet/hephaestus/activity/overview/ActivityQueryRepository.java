package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Lists ledger events grouped by work. The page selects latest timestamps before counting its groups.
 * Time ranges are half-open: {@code [from, to)}.
 */
@org.springframework.stereotype.Repository
public interface ActivityQueryRepository extends Repository<ActivityEvent, UUID> {

    /** What counts: actors in scope, the event types asked for, and never a review of one's own work. */
    String COUNTED = """
            e.workspace.id = :#{#scope.workspaceId()}
            AND e.actor.id IN :#{#scope.actorIds()}
            AND (:#{#scope.repositoryIds().isEmpty()} = true OR e.repository.id IN :#{#scope.repositoryIds()})
            AND e.eventType IN :eventTypes
            AND e.occurredAt >= :#{#range.from()}
            AND e.occurredAt < :#{#range.to()}
            AND (e.targetType <> 'review' OR reviewedWork.author.id IS NULL
                OR reviewedWork.author.id <> e.actor.id)
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

    /**
     * The pull request or issue an event happened on: its target, or the pull request or issue its review or
     * comment belongs to; null when that is not known.
     */
    String WORK_ID = """
            CASE e.targetType
                WHEN 'pull_request' THEN e.targetId
                WHEN 'issue' THEN e.targetId
                WHEN 'review' THEN review.pullRequest.id
                WHEN 'issue_comment' THEN comment.issue.id
                WHEN 'review_comment' THEN codeComment.pullRequest.id
            END
            """;

    /** Counted events with the group they belong to: their work, or the event alone when its work is not known. */
    String GROUPED = "SELECT CASE WHEN (" + WORK_ID + ") IS NULL THEN concat('event:', cast(e.id AS String))"
            + " ELSE concat('work:', cast((" + WORK_ID + ") AS String)) END AS id,"
            + """
                e.occurredAt AS occurredAt, e.eventType AS eventType, e.actor.id AS actorId
            FROM ActivityEvent e
            LEFT JOIN PullRequestReview review ON e.targetType = 'review' AND review.id = e.targetId
            LEFT JOIN Issue reviewedWork ON reviewedWork.id = review.pullRequest.id
            LEFT JOIN IssueComment comment ON e.targetType = 'issue_comment' AND comment.id = e.targetId
            LEFT JOIN PullRequestReviewComment codeComment
                ON e.targetType = 'review_comment' AND codeComment.id = e.targetId
            WHERE
            """;

    String EVENT = "de.tum.cit.aet.hephaestus.activity.ActivityEventType.";

    /**
     * The groups after the cursor {@code after}, latest activity first, each with how often every kind happened in
     * it and whom it counts for. A column per kind, of that kind's event
     * type in {@link ActivityKind}.
     */
    String WORK_PAGE = "SELECT grouped.id AS id, MAX(grouped.occurredAt) AS lastOccurredAt,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "PULL_REQUEST_OPENED) AS pullRequestsOpened,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "PULL_REQUEST_MERGED) AS pullRequestsMerged,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "PULL_REQUEST_CLOSED) AS pullRequestsClosed,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "REVIEW_APPROVED) AS approvals,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "REVIEW_CHANGES_REQUESTED) AS changeRequests,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "REVIEW_COMMENTED) AS commentReviews,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "COMMENT_CREATED) AS comments,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "REVIEW_COMMENT_CREATED) AS codeComments,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "ISSUE_CREATED) AS issuesOpened,"
            + " COUNT(*) FILTER (WHERE grouped.eventType = " + EVENT + "ISSUE_CLOSED) AS issuesClosed,"
            + " LISTAGG(DISTINCT cast(grouped.actorId AS String), ',') AS actorIds"
            + " FROM grouped grouped JOIN page page ON page.id = grouped.id ";

    String PAGE_END = """
            GROUP BY grouped.id
            ORDER BY MAX(grouped.occurredAt) DESC, grouped.id DESC
            """;

    String CANDIDATES = "WITH grouped AS MATERIALIZED (" + GROUPED;
    String SELECT_PAGE = """
            ), page AS (
                SELECT grouped.id AS id, MAX(grouped.occurredAt) AS lastOccurredAt FROM grouped grouped
                GROUP BY grouped.id
                HAVING MAX(grouped.occurredAt) < :#{#after.lastOccurredAt()}
                    OR (MAX(grouped.occurredAt) = :#{#after.lastOccurredAt()} AND grouped.id < :#{#after.id()})
                ORDER BY MAX(grouped.occurredAt) DESC, grouped.id DESC
                LIMIT :#{#limit.max()}
            )
            """;

    @Query(CANDIDATES + COUNTED + SELECT_PAGE + WORK_PAGE + PAGE_END)
    List<WorkGroup> findWork(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") TimeRange range,
            @Param("after") ActivityWorkCursor after,
            @Param("limit") Limit limit);

    @Query(CANDIDATES + COUNTED + IN_TEAMS + SELECT_PAGE + WORK_PAGE + PAGE_END)
    List<WorkGroup> findWorkInTeams(
            @Param("scope") ActivityScope scope,
            @Param("eventTypes") Collection<ActivityEventType> eventTypes,
            @Param("range") TimeRange range,
            @Param("after") ActivityWorkCursor after,
            @Param("limit") Limit limit);

    interface WorkGroup {
        String getId();

        Instant getLastOccurredAt();

        Long getPullRequestsOpened();

        Long getPullRequestsMerged();

        Long getPullRequestsClosed();

        Long getApprovals();

        Long getChangeRequests();

        Long getCommentReviews();

        Long getComments();

        Long getCodeComments();

        Long getIssuesOpened();

        Long getIssuesClosed();

        /** The ids of everyone the group's activity counts for, comma separated. */
        String getActorIds();

        default long count(ActivityKind kind) {
            return switch (kind) {
                case PULL_REQUEST_OPENED -> getPullRequestsOpened();
                case PULL_REQUEST_MERGED -> getPullRequestsMerged();
                case PULL_REQUEST_CLOSED -> getPullRequestsClosed();
                case REVIEW_APPROVED -> getApprovals();
                case REVIEW_CHANGES_REQUESTED -> getChangeRequests();
                case REVIEW_COMMENTED -> getCommentReviews();
                case COMMENTED -> getComments();
                case CODE_COMMENTED -> getCodeComments();
                case ISSUE_OPENED -> getIssuesOpened();
                case ISSUE_CLOSED -> getIssuesClosed();
            };
        }
    }
}
