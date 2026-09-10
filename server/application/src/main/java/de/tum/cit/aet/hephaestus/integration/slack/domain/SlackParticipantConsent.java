package de.tum.cit.aet.hephaestus.integration.slack.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A single individual's Slack consent decision within one workspace — the person-level layer of the two-layer
 * consent model (channel-admin activation × per-person opt-out). Keyed by {@code (workspace_id, slack_user_id)}
 * so it exists independently of whether that Slack user is currently linked to a workspace member: an unlinked
 * user can still opt out, and the decision applies once they later link.
 *
 * <p><strong>{@code ingestion_opted_out}</strong> is the mentoring-purpose firewall: when {@code true} the ingest
 * write-path ({@code SlackIngestService}) never stores this person's channel messages, composing with the
 * capability flag and the channel {@code ACTIVE} gate — ingestion happens iff capability ON <em>and</em> channel
 * ACTIVE <em>and</em> NOT {@code ingestion_opted_out}.
 *
 * <p>Workspace-scoped by a direct {@code workspaceId} field (part of the composite key), so
 * {@code WorkspaceScopedTables} classifies it scoped and the tenancy {@code StatementInspector} rides a predicate on
 * every query. The composite key is expressed with {@link IdClass} (rather than {@code @EmbeddedId}) precisely so the
 * {@code workspaceId} column is a direct entity field the data-isolation arch rule can see.
 */
@Entity
@Table(name = "slack_participant_consent")
@IdClass(SlackParticipantConsent.Id.class)
@Getter
@Setter
@NoArgsConstructor
public class SlackParticipantConsent {

    @jakarta.persistence.Id
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @jakarta.persistence.Id
    @Column(name = "slack_user_id", nullable = false, length = 32)
    private String slackUserId;

    /** {@code true} ⇒ this person's channel messages are never ingested (the mentoring-purpose firewall). */
    @Column(name = "ingestion_opted_out", nullable = false)
    @ColumnDefault("false")
    private boolean ingestionOptedOut;

    /** Persisted research opt-out bit (written by the App Home toggle); research-eligibility semantics unchanged. */
    @Column(name = "research_opted_out", nullable = false)
    @ColumnDefault("false")
    private boolean researchOptedOut;

    /** Where the decision originated (e.g. {@code SLACK_APP_HOME}); nullable for historical/backfilled rows. */
    @Column(name = "source", length = 32)
    private @Nullable String source;

    /**
     * When the current decision was made. Every write goes through the repository's native {@code ON CONFLICT}
     * upserts, which restamp this on each re-decision; the creation timestamp only covers a JPA-persisted row.
     */
    @CreationTimestamp
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** Composite primary key {@code (workspaceId, slackUserId)}. Field names mirror the entity's {@code @Id} fields. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private @Nullable Long workspaceId;
        private @Nullable String slackUserId;

        public Id(Long workspaceId, String slackUserId) {
            this.workspaceId = workspaceId;
            this.slackUserId = slackUserId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Id other)) {
                return false;
            }
            return Objects.equals(workspaceId, other.workspaceId) && Objects.equals(slackUserId, other.slackUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workspaceId, slackUserId);
        }
    }
}
