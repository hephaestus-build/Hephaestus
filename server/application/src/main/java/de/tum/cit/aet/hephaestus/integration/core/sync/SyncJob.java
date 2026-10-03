package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One row per meaningful sync pass: {@link SyncJobType#INITIAL}, {@link SyncJobType#RECONCILIATION},
 * or {@link SyncJobType#BACKFILL}. Deliberately does NOT record the 60s-tick scheduler slices most
 * integrations run internally — those advance resource-level watermarks directly and would be pure
 * noise here.
 *
 * <p>Small, mutable row: {@link #status} + coarse {@link #itemsProcessed}/{@link #itemsTotal} +
 * free-form {@link #progress} JSONB + {@link #errorSummary}. The full execution template (creation,
 * one-active-job guard, lease heartbeat, zombie reaping, retention) lives in {@link SyncJobService} —
 * this class is intentionally a thin record, not a state machine.
 *
 * <p>Retention: only the newest 50 rows per connection are kept (pruned by {@link SyncJobService}
 * right after insert); this is a live-operations dashboard, not an audit trail.
 */
@Entity
// DynamicUpdate: emit UPDATEs with only the dirtied columns so a progress/heartbeat/completion write
// (which never touches cancel_requested) can't rewrite a stale snapshot's flag over a concurrently
// requested cancel. The flag itself is written only by the guarded markCancelRequested query.
@DynamicUpdate
@Table(
        name = "sync_job",
        indexes = {@Index(name = "ix_sync_job_connection_created", columnList = "connection_id, created_at DESC")})
@Getter
@Setter
@NoArgsConstructor
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sync_job_workspace"))
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sync_job_connection"))
    private Connection connection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(name = "connection_id", referencedColumnName = "id", insertable = false, updatable = false),
                @JoinColumn(
                        name = "workspace_id",
                        referencedColumnName = "workspace_id",
                        insertable = false,
                        updatable = false),
            },
            foreignKey = @ForeignKey(name = "fk_sync_job_connection_workspace"))
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @SuppressWarnings("UnusedVariable") // mapping-only: Hibernate reads it to declare fk_sync_job_connection_workspace
    private @Nullable Connection tenantOwnedConnection;

    /**
     * Denormalized from {@link #connection} so job-history queries and DTO mapping never need to
     * join/fetch the Connection row just to render a kind badge.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private IntegrationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private SyncJobType type;

    // Column named trigger_source, not "trigger" — TRIGGER is a SQL keyword; safer to sidestep it
    // entirely rather than rely on every dialect/tool agreeing it's non-reserved.
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 32)
    private SyncJobTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @ColumnDefault("'PENDING'")
    private SyncJobStatus status = SyncJobStatus.PENDING;

    @Column(name = "cancel_requested", nullable = false)
    @ColumnDefault("false")
    private boolean cancelRequested = false;

    @Column(name = "started_at")
    @Nullable
    private Instant startedAt;

    @Column(name = "finished_at")
    @Nullable
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp(source = SourceType.DB)
    private @Nullable Instant createdAt;

    /**
     * Lease heartbeat, touched every 60s by {@link SyncJobService} for every registered
     * in-JVM handle while the job is RUNNING. NOT updated by progress calls — GitHub rate-limit
     * waits can run up to ~1h and Slack replays run for hours at a throttled rate, so progress is not
     * a reliable liveness signal.
     */
    @Column(name = "heartbeat_at")
    @Nullable
    private Instant heartbeatAt;

    @Column(name = "items_processed")
    @Nullable
    private Integer itemsProcessed;

    @Column(name = "items_total")
    @Nullable
    private Integer itemsTotal;

    /** Per-phase counters, integration-specific shape, e.g. {@code {"repositories": {"done": 4, "total": 12}}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", columnDefinition = "jsonb")
    @Nullable
    private Map<String, Object> progress;

    /** Truncated to 2000 chars by {@link SyncJobService} before persisting. */
    @Column(name = "error_summary", columnDefinition = "TEXT")
    @Nullable
    private String errorSummary;

    @Column(name = "triggered_by_user_id")
    @Nullable
    private Long triggeredByUserId;

    public SyncJob(
            Workspace workspace,
            Connection connection,
            IntegrationKind kind,
            SyncJobType type,
            SyncJobTrigger trigger,
            @Nullable Long triggeredByUserId) {
        this.workspace = workspace;
        this.connection = connection;
        this.kind = kind;
        this.type = type;
        this.trigger = trigger;
        this.triggeredByUserId = triggeredByUserId;
    }

    public Long getId() {
        return Objects.requireNonNull(id, "Sync job has not been persisted");
    }

    public Instant getCreatedAt() {
        return Objects.requireNonNull(createdAt, "Sync job has not been persisted");
    }
}
