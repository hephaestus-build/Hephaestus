package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The feedback an account received and its responses, in every workspace, for its self-service export. */
@Transactional(readOnly = true)
public interface FeedbackExportRepository extends Repository<Feedback, UUID> {

    /** Delivered feedback addressed to the account in the given states, oldest delivery first. */
    @WorkspaceAgnostic("The self-service export reads the account's own feedback in every workspace")
    @Query(value = """
        SELECT w.slug AS workspace, f.id AS feedbackId, f.destination AS destination, f.body AS body,
          f.created_at AS createdAt, f.delivered_at AS deliveredAt,
          (SELECT CASE WHEN d.action='WITHDRAW' THEN d.decided_at END FROM (""" + FeedbackVisibilitySql.LATEST + """
          ) d) AS withdrawnAt
        FROM feedback f JOIN workspace w ON w.id=f.workspace_id
        WHERE f.developer_id=:accountId AND f.delivered_at IS NOT NULL AND f.state IN (:states)
        ORDER BY f.delivered_at, f.id
        """, nativeQuery = true)
    List<Received> findReceived(@Param("accountId") long accountId, @Param("states") Collection<String> states);

    /** The posted pieces of these feedback rows, in order. */
    @WorkspaceAgnostic("The self-service export reads the account's own feedback in every workspace")
    @Query(value = """
        SELECT c.feedback_id AS feedbackId, c.kind AS kind, c.path AS path, c.start_line AS startLine,
          c.end_line AS endLine, c.body AS body, c.posted_at AS postedAt
        FROM feedback_copy c JOIN feedback f ON f.id=c.feedback_id AND f.workspace_id=c.workspace_id
        WHERE f.developer_id=:accountId AND c.feedback_id IN (:feedbackIds)
          AND c.state='POSTED' AND c.posted_at IS NOT NULL
        ORDER BY c.feedback_id, c.position
        """, nativeQuery = true)
    List<Piece> findPostedPieces(
            @Param("accountId") long accountId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    /**
     * Every response to feedback addressed to the account, oldest first. Only the person a feedback addresses can
     * respond to it, so these are the account's own words.
     */
    @WorkspaceAgnostic("The self-service export reads the account's own feedback in every workspace")
    @Query(value = """
        SELECT w.slug AS workspace, r.feedback_id AS feedbackId, r.resolution AS resolution,
          r.usefulness AS usefulness, r.comment AS comment, r.created_at AS createdAt
        FROM feedback_response r
        JOIN feedback f ON f.id=r.feedback_id AND f.workspace_id=r.workspace_id
        JOIN workspace w ON w.id=r.workspace_id
        WHERE f.developer_id=:accountId
        ORDER BY r.created_at, r.id::text
        """, nativeQuery = true)
    List<Response> findResponses(@Param("accountId") long accountId);

    interface Received {
        String getWorkspace();

        UUID getFeedbackId();

        String getDestination();

        @Nullable
        String getBody();

        Instant getCreatedAt();

        Instant getDeliveredAt();

        @Nullable
        Instant getWithdrawnAt();
    }

    interface Piece {
        UUID getFeedbackId();

        String getKind();

        @Nullable
        String getPath();

        @Nullable
        Integer getStartLine();

        @Nullable
        Integer getEndLine();

        @Nullable
        String getBody();

        Instant getPostedAt();
    }

    interface Response {
        String getWorkspace();

        UUID getFeedbackId();

        @Nullable
        String getResolution();

        @Nullable
        String getUsefulness();

        @Nullable
        String getComment();

        Instant getCreatedAt();
    }
}
