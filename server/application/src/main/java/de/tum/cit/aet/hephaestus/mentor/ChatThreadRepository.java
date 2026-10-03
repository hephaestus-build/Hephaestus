package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ChatThreadRepository extends JpaRepository<ChatThread, UUID> {
    /**
     * List thread summaries (no messages, no session_jsonl BYTEA) owned by any of the given users
     * inside the given workspace, newest first. Constructor projection so Postgres never
     * detoasts the multi-MB session JSONL just to render the sidebar.
     */
    @Query("SELECT new de.tum.cit.aet.hephaestus.mentor.ChatThreadSummaryDTO(t.id, t.title, t.createdAt) "
            + "FROM ChatThread t WHERE t.workspace.id = :workspaceId AND t.user.id IN :userIds "
            + "ORDER BY t.createdAt DESC, t.id DESC")
    Page<ChatThreadSummaryDTO> findSummariesByWorkspaceAndUserIdIn(
            @Param("workspaceId") Long workspaceId, @Param("userIds") Collection<Long> userIds, Pageable pageable);

    /**
     * Resolve a thread within a workspace; returns empty when the thread either does not
     * exist or belongs to a different workspace.
     */
    Optional<ChatThread> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    Optional<ChatThread> findByIdAndWorkspaceIdAndUserIdIn(UUID id, Long workspaceId, Collection<Long> userIds);

    /**
     * Holds the thread's row until the caller's transaction ends, so turn admissions in one thread run one at a time
     * on every replica; foreign-key checks on new messages only share the row and are not held up. Selects the id
     * alone, because the row carries the session blob.
     */
    @Query(
            value = "SELECT id FROM chat_thread WHERE id = :id AND workspace_id = :workspaceId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<UUID> lockForTurnAdmission(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** Projection: avoids materialising the full entity to fetch the JSONL blob. Empty when missing or NULL. */
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query("SELECT t.sessionJsonl FROM ChatThread t WHERE t.id = :threadId")
    Optional<byte[]> findSessionJsonl(@Param("threadId") UUID threadId);

    /**
     * The row version a turn's journal write is conditional on. PostgreSQL gives every write to the row a
     * new {@code xmin}, including a person erasure clearing the journal, so a turn admitted before that
     * write cannot store the session it was running back over it.
     */
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query(value = "SELECT CAST(xmin AS text) FROM chat_thread WHERE id = :threadId", nativeQuery = true)
    Optional<String> findSessionVersion(@Param("threadId") UUID threadId);

    /** Writes only when the row is still at {@code version}; returns 0 when anything wrote it since. */
    @Modifying
    @Transactional
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query(
            value =
                    "UPDATE chat_thread SET session_jsonl = :bytes WHERE id = :threadId AND CAST(xmin AS text) = :version",
            nativeQuery = true)
    int updateSessionJsonl(
            @Param("threadId") UUID threadId, @Param("bytes") byte[] bytes, @Param("version") String version);

    /**
     * Bulk-delete every thread for a workspace. Cascades to {@code chat_message} +
     * {@code chat_message_vote} via existing FKs. Used by
     * {@link de.tum.cit.aet.hephaestus.mentor.adapter.MentorWorkspacePurgeAdapter} on soft purge,
     * which leaves the workspace row in place (so the workspace-level cascade can't fire).
     */
    @Modifying
    @Transactional
    int deleteByWorkspaceId(Long workspaceId);

    /**
     * Bulk-delete every thread of one {@link ThreadSurface} for a workspace. Cascades to {@code chat_message} +
     * {@code chat_message_vote} via the existing DB {@code ON DELETE CASCADE} FKs. Used to erase
     * Slack-originated DM content on an app uninstall
     * without touching the workspace's web mentor history. Returns the thread count deleted, for observability.
     */
    @Modifying
    @Transactional
    int deleteByWorkspaceIdAndSurface(Long workspaceId, ThreadSurface surface);
}
