package de.tum.cit.aet.hephaestus.integration.core.signal;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One occurrence of one signal on one artifact, and what we did about it.
 *
 * <p>Writes go through {@link ArtifactSignalRepository}'s native statements rather than this mapping:
 * recording must be an insert-or-nothing race the database settles, and every state change is a
 * conditional update, neither of which Hibernate's dirty checking can express. The entity exists so
 * the reaper and the read views can work with rows in Java.
 */
@Entity
@Table(
        name = "artifact_signal",
        indexes = {@Index(name = "idx_artifact_signal_state_changed", columnList = "state, state_changed_at")},
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_artifact_signal",
                        columnNames = {"workspace_id", "artifact_kind", "artifact_id", "signal_name", "revision"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ArtifactSignal {

    @Id
    @EqualsAndHashCode.Include
    @Column(nullable = false, updatable = false)
    private UUID id;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_artifact_signal_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @NonNull
    @Column(name = "artifact_kind", nullable = false, updatable = false, length = ArtifactKind.MAX_LENGTH)
    private String artifactKind;

    @NonNull
    @Column(name = "artifact_id", nullable = false, updatable = false)
    private Long artifactId;

    @NonNull
    @Column(name = "signal_name", nullable = false, updatable = false, length = SignalName.MAX_LENGTH)
    private String signalName;

    @NonNull
    @Column(name = "revision", nullable = false, updatable = false, length = SignalRevision.MAX_LENGTH)
    private String revision;

    /** When the observed thing happened upstream — for a sync discovery, only as precise as the sync. */
    @NonNull
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "discovered_via", nullable = false, length = 16)
    private DiscoveredVia discoveredVia;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SignalState state;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "state_reason", length = 48)
    private SignalStateReason stateReason;

    /** The review this signal produced, when it produced one. */
    @Nullable
    @Column(name = "job_id")
    private UUID jobId;

    /**
     * The SCM user who asked for this, on the occasions a person asked at all — null for everything the
     * system noticed by itself.
     *
     * <p>A raw id rather than an association: this module owns the ledger and must not depend on the SCM
     * domain to write a row (ADR 0017 keeps that edge one-way). It is also what the per-person request
     * limit counts, so it must be written in the same statement as the row it attributes, never patched in
     * afterwards.
     */
    @Nullable
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    /** The person who changed the issue; unlike requestedByUserId, this is not a review requester. */
    @Nullable
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /**
     * When the {@link #state} last actually changed — and therefore how long this signal has been
     * waiting in the one it is in. Only a change of state moves it, which is what the lapse deadline
     * measures; a re-offer refused again for the same class of reason leaves the wait running.
     */
    @NonNull
    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    /**
     * When the reaper last re-offered this signal, or {@code null} if it never has.
     *
     * <p>Separate from {@link #stateChangedAt} because this one spaces the retries out and that one
     * decides when to give up. Sharing a column would let every retry postpone the deadline it races.
     */
    @Nullable
    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    /** The ledger identity of this row, for handing back to a {@link SignalRecorder}. */
    public SignalKey key() {
        return new SignalKey(workspace.getId(), artifactId, SignalName.of(signalName), new SignalRevision(revision));
    }
}
