package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ResultCommunicationRepository extends Repository<Result, UUID> {
    @Query(value = """
        SELECT DISTINCT f.id,f.destination,f.state,f.delivered_at AS deliveredAt,
          COALESCE((SELECT d.action='WITHDRAW' FROM feedback_decision d WHERE d.workspace_id=f.workspace_id AND d.feedback_id=f.id AND d.action IN ('WITHDRAW','RESTORE') ORDER BY d.decided_at DESC,d.id::text DESC LIMIT 1),false) AS withdrawn
        FROM feedback f JOIN feedback_basis b ON b.feedback_id=f.id AND b.workspace_id=f.workspace_id
        WHERE f.workspace_id=:ws AND b.result_id=:id ORDER BY f.id
        """, nativeQuery = true)
    List<BoundFeedback> boundFeedback(long ws, UUID id);

    @Query(value = """
        SELECT DISTINCT f.id,f.destination,s.comment,s.created_at AS respondedAt FROM feedback f
        JOIN feedback_basis b ON b.feedback_id=f.id AND b.workspace_id=f.workspace_id JOIN LATERAL (SELECT r.* FROM feedback_response r WHERE r.feedback_id=f.id AND r.workspace_id=f.workspace_id ORDER BY r.created_at DESC,r.id::text DESC LIMIT 1) s ON true
        WHERE f.workspace_id=:ws AND b.result_id=:id AND s.resolution='DISAGREE' ORDER BY s.created_at
        """, nativeQuery = true)
    List<Disagreement> disagreements(long ws, UUID id);

    @Query(value = """
        SELECT DISTINCT b.result_id AS resultId,f.id,f.destination,f.state,f.delivered_at AS deliveredAt,s.resolution,s.usefulness,s.comment,s.created_at AS respondedAt
        FROM feedback f JOIN feedback_basis b ON b.feedback_id=f.id AND b.workspace_id=f.workspace_id LEFT JOIN LATERAL (SELECT r.* FROM feedback_response r WHERE r.feedback_id=f.id AND r.workspace_id=f.workspace_id ORDER BY r.created_at DESC,r.id::text DESC LIMIT 1) s ON true
        WHERE f.workspace_id=:ws AND f.developer_id=:developer AND b.result_id IN (:ids) ORDER BY b.result_id,f.id
        """, nativeQuery = true)
    List<OwnFeedback> ownFeedback(long ws, long developer, Collection<UUID> ids);

    // A retracted copy is a note or summary edited to the withdrawal notice, or one that is gone, so it no longer
    // carries the feedback; only a posted copy is linked as the comment to read.
    @Query(
            value = "SELECT feedback_id AS feedbackId,id,kind,external_url AS externalUrl,posted_at AS postedAt"
                    + " FROM feedback_copy WHERE workspace_id=:ws AND feedback_id IN (:ids) AND state='POSTED'"
                    + " AND posted_at IS NOT NULL ORDER BY feedback_id,position,id",
            nativeQuery = true)
    List<OwnCopy> ownCopies(long ws, Collection<UUID> ids);

    interface BoundFeedback {
        UUID getId();

        FeedbackDestination getDestination();

        FeedbackState getState();

        @Nullable
        Instant getDeliveredAt();

        boolean getWithdrawn();
    }

    interface Disagreement {
        UUID getId();

        FeedbackDestination getDestination();

        String getComment();

        Instant getRespondedAt();
    }

    interface OwnFeedback {
        UUID getResultId();

        UUID getId();

        FeedbackDestination getDestination();

        FeedbackState getState();

        @Nullable
        Instant getDeliveredAt();

        @Nullable
        FeedbackResolution getResolution();

        @Nullable
        FeedbackUsefulness getUsefulness();

        @Nullable
        String getComment();

        @Nullable
        Instant getRespondedAt();
    }

    interface OwnCopy {
        UUID getFeedbackId();

        UUID getId();

        FeedbackCopyKind getKind();

        @Nullable
        String getExternalUrl();

        Instant getPostedAt();
    }
}
