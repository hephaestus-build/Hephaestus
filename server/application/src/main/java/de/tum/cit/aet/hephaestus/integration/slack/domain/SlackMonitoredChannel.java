package de.tum.cit.aet.hephaestus.integration.slack.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A Slack channel the workspace has allow-listed for monitoring. The consent lifecycle rides a dedicated
 * state column on the allow-list row rather than a separate table. Workspace-scoped (scalar {@code workspaceId});
 * ingestion only flows for a channel whose {@link ConsentState} is {@code ACTIVE} — enforced by the message
 * handler, not by this row's mere presence.
 */
@Entity
@Table(
        name = "slack_monitored_channel",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_slack_monitored_channel",
                        columnNames = {"workspace_id", "slack_channel_id"}))
@Getter
@Setter
@NoArgsConstructor
public class SlackMonitoredChannel {

    /**
     * Values of {@code chk_slack_monitored_channel_consent}. {@code SlackChannelConsentService} owns
     * transitions and announcement confirmation.
     */
    public enum ConsentState {
        PENDING,
        ACTIVATING,
        ACTIVE,
        FAILED,
        PAUSED,
        REVOKED,
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "slack_team_id", nullable = false, length = 32)
    private String slackTeamId;

    @Column(name = "slack_channel_id", nullable = false, length = 32)
    private String slackChannelId;

    @Column(name = "channel_name", length = 256)
    private @Nullable String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_state", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private ConsentState consentState = ConsentState.PENDING;

    /** The opaque announcement marker of this setup; explicit retries reuse it. */
    @Column(name = "consent_announcement_id")
    private @Nullable UUID consentAnnouncementId;

    @Column(name = "consent_announced_at")
    private @Nullable Instant consentAnnouncedAt;

    /** Start of the current monitoring period; completed reconciliation windows do not advance it. */
    @Column(name = "ingest_after")
    private @Nullable Instant ingestAfter;

    /**
     * Reconciliation floor: activation and resume exclude the preceding unmonitored period, then each completed
     * history-sync window advances it. Kept as the exact Slack ts string so it composes with the lexicographic
     * comparisons used by the forward-only gate and the retention sweep.
     */
    @Column(name = "last_history_synced_ts", length = 32)
    private @Nullable String lastHistorySyncedTs;

    /** When the last history-sync window completed; drives staleness-first ordering under a request budget. */
    @Column(name = "history_synced_at")
    private @Nullable Instant historySyncedAt;

    /**
     * Where the check of older stored messages for deletions stopped in its current pass: the start of the last UTC
     * day it compared. Null when the next pass starts again at the watermark.
     */
    @Column(name = "history_check_cursor_ts", length = 32)
    private @Nullable String historyCheckCursorTs;

    /**
     * When the check of older stored messages last finished a full pass, or gave way after a failure; orders channels
     * stalest first.
     */
    @Column(name = "history_checked_at")
    private @Nullable Instant historyCheckedAt;

    @Column(name = "last_sync_error", length = 2048)
    private @Nullable String lastSyncError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
