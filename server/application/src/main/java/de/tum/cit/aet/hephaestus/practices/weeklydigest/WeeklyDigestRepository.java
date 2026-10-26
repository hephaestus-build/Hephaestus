package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** The weekly digest's own reads of feedback, results and feedback copies. */
interface WeeklyDigestRepository extends Repository<Feedback, UUID> {

    /**
     * Feedback that reached the developer, by destination. On the work: delivered, or UNCONFIRMED, FAILED or REPLACED
     * after a copy appeared. In conversation: delivered. Practice page feedback never counts: DELIVERED there means the
     * developer already opened it.
     */
    String REACHED = """
        ((f.destination='ON_WORK' AND f.state IN ('DELIVERED','UNCONFIRMED','FAILED','REPLACED'))
          OR (f.destination='CONVERSATION' AND f.state='DELIVERED')) AND f.delivered_at IS NOT NULL
        """;

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id=:workspaceId AND f.developer_id=:accountId
          AND f.delivered_at>:since AND f.delivered_at<=:until AND\s""" + REACHED + """
        ORDER BY f.delivered_at,f.id::text
        """, nativeQuery = true)
    List<Feedback> findReachedBetween(long workspaceId, long accountId, Instant since, Instant until);

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id=:workspaceId AND f.developer_id=:accountId AND f.id IN (:ids)
          AND\s""" + REACHED, nativeQuery = true)
    List<Feedback> findReached(long workspaceId, long accountId, Collection<UUID> ids);

    /**
     * Whether anything that a digest can list happened in the window: feedback reached the developer, Practice page
     * feedback was prepared for them, or a review recorded a result about them.
     */
    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM feedback f WHERE f.workspace_id=:workspaceId AND f.developer_id=:accountId
            AND ((f.delivered_at>:since AND f.delivered_at<=:until AND\s""" + REACHED + """
              ) OR (f.destination='PRACTICE_PAGE' AND f.state='READY' AND f.created_at>:since AND f.created_at<=:until)))
          OR EXISTS(SELECT 1 FROM result r WHERE r.workspace_id=:workspaceId AND r.developer_id=:accountId
            AND r.recorded_at>:since AND r.recorded_at<=:until)
        """, nativeQuery = true)
    boolean anythingBetween(long workspaceId, long accountId, Instant since, Instant until);

    /** The Heph message each conversation feedback copy was shown in, once its receipt settled. */
    @Query(value = """
        SELECT c.feedback_id AS feedbackId,c.mentor_entry_id AS mentorEntryId FROM feedback_copy c
        WHERE c.workspace_id=:workspaceId AND c.feedback_id IN (:feedbackIds) AND c.kind='CHAT_TURN'
          AND c.mentor_entry_id IS NOT NULL
        ORDER BY c.position
        """, nativeQuery = true)
    List<ConversationCopy> conversationCopies(long workspaceId, Collection<UUID> feedbackIds);

    interface ConversationCopy {
        UUID getFeedbackId();

        UUID getMentorEntryId();
    }
}
