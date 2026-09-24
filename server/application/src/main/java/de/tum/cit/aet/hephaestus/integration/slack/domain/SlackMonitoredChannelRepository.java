package de.tum.cit.aet.hephaestus.integration.slack.domain;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped access to allow-listed Slack channels. Every finder carries the {@code workspace_id} predicate the
 * tenancy {@code StatementInspector} requires.
 */
public interface SlackMonitoredChannelRepository extends JpaRepository<SlackMonitoredChannel, Long> {
    Optional<SlackMonitoredChannel> findByWorkspaceIdAndSlackChannelId(Long workspaceId, String slackChannelId);

    boolean existsByWorkspaceIdAndSlackChannelId(Long workspaceId, String slackChannelId);

    /**
     * The consent lifecycle state of one allow-listed channel, if the row exists. Read by the ingest
     * gate: a message is only persisted when this is {@link ConsentState#ACTIVE}.
     */
    @Query("SELECT c.consentState FROM SlackMonitoredChannel c "
            + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId")
    Optional<ConsentState> findConsentState(
            @Param("workspaceId") Long workspaceId, @Param("slackChannelId") String slackChannelId);

    /**
     * The consent-announcement timestamp of one allow-listed channel, if the row exists and was ever activated
     * (null before the first {@code PENDING → ACTIVE} transition stamps it). Read by the ingest write-path to
     * enforce the forward-only invariant: on an {@code ACTIVE} channel, only messages whose {@code ts} is strictly
     * after this timestamp are ever stored (pre-announcement history never enters).
     */
    @Query("SELECT c.consentAnnouncedAt FROM SlackMonitoredChannel c "
            + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId")
    Optional<Instant> findConsentAnnouncedAt(
            @Param("workspaceId") Long workspaceId, @Param("slackChannelId") String slackChannelId);

    /** All allow-listed channels for a workspace, newest-first — the admin activation control-plane listing. */
    List<SlackMonitoredChannel> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /**
     * Data-subject / channel erasure: flip a channel's consent to {@code REVOKED} so ingestion stops
     * immediately. Returns the rows affected (0 when the channel was never allow-listed). The stored message
     * history is cleared separately by the caller.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SlackMonitoredChannel c SET c.lastSyncError = null, c.consentState = "
            + "de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.REVOKED "
            + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId")
    int revokeConsent(@Param("workspaceId") Long workspaceId, @Param("slackChannelId") String slackChannelId);

    /** Allow-listed channels for a workspace in one consent state, stalest history sync first (nulls first). */
    @Query(
            "SELECT c FROM SlackMonitoredChannel c WHERE c.workspaceId = :workspaceId AND c.consentState = :consentState "
                    + "ORDER BY c.historySyncedAt ASC NULLS FIRST, c.id ASC")
    List<SlackMonitoredChannel> findForHistorySync(
            @Param("workspaceId") Long workspaceId, @Param("consentState") ConsentState consentState);

    /** Every allow-listed channel for a workspace that is not terminally revoked — the metadata-refresh set. */
    List<SlackMonitoredChannel> findByWorkspaceIdAndConsentStateNot(Long workspaceId, ConsentState consentState);

    /**
     * Advance a channel's history-reconciliation watermark after a completed sync window. Separate from the entity
     * setters so the sync never has to hold a managed entity across its (long, rate-limited) fetch loop.
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE SlackMonitoredChannel c SET c.lastHistorySyncedTs = :lastTs, c.historySyncedAt = :syncedAt, c.lastSyncError = null "
                    + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId AND c.consentState = "
                    + "de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE")
    int advanceHistoryWatermark(
            @Param("workspaceId") Long workspaceId,
            @Param("slackChannelId") String slackChannelId,
            @Param("lastTs") String lastTs,
            @Param("syncedAt") Instant syncedAt);

    @Modifying
    @Transactional
    @Query("UPDATE SlackMonitoredChannel c SET c.lastSyncError = :error "
            + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId AND c.consentState = "
            + "de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVE")
    int recordHistorySyncError(
            @Param("workspaceId") Long workspaceId,
            @Param("slackChannelId") String slackChannelId,
            @Param("error") String error);

    /** Heal a stale channel name (Slack {@code channel_rename}, or the metadata refresh). */
    @Modifying
    @Transactional
    @Query("UPDATE SlackMonitoredChannel c SET c.channelName = :channelName "
            + "WHERE c.workspaceId = :workspaceId AND c.slackChannelId = :slackChannelId")
    int updateChannelName(
            @Param("workspaceId") Long workspaceId,
            @Param("slackChannelId") String slackChannelId,
            @Param("channelName") String channelName);

    /**
     * Every workspace with at least one channel in the given consent state. Deliberately unscoped: the nightly
     * reconciliation is fleet-wide and must discover its own tenants before it can scope anything.
     */
    @Query(
            value = "SELECT DISTINCT workspace_id FROM slack_monitored_channel WHERE consent_state = :consentState",
            nativeQuery = true)
    List<Long> findDistinctWorkspaceIdsByConsentState(@Param("consentState") String consentState);

    /** Workspace purge: delete every allow-list row for one workspace. */
    long deleteByWorkspaceId(Long workspaceId);

    /** Scoped row count for a workspace. */
    long countByWorkspaceId(Long workspaceId);

    /** Scoped count by lifecycle state, used by App Home to show whether channel context is actually active. */
    long countByWorkspaceIdAndConsentState(Long workspaceId, ConsentState consentState);
}
