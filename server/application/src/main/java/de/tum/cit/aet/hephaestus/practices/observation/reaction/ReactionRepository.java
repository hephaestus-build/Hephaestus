package de.tum.cit.aet.hephaestus.practices.observation.reaction;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for append-only feedback-response snapshots in the legacy {@code reaction} table. */
@Repository
@WorkspaceAgnostic("Reaction scoped through Feedback.workspaceId relationship")
public interface ReactionRepository extends JpaRepository<Reaction, UUID> {
    /**
     * The response that currently stands for each of the reactor's feedback units in the workspace: the
     * newest snapshot per unit, with the unit's channel and recipient beside it so a query can narrow the
     * units without changing which snapshot stands. Binds {@code :reactorUserId} and {@code :workspaceId};
     * a query wraps it as {@code (...) latest}.
     */
    String LATEST_RESPONSE = """
            SELECT DISTINCT ON (r.feedback_id) r.feedback_id, r.usefulness, r.action, r.explanation, r.created_at,
                   fb.channel, fb.recipient_user_id
            FROM reaction r
            JOIN feedback fb ON fb.id = r.feedback_id
            WHERE r.reactor_user_id = :reactorUserId
              AND fb.workspace_id = :workspaceId
            ORDER BY r.feedback_id, r.created_at DESC, r.id DESC
        """;

    @Query(value = """
        SELECT r.usefulness AS "usefulness", r.action AS "resolution",
               r.explanation AS "comment", r.created_at AS "respondedAt"
        FROM reaction r
        WHERE r.feedback_id = :feedbackId AND r.reactor_user_id = :reactorUserId
        ORDER BY r.created_at DESC, r.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<CurrentResponseProjection> findCurrentResponse(
            @Param("feedbackId") UUID feedbackId, @Param("reactorUserId") Long reactorUserId);

    /**
     * The current answer to one piece of feedback. Every component is null when the recipient has said nothing
     * that still stands, which is how the caller tells "no response" from "a response with one dimension".
     */
    interface CurrentResponseProjection {
        @Nullable
        String getUsefulness();

        @Nullable
        String getResolution();

        @Nullable
        String getComment();

        @Nullable
        Instant getRespondedAt();
    }

    /**
     * The response that currently stands on each of these feedback units, for the units that have one: the
     * batch form of {@link #findCurrentResponse}, so a page of cards is one query rather than one per card. A
     * unit whose newest snapshot says nothing — the recipient deleted their response — is absent, exactly as
     * the single form answers empty for it. The caller passes at least one id.
     */
    @Query(value = """
        SELECT latest.feedback_id AS "feedbackId", latest.usefulness AS "usefulness", latest.action AS "resolution",
               latest.explanation AS "comment", latest.created_at AS "respondedAt"
        FROM (
        """ + LATEST_RESPONSE + """
        ) latest
        WHERE latest.feedback_id IN (:feedbackIds)
          AND (latest.usefulness IS NOT NULL OR latest.action IS NOT NULL)
        """, nativeQuery = true)
    List<CurrentResponseRow> findCurrentResponses(
            @Param("reactorUserId") Long reactorUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("feedbackIds") Collection<UUID> feedbackIds);

    interface CurrentResponseRow extends CurrentResponseProjection {
        UUID getFeedbackId();
    }

    /** Current resolution for each requested recurrence locus. The caller passes at least one key. */
    @Query(value = """
        SELECT DISTINCT ON (o.recurrence_key) o.recurrence_key AS "recurrenceKey", r.action AS "resolution"
        FROM feedback fb
        JOIN feedback_observation fo ON fo.feedback_id = fb.id
        JOIN observation o ON o.id = fo.observation_id
        JOIN LATERAL (
            SELECT response.action, response.created_at, response.id
            FROM reaction response
            WHERE response.feedback_id = fb.id AND response.reactor_user_id = :reactorUserId
            ORDER BY response.created_at DESC, response.id DESC LIMIT 1
        ) r ON r.action IS NOT NULL
        WHERE o.recurrence_key IN (:recurrenceKeys)
          AND fb.workspace_id = :workspaceId
        ORDER BY o.recurrence_key, r.created_at DESC, r.id DESC
        """, nativeQuery = true)
    List<LocusResolutionProjection> findCurrentResolutionByRecurrenceKeys(
            @Param("recurrenceKeys") Collection<String> recurrenceKeys,
            @Param("reactorUserId") Long reactorUserId,
            @Param("workspaceId") Long workspaceId);

    interface LocusResolutionProjection {
        String getRecurrenceKey();

        String getResolution();
    }

    /**
     * The recipient's IN_APP feedback whose CURRENT response resolves it — the answers
     * {@code FeedbackResolution#resolves} names — responded to inside a window: after {@code since}, at or
     * before {@code until}. Current, not ever: a response the recipient later replaced does not stand, and
     * the window is read off the response that does.
     */
    @Query(value = """
        SELECT latest.feedback_id AS "feedbackId", latest.created_at AS "respondedAt"
        FROM (
        """ + LATEST_RESPONSE + """
        ) latest
        WHERE latest.action IN ('ADDRESSED', 'NOT_APPLICABLE')
          AND latest.channel = 'IN_APP'
          AND latest.recipient_user_id = :reactorUserId
          AND latest.created_at > :since
          AND latest.created_at <= :until
        ORDER BY latest.created_at DESC
        """, nativeQuery = true)
    List<AddressedFeedbackProjection> findInAppResolvedByDeveloperBetween(
            @Param("reactorUserId") Long reactorUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") Instant since,
            @Param("until") Instant until);

    interface AddressedFeedbackProjection {
        UUID getFeedbackId();

        Instant getRespondedAt();
    }

    /** Resolution counts from each feedback unit's newest response snapshot. */
    @Query(value = """
        SELECT latest.action AS action, COUNT(*) AS count
        FROM (
        """ + LATEST_RESPONSE + """
        ) latest
        WHERE latest.action IS NOT NULL
        GROUP BY latest.action
        """, nativeQuery = true)
    List<ActionCountProjection> countByReactorAndWorkspaceGroupByAction(
            @Param("reactorUserId") Long reactorUserId, @Param("workspaceId") Long workspaceId);

    interface ActionCountProjection {
        String getAction();

        Long getCount();
    }
}
