package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Workspace-agnostic by intent: the queries that are not workspace-scoped are the global
 * crash-recovery sweep and single-row access by an id the caller already proved it owns. Ownership is
 * enforced upstream in {@link ChatThreadService} for every other access path.
 */
@Repository
@WorkspaceAgnostic("Crash-recovery sweep only; thread-scoped access goes through ChatThreadService")
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    @Query("SELECT m.thread.user.id FROM ChatMessage m WHERE m.id = :id AND m.thread.workspace.id = :workspaceId")
    Optional<Long> findDeveloperIdByIdAndWorkspaceId(UUID id, Long workspaceId);

    @Query("""
        SELECT m
        FROM ChatMessage m
        JOIN m.thread t
        WHERE t.workspace.id = :workspaceId
          AND t.user.id = :userId
          AND t.id = :threadId
          AND m.status = de.tum.cit.aet.hephaestus.mentor.ChatMessage.Status.completed
          AND m.role <> de.tum.cit.aet.hephaestus.mentor.ChatMessage.Role.SYSTEM
          AND (:excludedMessageId IS NULL OR m.id <> :excludedMessageId)
        ORDER BY m.createdAt ASC
        """)
    List<ChatMessage> findContextMessages(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("threadId") UUID threadId,
            @Param("excludedMessageId") @Nullable UUID excludedMessageId);

    @Query(
            "SELECT m FROM ChatMessage m JOIN FETCH m.thread t JOIN FETCH t.workspace "
                    + "WHERE m.status = de.tum.cit.aet.hephaestus.mentor.ChatMessage.Status.in_flight AND m.createdAt < :cutoff")
    List<ChatMessage> findStaleInFlightForAccounting(@Param("cutoff") Instant cutoff);

    /**
     * Add ONE served proxy call's tokens to a mentor turn's running totals. A provider call can outlive
     * the turn that issued it, so {@code status = 'in_flight'} fences the add: once the turn has gone
     * terminal it has already been billed from these columns, and a late add would corrupt that record.
     *
     * <p>Native, and leaving {@code version} alone, because it is the single writer of columns that are
     * {@code updatable = false} on the entity and must not race the orchestrator's terminal write.
     *
     * @return 1 if the turn is still running, 0 if it has ended (a safe no-op)
     */
    @Modifying
    @Query(value = """
        UPDATE chat_message
           SET llm_total_calls = llm_total_calls + 1,
               llm_total_input_tokens = llm_total_input_tokens + :input,
               llm_total_output_tokens = llm_total_output_tokens + :output,
               llm_total_reasoning_tokens = llm_total_reasoning_tokens + :reasoning,
               llm_cache_read_tokens = llm_cache_read_tokens + :cacheRead,
               llm_cache_write_tokens = llm_cache_write_tokens + :cacheWrite
         WHERE id = :id
           AND status = 'in_flight'
        """, nativeQuery = true)
    int accumulateLlmUsage(
            @Param("id") UUID id,
            @Param("input") long input,
            @Param("output") long output,
            @Param("reasoning") long reasoning,
            @Param("cacheRead") long cacheRead,
            @Param("cacheWrite") long cacheWrite);

    /**
     * The turn's accumulated proxy usage read straight from the row rather than from a possibly stale
     * entity. Callers must read it only AFTER flushing the turn's terminal status, which locks the row
     * and closes {@link #accumulateLlmUsage}'s fence — only then is the total final.
     */
    @Query("SELECT new de.tum.cit.aet.hephaestus.mentor.MentorTurnLlmUsage("
            + "m.llmTotalCalls, m.llmTotalInputTokens, m.llmTotalOutputTokens, "
            + "m.llmTotalReasoningTokens, m.llmCacheReadTokens, m.llmCacheWriteTokens) "
            + "FROM ChatMessage m WHERE m.id = :id")
    Optional<MentorTurnLlmUsage> findLlmUsageById(@Param("id") UUID id);
}
