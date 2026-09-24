package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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
     * List thread summaries (no messages, no session_jsonl BYTEA) owned by the given user
     * inside the given workspace, newest first. Constructor projection so Postgres never
     * detoasts the multi-MB session JSONL just to render the sidebar.
     */
    @Query("SELECT new de.tum.cit.aet.hephaestus.mentor.ChatThreadSummaryDTO(t.id, t.title, t.createdAt) "
            + "FROM ChatThread t WHERE t.workspace.id = :workspaceId AND t.user.id = :userId "
            + "ORDER BY t.createdAt DESC, t.id DESC")
    Page<ChatThreadSummaryDTO> findSummariesByWorkspaceAndUser(
            @Param("workspaceId") Long workspaceId, @Param("userId") Long userId, Pageable pageable);

    /**
     * Resolve a thread within a workspace; returns empty when the thread either does not
     * exist or belongs to a different workspace.
     */
    Optional<ChatThread> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    Optional<ChatThread> findByIdAndWorkspaceIdAndUserId(UUID id, Long workspaceId, Long userId);

    /** Projection: avoids materialising the full entity to fetch the JSONL blob. Empty when missing or NULL. */
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query("SELECT t.sessionJsonl FROM ChatThread t WHERE t.id = :threadId")
    Optional<byte[]> findSessionJsonl(@Param("threadId") UUID threadId);

    /** Projection write: avoids dirty-checking the entity. {@code persistInFlight} guarantees the row exists. */
    @Modifying
    @Transactional
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query("UPDATE ChatThread t SET t.sessionJsonl = :bytes WHERE t.id = :threadId")
    int updateSessionJsonl(@Param("threadId") UUID threadId, @Param("bytes") byte[] bytes);

    @Modifying
    @Transactional
    @WorkspaceAgnostic("Caller has already resolved thread ownership via findByIdAndWorkspaceId")
    @Query("UPDATE ChatThread t SET t.sessionJsonl = NULL WHERE t.id = :threadId")
    int clearSessionJsonl(@Param("threadId") UUID threadId);

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
