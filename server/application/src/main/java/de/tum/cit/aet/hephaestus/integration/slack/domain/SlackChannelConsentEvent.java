package de.tum.cit.aet.hephaestus.integration.slack.domain;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One append-only audit row per channel consent-state change. The activation request and the outbox confirmation
 * have separate rows, written by {@code SlackChannelConsentService}. The row
 * captures the GDPR-accountability record — who changed a channel to what, when, and why — independently of the
 * mutable current state on {@link SlackMonitoredChannel}.
 *
 * <p><strong>Immutable by construction.</strong> The entity is {@link Immutable} and its repository exposes only
 * insert + workspace-scoped read; there is no update or delete path, so the audit trail cannot be rewritten.
 * Workspace-scoped → a tenancy predicate rides every read.
 */
@Entity
@Immutable
@Table(
        name = "slack_channel_consent_event",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_slack_channel_consent_event_tenant",
                        columnNames = {"id", "workspace_id"}))
@Getter
@NoArgsConstructor
public class SlackChannelConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "slack_channel_id", nullable = false, length = 32)
    private String slackChannelId;

    /**
     * The state the channel left. Null only for a transition of a not-yet-persisted channel.
     * Value-constrained by {@code chk_slack_channel_consent_event_from_state} ({@link ConsentState} values).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 16)
    private @Nullable ConsentState fromState;

    /** Value-constrained by {@code chk_slack_channel_consent_event_to_state} (see {@link #fromState}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 16)
    private ConsentState toState;

    @Column(name = "actor_account_id")
    private @Nullable Long actorAccountId;

    @Column(name = "reason")
    private @Nullable String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    public SlackChannelConsentEvent(
            Long workspaceId,
            String slackChannelId,
            @Nullable ConsentState fromState,
            ConsentState toState,
            @Nullable Long actorAccountId,
            @Nullable String reason) {
        this.workspaceId = workspaceId;
        this.slackChannelId = slackChannelId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorAccountId = actorAccountId;
        this.reason = reason;
    }
}
