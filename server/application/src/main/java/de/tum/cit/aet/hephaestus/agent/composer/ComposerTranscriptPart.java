package de.tum.cit.aet.hephaestus.agent.composer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * One part of a {@code review_compose} run's model conversation that the server issued or served to the run's claim:
 * a session (with its destination), a turn or tool answer it gave the composer, a model reply, or a tool call the model
 * wrote. The row keeps a digest, never the content. Only {@link ComposerTranscript} writes it, through conditional statements;
 * this entity only maps the table.
 */
@Entity
@Table(
        name = "composer_transcript_part",
        indexes =
                @Index(
                        name = "idx_composer_transcript_part_claim",
                        columnList = "run_id, workspace_id, lease_epoch, kind, digest"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ComposerTranscriptPart {

    /** What a part is. */
    public enum Kind {
        /** A session's instructions and tools, as the server rendered them. */
        SESSION,
        /** A turn of a session that the server gave the composer: its input, nudge or finish. */
        TEXT,
        /** A tool's answer that the server gave the composer for one call. */
        ANSWER,
        /** A model reply, linked to the exact conversation it answered. */
        REPLY,
        /** A conversation that got its answer, so that it is not sent again. */
        SERVED,
        /** A tool call that the model wrote; a composition route uses it once. */
        CALL
    }

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "lease_epoch", nullable = false, updatable = false)
    private int leaseEpoch;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 8)
    private Kind kind;

    @Column(name = "digest", nullable = false, updatable = false, length = 64)
    private String digest;

    @Column(name = "destination", updatable = false, length = 16)
    private @Nullable String destination;

    @Column(name = "call_id", updatable = false, length = 256)
    private @Nullable String callId;

    @Column(name = "tool", updatable = false, length = 64)
    private @Nullable String tool;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private @Nullable Instant usedAt;
}
