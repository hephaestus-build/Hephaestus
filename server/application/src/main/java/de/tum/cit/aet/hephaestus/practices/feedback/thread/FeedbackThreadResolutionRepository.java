package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackThreadResolutionRepository extends JpaRepository<FeedbackThreadResolution, UUID> {
    /** One provider thread that a posted note of a developer's feedback on the work starts. */
    interface OwnedThread {
        UUID getCopyId();

        String getThreadRef();

        UUID getFeedbackId();

        UUID getReviewId();

        String getKind();

        int getPosition();
    }

    /** The developer's threads on the item, each with the copy of the newest revision that owns it. */
    @Query(value = """
        SELECT DISTINCT ON (c.thread_ref) c.id AS copyId, c.thread_ref AS threadRef, c.feedback_id AS feedbackId,
          f.review_id AS reviewId, c.kind AS kind, c.position AS position
        FROM feedback_copy c JOIN feedback f ON f.id = c.feedback_id AND f.workspace_id = c.workspace_id
        WHERE c.workspace_id = :workspaceId AND f.item_id = :itemId AND f.developer_id = :developerId
          AND f.destination = 'ON_WORK' AND c.state = 'POSTED' AND c.thread_ref IS NOT NULL
          AND c.kind IN ('LINE_NOTE', 'FILE_NOTE')
        ORDER BY c.thread_ref, c.posted_at DESC NULLS LAST, c.created_at DESC, c.id
        """, nativeQuery = true)
    List<OwnedThread> findOwnedThreads(
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("developerId") long developerId);

    /** Whether any revision of the feedback that owns the thread is human-controlled. */
    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM feedback_copy c JOIN feedback f ON f.id = c.feedback_id
            AND f.workspace_id = c.workspace_id
          WHERE c.workspace_id = :workspaceId AND c.thread_ref = :threadRef AND f.item_id = :itemId
            AND f.composition->>'humanControlled' = 'true')
        """, nativeQuery = true)
    boolean humanControlled(
            @Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("threadRef") String threadRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM FeedbackThreadResolution r
        WHERE r.workspaceId = :workspaceId AND r.itemId = :itemId AND r.threadRef = :threadRef
        """)
    Optional<FeedbackThreadResolution> lockThread(
            @Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("threadRef") String threadRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM FeedbackThreadResolution r WHERE r.id = :id AND r.workspaceId = :workspaceId")
    Optional<FeedbackThreadResolution> lockById(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Records a first ask to resolve the thread, unless a row for it exists. 1 when this call recorded it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO feedback_thread_resolution(id, workspace_id, item_id, thread_ref, copy_id, resolved, state,
          generation, changed_at)
        VALUES (:id, :workspaceId, :itemId, :threadRef, :copyId, true, 'PENDING', 1, :at)
        ON CONFLICT ON CONSTRAINT uk_feedback_thread_resolution_thread DO NOTHING
        """, nativeQuery = true)
    int insertFirstResolve(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("threadRef") String threadRef,
            @Param("copyId") UUID copyId,
            @Param("at") Instant at);
}
