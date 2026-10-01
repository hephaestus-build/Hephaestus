package de.tum.cit.aet.hephaestus.integration.slack.domain;

import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackRetentionSweeper;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped access to ingested Slack messages. Every finder carries the {@code workspace_id} predicate the tenancy
 * {@code StatementInspector} requires.
 */
public interface SlackMessageRepository extends JpaRepository<SlackMessage, Long> {

    String MESSAGE_READ_GUARD = """
          AND m.workspaceId=:workspaceId AND c.workspaceId=:workspaceId
          AND c.consentState=de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE
          AND m.deletedAt IS NULL AND NOT EXISTS
            (SELECT 1 FROM SlackParticipantConsent p WHERE p.workspaceId=:workspaceId
             AND p.slackUserId=m.authorSlackUserId AND p.ingestionOptedOut=TRUE)
        """;

    @Query("""
        SELECT m.id AS id, m.slackChannelId AS slackChannelId, m.slackTs AS slackTs,
          m.slackThreadTs AS slackThreadTs, m.authorMemberId AS authorMemberId,
          m.text AS text, m.ingestedAt AS ingestedAt
        FROM SlackMessage m JOIN SlackMonitoredChannel c
          ON c.workspaceId=m.workspaceId AND c.slackChannelId=m.slackChannelId
        WHERE m.workspaceId=:workspaceId
        """ + MESSAGE_READ_GUARD + " ORDER BY m.slackChannelId,m.slackTs,m.id")
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "256"))
    java.util.stream.Stream<WorkspaceMessage> streamWorkspaceMessages(@Param("workspaceId") long workspaceId);

    /** Scalar projection keeps the full-folder stream out of Hibernate's managed-entity cache. */
    interface WorkspaceMessage {
        Long getId();

        String getSlackChannelId();

        String getSlackTs();

        @Nullable
        String getSlackThreadTs();

        @Nullable
        Long getAuthorMemberId();

        @Nullable
        String getText();

        Instant getIngestedAt();
    }

    @Query("""
        SELECT COUNT(m)>0 FROM SlackMessage m JOIN SlackMonitoredChannel c
          ON c.workspaceId=m.workspaceId AND c.slackChannelId=m.slackChannelId
        WHERE m.workspaceId=:workspaceId AND m.slackChannelId=:channelId AND m.slackTs=:messageTs
        """ + MESSAGE_READ_GUARD)
    boolean isMessageReadable(
            @Param("workspaceId") long workspaceId,
            @Param("channelId") String channelId,
            @Param("messageTs") String messageTs);

    boolean existsByWorkspaceIdAndSlackChannelIdAndSlackTs(Long workspaceId, String slackChannelId, String slackTs);

    /** Retention sweep: delete messages only for thread aggregates selected as aged. */
    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM slack_message m
         USING slack_thread t
         WHERE m.workspace_id = :workspaceId
           AND t.workspace_id = :workspaceId
           AND t.id IN (:threadIds)
           AND m.slack_channel_id = t.slack_channel_id
           AND COALESCE(m.slack_thread_ts, m.slack_ts) = t.slack_thread_ts
        """, nativeQuery = true)
    int deleteByWorkspaceIdAndThreadIds(
            @Param("workspaceId") long workspaceId, @Param("threadIds") java.util.Collection<Long> threadIds);

    long deleteByWorkspaceId(Long workspaceId);

    /**
     * Channel erasure: delete every ingested message of one channel promptly (not waiting for the 180-day
     * retention sweep) when its consent is withdrawn. Idempotent (returns 0 when the channel had nothing ingested).
     */
    long deleteByWorkspaceIdAndSlackChannelId(Long workspaceId, String slackChannelId);

    /**
     * Person erasure (opt-out / account hard-delete): delete every message this workspace stored that is authored by
     * one member — matched on the {@code author_member_id} firewall stamp, so only that individual's messages go and
     * co-authors on the same channels/threads are untouched. Idempotent (0 when the member authored nothing).
     */
    @Modifying
    @Transactional
    @Query(
            value = "DELETE FROM slack_message WHERE workspace_id = :workspaceId AND author_member_id = :memberId",
            nativeQuery = true)
    int deleteByWorkspaceIdAndAuthorMemberId(@Param("workspaceId") long workspaceId, @Param("memberId") long memberId);

    @Modifying
    @Transactional
    @Query(
            value =
                    "DELETE FROM slack_message WHERE workspace_id = :workspaceId AND author_slack_user_id = :slackUserId",
            nativeQuery = true)
    int deleteByWorkspaceIdAndAuthorSlackUserId(
            @Param("workspaceId") long workspaceId, @Param("slackUserId") String slackUserId);

    long countByWorkspaceId(Long workspaceId);

    /**
     * Locally stored message count for one channel — the cheap, already-indexed
     * ({@code idx_slack_message_thread} covers the {@code (workspace_id, slack_channel_id)} prefix) count backing
     * {@code SyncResourceState.itemCount} in the sync-observability read model. Includes tombstoned rows (a
     * deleted message is still "stored", just contentless) since this is a storage count, not a content count.
     */
    long countByWorkspaceIdAndSlackChannelId(Long workspaceId, String slackChannelId);

    /**
     * Grouped local message count per channel for one workspace, in a single query — backs the
     * sync-observability resource list's per-channel {@code itemCount} without an N+1 count per
     * monitored channel (mirrors {@code IssueRepository.RepositoryItemCount}). Includes tombstoned rows,
     * matching {@link #countByWorkspaceIdAndSlackChannelId} (a storage count, not a content count).
     *
     * @return one projection row per channel that has at least one stored message
     */
    @Query("SELECT m.slackChannelId AS slackChannelId, COUNT(m) AS itemCount FROM SlackMessage m "
            + "WHERE m.workspaceId = :workspaceId GROUP BY m.slackChannelId")
    List<ChannelItemCount> countGroupedByChannelId(@Param("workspaceId") long workspaceId);

    /** Projection for {@link #countGroupedByChannelId}. */
    interface ChannelItemCount {
        String getSlackChannelId();

        Long getItemCount();
    }

    /**
     * Retention-sweep fan-out: every workspace that currently has at least one ingested message. Native +
     * unscoped by design (the {@link SlackRetentionSweeper}
     * runs {@code @WorkspaceAgnostic}, so the tenancy {@code StatementInspector} treats this as exempt). Callers
     * outside a bypass scope will trip the inspector — that is intentional.
     */
    @Query(value = "SELECT DISTINCT workspace_id FROM slack_message", nativeQuery = true)
    List<Long> findDistinctWorkspaceIds();

    /**
     * Idempotent ingest: insert the rendered message and no-op on the unique
     * {@code (workspace_id, slack_channel_id, slack_ts)} conflict (Slack retries un-acked events). INSERTs are
     * exempt from the tenancy predicate check. Returns the number of rows actually inserted (0 on a duplicate).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, slack_thread_ts, author_slack_user_id, author_member_id, text, ingested_at)
        VALUES (:workspaceId, :slackTeamId, :slackChannelId, :slackTs, :slackThreadTs, :authorSlackUserId, :authorMemberId, :text, now())
        ON CONFLICT (workspace_id, slack_channel_id, slack_ts) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("workspaceId") long workspaceId,
            @Param("slackTeamId") String slackTeamId,
            @Param("slackChannelId") String slackChannelId,
            @Param("slackTs") String slackTs,
            @Param("slackThreadTs") @Nullable String slackThreadTs,
            @Param("authorSlackUserId") @Nullable String authorSlackUserId,
            @Param("authorMemberId") @Nullable Long authorMemberId,
            @Param("text") @Nullable String text);

    /**
     * Slack {@code message_deleted} tombstone (GDPR Art. 17), durable against out-of-order delivery: UPSERT a
     * contentless tombstone (stamp {@code deleted_at}, {@code text = NULL}). On an already-ingested row it tombstones
     * it in place; if the delete raced ahead of a NAK-redelivered base insert, it writes the tombstone first so the
     * later {@link #insertIfAbsent} ({@code ON CONFLICT DO NOTHING}) cannot resurrect the deleted content. INSERTs are
     * exempt from the tenancy predicate check; the caller applies the same channel-consent + forward-only gates as
     * ingest, so this only ever writes for an ACTIVE, in-window channel. Returns rows affected (always 1).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, text, deleted_at, ingested_at)
        VALUES (:workspaceId, :slackTeamId, :slackChannelId, :slackTs, NULL, :now, now())
        ON CONFLICT (workspace_id, slack_channel_id, slack_ts) DO UPDATE SET deleted_at = :now, text = NULL
        """, nativeQuery = true)
    int tombstone(
            @Param("workspaceId") long workspaceId,
            @Param("slackTeamId") String slackTeamId,
            @Param("slackChannelId") String slackChannelId,
            @Param("slackTs") String slackTs,
            @Param("now") Instant now);

    /**
     * Slack {@code message_changed}: replace the rendered {@code text} with the edited body and stamp
     * {@code edited_at}. Scoped JPQL UPDATE carrying the {@code workspace_id} predicate. Never resurrects a
     * tombstoned row ({@code deleted_at IS NULL} guard). Returns rows affected — a {@code 0} lets
     * {@code SlackIngestService.editMessage} tell an already-tombstoned row (present, skip) from a not-yet-ingested
     * one (re-ingest the edited body through the full consent stack), making edits durable against out-of-order
     * delivery at parity with the tombstone.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SlackMessage m SET m.text = :text, m.editedAt = :now "
            + "WHERE m.workspaceId = :workspaceId AND m.slackChannelId = :slackChannelId AND m.slackTs = :slackTs "
            + "AND m.deletedAt IS NULL")
    int applyEdit(
            @Param("workspaceId") Long workspaceId,
            @Param("slackChannelId") String slackChannelId,
            @Param("slackTs") String slackTs,
            @Param("text") @Nullable String text,
            @Param("now") Instant now);

    /**
     * Agent-owned {@code ConversationThreadProjection} SPI: the non-tombstoned turns of one thread (root
     * {@code slack_ts = threadTs} + replies {@code slack_thread_ts = threadTs}), oldest first, with the author's
     * linked login/name resolved via an ad-hoc {@code LEFT JOIN} on {@code User} (no FK — {@code authorMemberId} is
     * a scalar firewall stamp). Consent-gated on the SAME read as the message fetch (not only on a prior thread
     * scan): {@code c.consentState = ACTIVE} is a join predicate here, so a channel paused/revoked between enqueue
     * and execution — or on a retry — yields zero rows atomically with the read, never a stale/leaked message.
     * Workspace-pinned.
     *
     * @param pageable caller passes {@code PageRequest.of(0, limit)} for the per-thread message cap
     */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadMessageRow(
            m.slackTs, m.authorSlackUserId, m.authorMemberId, u.login, u.name, m.text, m.editedAt
        )
        FROM SlackMessage m
        JOIN SlackMonitoredChannel c ON c.workspaceId = m.workspaceId AND c.slackChannelId = m.slackChannelId
        LEFT JOIN de.tum.cit.aet.hephaestus.integration.scm.domain.user.User u ON u.id = m.authorMemberId
        WHERE m.workspaceId = :workspaceId
          AND m.slackChannelId = :channelId
          AND (m.slackThreadTs = :threadTs OR m.slackTs = :threadTs)
        """ + MESSAGE_READ_GUARD + " ORDER BY m.slackTs ASC")
    List<SlackThreadMessageRow> findThreadMessages(
            @Param("workspaceId") long workspaceId,
            @Param("channelId") String channelId,
            @Param("threadTs") String threadTs,
            Pageable pageable);

    /** Agent-owned {@code ConversationCandidateSource} SPI: count of non-tombstoned turns in one thread, workspace-pinned. */
    @Query("SELECT COUNT(m) FROM SlackMessage m WHERE m.workspaceId = :workspaceId AND m.slackChannelId = :channelId "
            + "AND (m.slackThreadTs = :threadTs OR m.slackTs = :threadTs) AND m.deletedAt IS NULL")
    long countLiveTurns(
            @Param("workspaceId") long workspaceId,
            @Param("channelId") String channelId,
            @Param("threadTs") String threadTs);

    /**
     * Agent-owned {@code ConversationCandidateSource} SPI: count of non-tombstoned turns in one thread whose
     * {@code slack_ts} is strictly greater than {@code watermark} (lexicographic — the same Slack-{@code ts}
     * ordering invariant {@link SlackThreadRepository#upsertOnMessage} relies on), workspace-pinned.
     */
    @Query("SELECT COUNT(m) FROM SlackMessage m WHERE m.workspaceId = :workspaceId AND m.slackChannelId = :channelId "
            + "AND (m.slackThreadTs = :threadTs OR m.slackTs = :threadTs) AND m.deletedAt IS NULL "
            + "AND m.slackTs > :watermark")
    long countLiveTurnsSince(
            @Param("workspaceId") long workspaceId,
            @Param("channelId") String channelId,
            @Param("threadTs") String threadTs,
            @Param("watermark") String watermark);
}
