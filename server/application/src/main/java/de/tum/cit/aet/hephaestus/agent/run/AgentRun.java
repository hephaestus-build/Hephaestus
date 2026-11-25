package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The lease of one piece of agent work on one subject. Only {@link AgentRuns} writes it, through conditional updates
 * on the database clock; this entity only inserts a run and reads one. The {@code concurrency_key} names the subject
 * ({@code review:<id>}, {@code conversation:<id>}): at most one run of a subject is {@link AgentRunState#LEASED} at a
 * time, and the runs of a subject are claimed in the order they were created.
 *
 * <p>{@code lease_epoch} counts the claims and fences every write of the holder. {@code attempt} counts the claims that
 * spent the kind's attempt budget: a lost lease, or a requeue that the holder charged.
 */
@Entity
@Table(
        name = "agent_run",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_agent_run_workspace",
                        columnNames = {"id", "workspace_id"}),
        indexes = {
            @Index(name = "idx_agent_run_queued", columnList = "available_at"),
            @Index(name = "idx_agent_run_leased_until", columnList = "leased_until"),
            @Index(name = "idx_agent_run_subject", columnList = "workspace_id, concurrency_key, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AgentRun {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", updatable = false)
    private @Nullable Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 24)
    private AgentRunKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, updatable = false, length = 16)
    private AgentRunBand band;

    @Column(name = "concurrency_key", nullable = false, updatable = false, length = 96)
    private String concurrencyKey;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'QUEUED'")
    @Column(name = "state", nullable = false, length = 8)
    private AgentRunState state;

    @ColumnDefault("0")
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @ColumnDefault("0")
    @Column(name = "lease_epoch", nullable = false)
    private int leaseEpoch;

    @Column(name = "leased_until")
    private @Nullable Instant leasedUntil;

    @ColumnDefault("now()")
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    /** Why the queued run waits beyond a retry's backoff; none on a leased or ended run. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_reason", length = 16)
    private @Nullable AgentRunHold holdReason;

    /**
     * Until when no claim takes the queued run, because its last claim could not start it ({@link AgentRuns#defer}). It
     * stays due since {@code available_at}, so it keeps its place in the claim order and its wait counts.
     */
    @Column(name = "deferred_until")
    private @Nullable Instant deferredUntil;

    /**
     * How long the run may wait to start once it is due ({@code available_at}) and nothing of its subject is ahead of
     * it; past that it ends as {@link AgentRunStopReason#NOT_STARTED}. A requeue makes it due again, so a retry gets
     * the same wait.
     */
    @Column(name = "start_within_seconds", updatable = false)
    private @Nullable Integer startWithinSeconds;

    /**
     * Bounds the current claim: a leased run is not renewed past it, and the reaper ends it there as {@link
     * AgentRunStopReason#DEADLINE}. Within a claim it only moves earlier ({@link AgentRuns#endBy}); a review or turn requeue clears it,
     * so the next claim sets its own. A composition keeps its creation deadline across free requeues and retries on a new run.
     */
    @Column(name = "deadline", updatable = false)
    private @Nullable Instant deadline;

    /**
     * The authorization generations that the work was requested under (ADR 0134 decision 8). Every continuation of the
     * work keeps it, and a boundary refuses the run once a generation moved on.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @ColumnDefault("jsonb_build_object('workspace', 0)")
    @Column(name = "authorization_stamp", nullable = false, updatable = false, columnDefinition = "jsonb")
    private AuthorizationStamp authorizationStamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 24)
    private @Nullable AgentRunStopReason stopReason;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    AgentRun(
            UUID id,
            long workspaceId,
            AgentRunKind kind,
            AgentRunBand band,
            String concurrencyKey,
            AuthorizationStamp authorizationStamp,
            int leaseEpoch,
            int attempt,
            Instant now,
            @Nullable Duration startWithin,
            Duration delay,
            @Nullable Duration executionWindow) {
        if (workspaceId < 0 || (workspaceId == AgentRunRef.INSTANCE_SCOPE) != (kind == AgentRunKind.DRAFT_REPORT))
            throw new IllegalArgumentException("Only report drafts run at instance scope");
        if (concurrencyKey.isBlank() || concurrencyKey.length() > 96)
            throw new IllegalArgumentException("A run names its subject in at most 96 characters");
        this.id = id;
        this.workspaceId = workspaceId == AgentRunRef.INSTANCE_SCOPE ? null : workspaceId;
        this.kind = kind;
        this.band = band;
        this.concurrencyKey = concurrencyKey;
        this.authorizationStamp = authorizationStamp;
        this.state = AgentRunState.QUEUED;
        this.leaseEpoch = leaseEpoch;
        this.attempt = attempt;
        this.availableAt = now.plus(delay);
        this.deadline = executionWindow == null ? null : this.availableAt.plus(executionWindow);
        this.createdAt = now;
        this.startWithinSeconds = startWithin == null ? null : Math.toIntExact(startWithin.toSeconds());
    }

    public long getWorkspaceId() {
        return workspaceId == null ? AgentRunRef.INSTANCE_SCOPE : workspaceId;
    }

    public AgentRunRef ref() {
        return new AgentRunRef(id, getWorkspaceId());
    }
}
