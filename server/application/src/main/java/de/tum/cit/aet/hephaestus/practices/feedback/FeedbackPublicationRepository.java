package de.tum.cit.aet.hephaestus.practices.feedback;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Plain scalar reads of the public communication on one work; never hydrates managed rows. */
interface FeedbackPublicationRepository extends Repository<Feedback, UUID> {

    /**
     * One statement, so one snapshot, for both facts: the digest of other reviews' confirmed on-work copies and their
     * withdrawal and incorrect-basis flags, and whether another review's write started without proof of its outcome.
     */
    @Query(value = """
        WITH sibling AS (
          SELECT f.id FROM feedback f
           WHERE f.workspace_id = :workspaceId AND f.item_id = :itemId AND f.person_account_id IN (:developerIds)
             AND f.destination = 'ON_WORK' AND f.review_id <> :reviewId
        ), said AS (
          SELECT s.id,
            COALESCE((SELECT d.action = 'WITHDRAW' FROM feedback_decision d
               WHERE d.workspace_id = :workspaceId AND d.feedback_id = s.id AND d.action IN ('WITHDRAW','RESTORE')
               ORDER BY d.decided_at DESC, d.id::text DESC LIMIT 1), false) AS withdrawn,
            EXISTS(SELECT 1 FROM feedback_basis b JOIN result_mark m
               ON m.result_id = b.result_id AND m.workspace_id = b.workspace_id
               WHERE b.workspace_id = :workspaceId AND b.feedback_id = s.id
                 AND m.kind = 'INCORRECT' AND m.removed_at IS NULL) AS incorrect
          FROM sibling s
          WHERE EXISTS(SELECT 1 FROM feedback_copy c
             WHERE c.workspace_id = :workspaceId AND c.feedback_id = s.id AND c.posted_at IS NOT NULL)
        ), entry AS (
          SELECT 'F|' || said.id::text || '|' || said.withdrawn::text || '|' || said.incorrect::text AS line FROM said
          UNION ALL
          SELECT 'C|' || c.feedback_id::text || '|' || c.id::text || '|' || c.state AS line
            FROM feedback_copy c JOIN said ON said.id = c.feedback_id
           WHERE c.workspace_id = :workspaceId AND c.posted_at IS NOT NULL
        )
        SELECT encode(sha256(convert_to(:binding || COALESCE(
                 (SELECT string_agg(line, chr(10) ORDER BY line) FROM entry), ''), 'UTF8')), 'hex') AS "context",
               encode(sha256(convert_to(:binding, 'UTF8')), 'hex') AS "emptyContext",
               EXISTS(SELECT 1 FROM feedback_copy c JOIN sibling s ON s.id = c.feedback_id
                 WHERE c.workspace_id = :workspaceId AND c.write_started_at IS NOT NULL AND c.posted_at IS NULL
                   AND c.external_ref IS NULL AND c.readback IS DISTINCT FROM 'ABSENT') AS "unresolved"
        """, nativeQuery = true)
    PublicationFacts facts(
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("reviewId") UUID reviewId,
            @Param("developerIds") Collection<Long> developerIds,
            @Param("binding") String binding);

    @Query(
            value = "SELECT item_id FROM feedback WHERE id = :feedbackId AND workspace_id = :workspaceId",
            nativeQuery = true)
    Optional<Long> itemOf(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    interface PublicationFacts {
        String getContext();

        String getEmptyContext();

        boolean getUnresolved();
    }
}
