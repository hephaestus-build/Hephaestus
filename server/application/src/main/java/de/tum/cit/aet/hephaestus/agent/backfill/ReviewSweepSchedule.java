package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * A standing instruction to look at a workspace's recent work again on a cadence, whether or not anything
 * announced it. Every other way into the review path needs something to happen first, so without a
 * schedule an artifact nothing ever signalled is indistinguishable from one that was offered and
 * declined — both have no ledger row and no observation.
 *
 * <p><strong>A schedule says when, not whose.</strong> Do not add a repository or author list here:
 * {@code WorkspaceReviewScope.repositories} already defines the compute scope, and
 * {@code ReviewGate} applies it to this path too. A second copy would disagree the first
 * time somebody changed one of them; recipient selection belongs to delivery policy instead.
 *
 * <p>Each tick opens a {@link ReviewBackfillRun} rather than driving artifacts itself, because the
 * campaign machinery already paces per tick, pauses on an exhausted budget, keeps a cursor and isolates
 * each artifact. The run is opened directly in {@code RUNNING}: creating this row is the confirmation.
 */
@Entity
@Table(
        name = "review_sweep_schedule",
        uniqueConstraints = {
            // Two schedules for one kind would each see the other's ledger rows as already covered, and only
            // one could hold the workspace's single active campaign slot; the loser would look enabled
            // forever while sweeping nothing.
            @UniqueConstraint(
                    name = "uq_review_sweep_schedule_workspace_kind",
                    columnNames = {"workspace_id", "artifact_kind"}),
        },
        indexes = {@Index(name = "idx_review_sweep_schedule_due", columnList = "enabled, next_run_at")})
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ReviewSweepSchedule {

    @Id
    @EqualsAndHashCode.Include
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_review_sweep_schedule_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @NotNull
    @Column(name = "artifact_kind", nullable = false, updatable = false, length = ArtifactKind.MAX_LENGTH)
    private String artifactKind;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false, length = 16)
    private ReviewSweepCadence cadence = ReviewSweepCadence.DAILY;

    /** Bounded at write time by {@link ReviewSweepCadence#maxLookback()}, which keeps a sweep's
     * measurements admissible as LIVE. */
    @NotNull
    @Column(name = "lookback_days", nullable = false)
    private Integer lookbackDays = 2;

    /** A disabled schedule is kept rather than deleted so the cadence and window survive a pause. */
    @NotNull
    @ColumnDefault("true")
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * Advanced by whole cadences from its own previous value, never from the moment the tick actually ran:
     * adding the interval to "now" would drift later by however long each tick was delayed. The
     * per-workspace jitter is therefore a phase offset applied once at creation, never on an advance.
     */
    @NotNull
    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    /**
     * When this schedule last actually opened a campaign, not when it last came due. It decides nothing —
     * {@link #windowStart} ignores it on purpose — but moving it on a tick that opened nothing would lose
     * the only signal distinguishing a working schedule from one whose every turn has been skipped.
     */
    @Nullable
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /**
     * The account whose authority each sweep spends under, recorded on every run this schedule opens as
     * both requester and confirmer: this row is where that consent was given.
     */
    @NotNull
    @Column(name = "created_by_account_id", nullable = false, updatable = false)
    private Long createdByAccountId;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    public ArtifactKind kind() {
        return ArtifactKind.of(artifactKind);
    }

    public Duration lookback() {
        return Duration.ofDays(lookbackDays);
    }

    /**
     * The last {@link #lookbackDays} days every time, deliberately <em>not</em> anchored to
     * {@link #lastRunAt}: abutting windows never re-walk a day, so a campaign that paused on an exhausted
     * budget or never reached the end of its cursor would leave artifacts nothing ever looks at again.
     *
     * <p>The overlap is the retry, and it is free — hence a lookback ceiling of <em>twice</em> the
     * cadence. An artifact still at the state a previous sweep measured produces the same
     * {@code SignalKey}, so the ledger's unique constraint refuses the second offer and the campaign walks
     * past it without a review or a charge.
     */
    public Instant windowStart(Instant now) {
        return now.minus(lookback());
    }

    /**
     * Immediately, offset by a per-workspace jitter of under an hour. The jitter is applied exactly once,
     * here; every later occurrence is this instant plus whole cadences, so schedules created together by
     * one migration stay spread across the hour instead of waking the same minute forever.
     */
    public static Instant firstRunAt(long workspaceId, Instant now) {
        return now.plus(Duration.ofMinutes(Math.floorMod(workspaceId, 60)));
    }

    /**
     * Moves {@link #nextRunAt} to the first occurrence strictly after {@code now}, keeping its phase.
     * Missed occurrences are skipped, not queued: an instance that was down for a year must not fire a
     * year's worth of sweeps, and a sweep is about what happened recently.
     */
    public void advancePast(Instant now) {
        Duration interval = cadence.interval();
        long missed = Math.max(0, Duration.between(nextRunAt, now).toSeconds() / interval.toSeconds());
        this.nextRunAt = nextRunAt.plus(interval.multipliedBy(missed + 1));
        this.updatedAt = now;
    }
}
