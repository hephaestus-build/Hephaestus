package de.tum.cit.aet.hephaestus.practices.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * A workspace admin's correction of one observation that was wrong when it was recorded. The observation stays
 * exactly as the review wrote it; while its invalidation is unrestored, current reads treat the claim as never
 * made and history labels it. Restoring completes the row rather than deleting it, so every correction keeps its
 * actor, reason and time, and at most one per observation is open ({@code uk_observation_invalidation_active}).
 */
@Entity
@Table(
        name = "observation_invalidation",
        indexes = {@Index(name = "idx_observation_invalidation_observation", columnList = "observation_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationInvalidation {

    public static final int MAX_REASON_LENGTH = 500;

    /** What became of the copies Hephaestus had already posted on the provider, as of this correction's state. */
    public enum ProviderCopy {
        /** Not yet settled: a delivery is still in flight or a provider edit has to be retried. */
        PENDING,
        /** Nothing citing the observation was posted to a provider. */
        NONE,
        /** Every posted summary comment now carries the correction, or no longer does after a restore. */
        UPDATED,
        /** Every summary was updated, but inline comments remain as posted: they have no edit path. */
        INLINE_REMAINS,
        /**
         * A copy may be on the provider that Hephaestus cannot correct: a summary it cannot edit or whose record is
         * incomplete, a comment the provider confirmed without returning its id, or a write it started and never
         * confirmed either way.
         */
        UNRESOLVED,
    }

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "observation_id", nullable = false, columnDefinition = "UUID")
    private UUID observationId;

    /** Keyed on the workspace too, so a correction can only ever name an observation of its own workspace. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(
                        name = "observation_id",
                        referencedColumnName = "id",
                        insertable = false,
                        updatable = false),
                @JoinColumn(
                        name = "workspace_id",
                        referencedColumnName = "workspace_id",
                        insertable = false,
                        updatable = false),
            },
            foreignKey = @ForeignKey(name = "fk_observation_invalidation_observation"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Observation observation;

    @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "invalidated_by_account_id", nullable = false)
    private Long invalidatedByAccountId;

    @Column(name = "invalidated_at", nullable = false)
    private Instant invalidatedAt;

    @Column(name = "restoration_reason", length = MAX_REASON_LENGTH)
    private @Nullable String restorationReason;

    @Column(name = "restored_by_account_id")
    private @Nullable Long restoredByAccountId;

    @Column(name = "restored_at")
    private @Nullable Instant restoredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_copy", nullable = false, length = 16)
    private ProviderCopy providerCopy;

    /** When a correction not final yet ({@code PENDING} or {@code UNRESOLVED}) is next looked at again. */
    @Column(name = "provider_copy_retry_at", nullable = false)
    private Instant providerCopyRetryAt;

    public ObservationInvalidation(Observation observation, long accountId, String reason, Instant at) {
        this.id = UUID.randomUUID();
        this.workspaceId = observation.getWorkspaceId();
        this.observationId = observation.getId();
        this.observation = observation;
        this.reason = reason;
        this.invalidatedByAccountId = accountId;
        this.invalidatedAt = at;
        this.providerCopy = ProviderCopy.PENDING;
        this.providerCopyRetryAt = at;
    }

    public void restore(long accountId, String reason, Instant at) {
        this.restorationReason = reason;
        this.restoredByAccountId = accountId;
        this.restoredAt = at;
        this.providerCopy = ProviderCopy.PENDING;
        this.providerCopyRetryAt = at;
    }
}
