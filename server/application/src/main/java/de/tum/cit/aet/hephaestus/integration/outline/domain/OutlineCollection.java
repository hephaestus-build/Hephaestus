package de.tum.cit.aet.hephaestus.integration.outline.domain;

import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * One Outline collection a workspace admin chose to mirror. A row exists only for collections that
 * were deliberately added through the admin surface; the rest of the Outline instance is never read.
 *
 * <p>Carries the human-facing catalog fields (name, urlId, color, icon) captured server-side
 * at registration so the admin table renders real names, the mirror lifecycle
 * ({@link MirrorState}), and the per-collection sync bookkeeping: {@link SyncStatus#PENDING}
 * until one clean full pass finishes, and {@code documentsSyncedAt} for stalest-first scheduling.
 * {@code documentsSyncedAt} advances only on a clean pass; a budget-exhausted or failed pass leaves it
 * untouched so nothing is skipped.
 */
@Entity
@Table(
        name = "outline_collection",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_outline_collection",
                        columnNames = {"workspace_id", "connection_id", "collection_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OutlineCollection {

    /** Admin-facing mirror lifecycle. PAUSED freezes sync but keeps the mirrored documents. */
    public enum MirrorState {
        ENABLED,
        PAUSED,
    }

    /** Whether a clean full pass has completed since registration (or since the last reset). */
    public enum SyncStatus {
        PENDING,
        COMPLETE,
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-lock guard. The admin PATCH (state ENABLED/PAUSED) and a sync pass's bookkeeping write can
     * race the SAME row — a registration or PAUSED→ENABLED resume kicks a sync off the request thread — and
     * a full-column save from the loser would otherwise clobber the winner's field. The sync side logs and
     * skips a lost race; the next pass reconciles the row regardless.
     */
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    /** Outline collection id (UUID). */
    @Column(name = "collection_id", nullable = false, length = 64)
    private String collectionId;

    /** Collection name as shown in Outline, refreshed on every catalog touch. */
    @Column(name = "name", length = 1024)
    private @Nullable String name;

    /** Outline url id (the short slug in collection URLs). */
    @Column(name = "url_id", length = 512)
    private @Nullable String urlId;

    @Column(name = "color", length = 32)
    private @Nullable String color;

    @Column(name = "icon", length = 64)
    private @Nullable String icon;

    /** Collection description as shown in Outline, refreshed on every catalog touch (truncated on write). */
    @Column(name = "description", length = 2048)
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private MirrorState state = MirrorState.ENABLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    private SyncStatus syncStatus = SyncStatus.PENDING;

    /** When the last clean pass finished; {@code NULLS FIRST} ordering makes never-synced collections go first. */
    @Column(name = "documents_synced_at")
    private @Nullable Instant documentsSyncedAt;

    /** Who on the provider can see this resource, as far as Hephaestus has proven it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_class", nullable = false, length = 16)
    @ColumnDefault("'UNPROVEN'")
    private VisibilityClass visibilityClass = VisibilityClass.UNPROVEN;

    /** When Hephaestus last proved this resource's readers on the provider. */
    @Column(name = "readers_synced_at")
    private @Nullable Instant readersSyncedAt;

    /** Last sync failure for this collection (admin-visible); cleared on the next clean pass. */
    @Column(name = "last_sync_error", length = 2048)
    private @Nullable String lastSyncError;

    /**
     * Coverage counter: how many documents upstream reported for this collection
     * ({@code documents.list} ∪ document tree) at the last enumeration — clean or budget-exhausted.
     * Together with the mirrored-live row count this answers "how much of the wiki do we hold?".
     */
    @Column(name = "documents_upstream")
    private @Nullable Integer documentsUpstream;

    /** Exports the last pass skipped because the shared budget ran out; 0 on a clean pass. */
    @Column(name = "exports_skipped_for_budget")
    private @Nullable Integer exportsSkippedForBudget;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isEnabled() {
        return state == MirrorState.ENABLED;
    }
}
