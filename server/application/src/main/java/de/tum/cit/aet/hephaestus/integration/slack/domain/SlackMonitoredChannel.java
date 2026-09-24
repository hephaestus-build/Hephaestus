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
     * Per-channel consent lifecycle (value-constrained by {@code chk_slack_monitored_channel_consent}):
     * {@code PENDING → ACTIVE → (PAUSED ⇄ ACTIVE) → REVOKED}. Discovery lands a channel in {@code PENDING};
     * a workspace admin activates it ({@code SlackChannelConsentService.transition}), which posts the in-channel
     * consent announcement and stamps {@code consentAnnouncedAt} so ingestion is forward-only (only messages after
     * the announcement flow). {@code PAUSED} stops ingestion but keeps stored data; {@code REVOKED} erases the
     * channel's raw + derived data and can only be set up again through a new registration.
     */
    public enum ConsentState {
        PENDING,
        ACTIVE,
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

    @Column(name = "consent_announced_at")
    private @Nullable Instant consentAnnouncedAt;

    /**
     * Reconciliation watermark: the newest Slack {@code ts} the history sync has replayed for this channel. Null
     * until the first completed sync window. Kept as the exact Slack ts string so it composes with the lexicographic
     * comparisons used by the forward-only gate and the retention sweep.
     */
    @Column(name = "last_history_synced_ts", length = 32)
    private @Nullable String lastHistorySyncedTs;

    /** When the last history-sync window completed; drives staleness-first ordering under a request budget. */
    @Column(name = "history_synced_at")
    private @Nullable Instant historySyncedAt;

    @Column(name = "last_sync_error", length = 2048)
    private @Nullable String lastSyncError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
