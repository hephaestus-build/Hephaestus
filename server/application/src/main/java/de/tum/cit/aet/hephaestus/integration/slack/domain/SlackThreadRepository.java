package de.tum.cit.aet.hephaestus.integration.slack.domain;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped access to Slack thread aggregates. Every finder carries the {@code workspace_id} predicate the tenancy
 * {@code StatementInspector} requires.
 */
public interface SlackThreadRepository extends JpaRepository<SlackThread, Long> {
    Optional<SlackThread> findByWorkspaceIdAndId(Long workspaceId, Long id);

    Optional<SlackThread> findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(
            Long workspaceId, String slackChannelId, String slackThreadTs);

    /**
     * Idempotent thread-aggregate upsert on a freshly ingested message. Creates the
     * {@code (workspace_id, slack_channel_id, slack_thread_ts)} row on first sight and, on the unique conflict,
     * advances the {@code first_ts}/{@code last_ts} window (Slack {@code ts} strings sort lexicographically —
     * fixed {@code <10-digit>.<6-digit>} format), bumps {@code message_count}, and unions the author's resolved
     * member id into {@code participant_account_ids} (the GIN-indexed {@code bigint[]} the participant firewall
     * matches with {@code = ANY(...)}). Native — the conflict-resolving merge has no Spring Data derived form.
     *
     * <p>The caller passes {@code slackThreadTs := thread_ts} for a reply and {@code := ts} for a thread root, so
     * a root and its replies collapse onto one aggregate row. {@code authorAccountId} is nullable (an unlinked
     * Slack author stamps no member id); the union is a no-op in that case.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_thread (workspace_id, slack_channel_id, slack_thread_ts, first_ts, last_ts, message_count, participant_account_ids, created_at)
        VALUES (
            :workspaceId, :slackChannelId, :slackThreadTs, :slackTs, :slackTs, 1,
            CASE WHEN :authorAccountId IS NULL THEN '{}'::bigint[] ELSE ARRAY[CAST(:authorAccountId AS bigint)] END,
            now()
        )
        ON CONFLICT (workspace_id, slack_channel_id, slack_thread_ts) DO UPDATE SET
            first_ts = CASE
                WHEN slack_thread.first_ts IS NULL OR EXCLUDED.first_ts < slack_thread.first_ts
                THEN EXCLUDED.first_ts ELSE slack_thread.first_ts END,
            last_ts = CASE
                WHEN slack_thread.last_ts IS NULL OR EXCLUDED.last_ts > slack_thread.last_ts
                THEN EXCLUDED.last_ts ELSE slack_thread.last_ts END,
            message_count = slack_thread.message_count + 1,
            participant_account_ids = CASE
                WHEN :authorAccountId IS NULL OR CAST(:authorAccountId AS bigint) = ANY(slack_thread.participant_account_ids)
                THEN slack_thread.participant_account_ids
                ELSE array_append(slack_thread.participant_account_ids, CAST(:authorAccountId AS bigint)) END
        """, nativeQuery = true)
    void upsertOnMessage(
            @Param("workspaceId") long workspaceId,
            @Param("slackChannelId") String slackChannelId,
            @Param("slackThreadTs") String slackThreadTs,
            @Param("slackTs") String slackTs,
            @Param("authorAccountId") @Nullable Long authorAccountId);

    /**
     * The ids of every thread aggregate on one channel — collected on channel erasure so the derived
     * {@code chat.thread} results/feedback (keyed by these {@code slack_thread} ids as
     * {@code item.source_id}) can be hard-deleted through the practices erasure port before the aggregates themselves
     * are dropped.
     */
    @Query("SELECT t.id FROM SlackThread t WHERE t.workspaceId = :workspaceId AND t.slackChannelId = :slackChannelId")
    List<Long> findIdsByWorkspaceIdAndSlackChannelId(
            @Param("workspaceId") Long workspaceId, @Param("slackChannelId") String slackChannelId);

    /** Workspace purge: delete every thread aggregate for one workspace. */
    long deleteByWorkspaceId(Long workspaceId);

    /**
     * Channel erasure: delete every thread aggregate of one channel promptly when its consent is withdrawn (the
     * aggregates hold the {@code participant_account_ids} personal data and are the artifact the derived CONVERSATION
     * feedback points at). Idempotent (returns 0 when the channel had no threads).
     */
    long deleteByWorkspaceIdAndSlackChannelId(Long workspaceId, String slackChannelId);

    /**
     * Retention sweep: the ids of every thread aggregate for a workspace that has gone cold — its most recent
     * activity ({@code last_ts}, the newest message in the thread) is strictly older than {@code cutoffTs}.
     * Because {@code last_ts} is the maximum message {@code ts} in the thread, "last_ts older than the cutoff" is
     * exactly "every message in the thread is older than the cutoff", so the whole thread (raw messages, the
     * {@code participant_account_ids} aggregate, and the derived CONVERSATION feedback) can be erased together.
     *
     * <p>{@code cutoffTs} is the retention cutoff rendered as a Slack {@code ts} string
     * ({@code <10-digit-epoch-seconds>.000000}); the comparison is lexicographic, which equals numeric ordering for
     * the fixed {@code <10-digit>.<6-digit>} Slack {@code ts} format (the same invariant {@link #upsertOnMessage}
     * relies on).
     */
    @Query(
            "SELECT t.id FROM SlackThread t WHERE t.workspaceId = :workspaceId AND t.lastTs IS NOT NULL AND t.lastTs < :cutoffTs")
    List<Long> findAgedThreadIds(@Param("workspaceId") Long workspaceId, @Param("cutoffTs") String cutoffTs);

    /**
     * Retention sweep: drop a set of aged thread aggregates for a workspace after their derived CONVERSATION feedback
     * has already been erased through the practices port. Idempotent (0 when the id set is empty / already gone).
     * Callers guard an empty collection.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SlackThread t WHERE t.workspaceId = :workspaceId AND t.id IN :ids")
    int deleteByWorkspaceIdAndIdIn(@Param("workspaceId") Long workspaceId, @Param("ids") Collection<Long> ids);

    /**
     * Person erasure (opt-out / account hard-delete): drop one member's id out of every thread's
     * {@code participant_account_ids} for a workspace via {@code array_remove}. The append-only participant array
     * otherwise keeps a person's id (and, on id reuse, their thread visibility) after they leave — this prunes it.
     * The {@code :memberId = ANY(participant_account_ids)} guard narrows the write set to rows that actually contain
     * the member (GIN-indexed), so unaffected threads are not rewritten. Idempotent.
     *
     * @return the number of thread aggregates pruned
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "UPDATE slack_thread SET participant_account_ids = array_remove(participant_account_ids, :memberId) WHERE workspace_id = :workspaceId AND :memberId = ANY(participant_account_ids)",
            nativeQuery = true)
    int pruneParticipant(@Param("workspaceId") long workspaceId, @Param("memberId") long memberId);

    /** Scoped row count for a workspace. */
    long countByWorkspaceId(Long workspaceId);

    /**
     * Deliberately unscoped: the scheduler scans settled threads across workspaces, and every result carries its
     * workspace id.
     */
    @Query("""
        SELECT t.workspaceId AS workspaceId,
               t.id AS threadId,
               t.slackChannelId AS slackChannelId,
               c.slackTeamId AS slackTeamId,
               c.channelName AS slackChannelName,
               t.slackThreadTs AS slackThreadTs,
               t.lastTs AS lastTs,
               t.lastReviewedTs AS lastReviewedTs,
               t.participantAccountIds AS participantAccountIds
        FROM SlackThread t
        JOIN SlackMonitoredChannel c ON c.workspaceId = t.workspaceId AND c.slackChannelId = t.slackChannelId
        WHERE c.consentState = de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE
          AND t.lastTs IS NOT NULL
          AND (t.lastReviewedTs IS NULL OR t.lastTs > t.lastReviewedTs)
          AND t.messageCount >= :minMessageCount
        ORDER BY t.lastTs ASC
        """)
    List<SettledCandidateRow> findSettledCandidateRows(@Param("minMessageCount") int minMessageCount);

    /**
     * One thread by id, carrying the same consent condition the sweep applies.
     *
     * <p>Workspace-pinned, and the {@code ACTIVE} join is not optional: this is the lookup the pending-
     * signal reaper uses days after the fact, and a channel whose consent was withdrawn in the meantime
     * must stop producing reviews immediately rather than at the next sweep.
     */
    @Query("""
        SELECT t.workspaceId AS workspaceId,
               t.id AS threadId,
               t.slackChannelId AS slackChannelId,
               c.slackTeamId AS slackTeamId,
               c.channelName AS slackChannelName,
               t.slackThreadTs AS slackThreadTs,
               t.lastTs AS lastTs,
               t.lastReviewedTs AS lastReviewedTs,
               t.participantAccountIds AS participantAccountIds
        FROM SlackThread t
        JOIN SlackMonitoredChannel c ON c.workspaceId = t.workspaceId AND c.slackChannelId = t.slackChannelId
        WHERE c.consentState = de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE
          AND t.workspaceId = :workspaceId
          AND t.id = :threadId
          AND t.lastTs IS NOT NULL
        """)
    Optional<SettledCandidateRow> findConsentedCandidateRow(
            @Param("workspaceId") long workspaceId, @Param("threadId") long threadId);

    interface SettledCandidateRow {
        long getWorkspaceId();

        long getThreadId();

        String getSlackChannelId();

        String getSlackTeamId();

        @Nullable
        String getSlackChannelName();

        String getSlackThreadTs();

        String getLastTs();

        @Nullable
        String getLastReviewedTs();

        long[] getParticipantAccountIds();
    }

    /**
     * Agent-owned {@code ConversationSourceLiveness} SPI: the subset of {@code threadIds} whose source channel
     * consent is still {@code ACTIVE} in this workspace — the fail-closed allow-set a
     * {@code chat.thread}-derived row must belong to before it may reach the mentor. Callers guard an
     * empty {@code threadIds}.
     */
    @Query(
            "SELECT t.id FROM SlackThread t JOIN SlackMonitoredChannel c ON c.workspaceId = t.workspaceId AND c.slackChannelId = t.slackChannelId WHERE t.workspaceId = :workspaceId AND t.id IN :threadIds AND c.consentState = de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE")
    List<Long> findActiveThreadIds(
            @Param("workspaceId") long workspaceId, @Param("threadIds") Collection<Long> threadIds);

    /**
     * Agent-owned {@code ConversationThreadProjection} SPI (participant-firewalled thread listing): threads in the
     * workspace whose channel consent is {@code ACTIVE} and whose participant set contains {@code audienceAccountId},
     * newest-active first, capped at {@code limit}. Native — Postgres {@code = ANY(participant_account_ids)} GIN
     * array-membership has no portable JPQL form. Columns: {@code slack_channel_id}, {@code channel_name}
     * (nullable), {@code slack_thread_ts}, {@code message_count} — unpacked by the caller into its own row type.
     */
    @Query(value = """
        SELECT t.slack_channel_id, c.channel_name, t.slack_thread_ts, t.message_count
        FROM slack_thread t
        JOIN slack_monitored_channel c ON c.workspace_id = t.workspace_id AND c.slack_channel_id = t.slack_channel_id
        WHERE t.workspace_id = :workspaceId
          AND c.consent_state = 'ACTIVE'
          AND :audienceAccountId = ANY(t.participant_account_ids)
        ORDER BY t.last_ts DESC NULLS LAST
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findParticipatingThreadRows(
            @Param("workspaceId") long workspaceId,
            @Param("audienceAccountId") long audienceAccountId,
            @Param("limit") int limit);

    /** Advances the completed-review watermark without allowing late reviews to rewind it. */
    @Modifying
    @Transactional
    @Query(
            "UPDATE SlackThread t SET t.lastReviewedTs = :lastTs WHERE t.workspaceId = :workspaceId AND t.id = :threadId AND (t.lastReviewedTs IS NULL OR t.lastReviewedTs < :lastTs)")
    int advanceReviewWatermark(
            @Param("workspaceId") long workspaceId, @Param("threadId") long threadId, @Param("lastTs") String lastTs);

    interface PersonSourceIdentityRow {
        long getProviderId();

        String getSubject();

        String getTeamId();
    }

    @WorkspaceAgnostic("Instance-admin person selection pins an exact provider and native identity across workspaces")
    @Query(value = """
        SELECT DISTINCT t.id
                FROM slack_message m JOIN slack_thread t ON t.workspace_id=m.workspace_id
                    AND t.slack_channel_id=m.slack_channel_id
                    AND t.slack_thread_ts=COALESCE(m.slack_thread_ts,m.slack_ts)
                JOIN identity_provider p ON p.type='SLACK' AND p.server_url='https://slack.com'
                 WHERE p.id=:providerId AND m.author_slack_user_id=:subject AND m.slack_team_id=:teamId
        """, nativeQuery = true)
    List<Long> findPersonSourceIds(
            @Param("providerId") long providerId, @Param("subject") String subject, @Param("teamId") String teamId);

    @Query(value = """
        SELECT DISTINCT p.id AS providerId,m.author_slack_user_id AS subject,m.slack_team_id AS teamId
                FROM slack_message m JOIN slack_thread t ON t.workspace_id=m.workspace_id
                    AND t.slack_channel_id=m.slack_channel_id
                    AND t.slack_thread_ts=COALESCE(m.slack_thread_ts,m.slack_ts)
                JOIN identity_provider p ON p.type='SLACK' AND p.server_url='https://slack.com'
                 WHERE t.workspace_id=:workspaceId AND t.id=:threadId AND m.author_slack_user_id IS NOT NULL AND m.author_slack_user_id<>''
        """, nativeQuery = true)
    List<PersonSourceIdentityRow> findPersonSourceIdentities(
            @Param("workspaceId") long workspaceId, @Param("threadId") long threadId);

    @Modifying
    @Transactional
    @WorkspaceAgnostic("Verified account absorption moves and deduplicates participants across every workspace")
    @Query(value = """
        UPDATE slack_thread SET participant_account_ids=ARRAY(
            SELECT DISTINCT CASE WHEN account_id=:unclaimed THEN :claimed ELSE account_id END
            FROM unnest(participant_account_ids) account_id ORDER BY 1)
        WHERE :unclaimed=ANY(participant_account_ids)
        """, nativeQuery = true)
    int absorbParticipantAccount(@Param("claimed") long claimedAccountId, @Param("unclaimed") long unclaimedAccountId);

    @WorkspaceAgnostic("Account absorption verifies source references before removing source memberships")
    @Query(
            value = "SELECT EXISTS(SELECT 1 FROM slack_thread WHERE :accountId=ANY(participant_account_ids))",
            nativeQuery = true)
    boolean containsAccount(@Param("accountId") long accountId);
}
