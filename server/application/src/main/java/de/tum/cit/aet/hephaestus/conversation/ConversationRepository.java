package de.tum.cit.aet.hephaestus.conversation;

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
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** The workspaces the account is a member of. */
    @WorkspaceAgnostic("An account-wide AI choice reaches every workspace of the account")
    @Query(value = "SELECT workspace_id FROM membership WHERE account_id = :accountId", nativeQuery = true)
    List<Long> findMemberWorkspaceIds(@Param("accountId") long accountId);

    Optional<Conversation> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    /** The account's main conversation in the workspace; the partial unique index allows at most one. */
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.workspaceId = :workspaceId AND c.accountId = :accountId AND c.parentConversationId IS NULL
            """)
    Optional<Conversation> findMain(@Param("workspaceId") Long workspaceId, @Param("accountId") Long accountId);

    Optional<Conversation> findByWorkspaceIdAndParentEntryId(Long workspaceId, UUID parentEntryId);

    /** Moves the read marker of a main conversation to {@code at}, and never back. */
    @Modifying
    @Query("""
            UPDATE Conversation c SET c.lastReadAt = :at
            WHERE c.id = :id AND c.workspaceId = :workspaceId AND c.parentConversationId IS NULL
              AND (c.lastReadAt IS NULL OR c.lastReadAt < :at)
            """)
    int markRead(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("at") Instant at);

    /** The read marker of a conversation as committed now, not as an earlier read of the row found it. */
    @Query("SELECT c.lastReadAt FROM Conversation c WHERE c.id = :id AND c.workspaceId = :workspaceId")
    @Nullable
    Instant findLastReadAt(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** The main conversations projected into this Slack DM, most recently active first. */
    @Query("""
            SELECT c FROM Conversation c
            WHERE c.workspaceId = :workspaceId AND c.parentConversationId IS NULL
              AND c.slackTeamId = :teamId AND c.slackUserId = :userId AND c.slackChannelId = :channelId
            ORDER BY c.lastActivityAt DESC, c.id
            """)
    List<Conversation> findMainsBySlackIdentity(
            @Param("workspaceId") Long workspaceId,
            @Param("teamId") String teamId,
            @Param("userId") String userId,
            @Param("channelId") String channelId);

    List<Conversation> findByWorkspaceIdAndParentConversationId(Long workspaceId, UUID parentConversationId);

    /**
     * Holds the conversation row until the transaction ends, so appends to one conversation take their sequence
     * numbers one at a time on every replica. Foreign-key checks of new entries only share the row.
     */
    @Query(
            value = "SELECT * FROM conversation WHERE id = :id AND workspace_id = :workspaceId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<Conversation> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** The developer's account a conversation belongs to, read without loading the conversation. */
    @Query("SELECT c.accountId FROM Conversation c WHERE c.id = :id AND c.workspaceId = :workspaceId")
    Optional<Long> findAccountIdByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** Shares the conversation row until the transaction ends: a writer that locks the row waits. */
    @Query(
            value = "SELECT id FROM conversation WHERE id = :id AND workspace_id = :workspaceId FOR SHARE",
            nativeQuery = true)
    Optional<UUID> shareByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** A transaction-scoped advisory lock on {@code key}; reads no rows. */
    @WorkspaceAgnostic("Takes an advisory lock and reads no table")
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) locked", nativeQuery = true)
    int lockMainCreation(@Param("key") String key);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Conversation c SET c.slackTeamId = NULL, c.slackUserId = NULL, c.slackChannelId = NULL
            WHERE c.workspaceId = :workspaceId AND c.slackTeamId IS NOT NULL
            """)
    int clearSlackIdentitiesByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Conversation c WHERE c.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
