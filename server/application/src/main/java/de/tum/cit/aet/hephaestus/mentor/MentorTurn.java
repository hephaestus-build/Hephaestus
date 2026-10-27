package de.tum.cit.aet.hephaestus.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.JsonNode;

/**
 * One question to Heph and the work to answer it; {@link MentorTurnService} owns the lifecycle. {@code attempt} counts
 * claims.
 *
 * <p>The token columns hold the current attempt's usage, which the LLM proxy adds call by call. {@code admission}
 * freezes the model and rates, so a lost attempt can still be billed. Every state change locks the row first, so it
 * reads the proxy's latest totals and the proxy cannot add to an attempt that has ended.
 */
@Entity
@Table(
        name = "mentor_turn",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_mentor_turn_request", columnNames = "request_id"),
            @UniqueConstraint(
                    name = "uk_mentor_turn_tenant",
                    columnNames = {"id", "workspace_id"}),
        },
        indexes = {
            @Index(name = "idx_mentor_turn_conversation_status", columnList = "conversation_id, status"),
            @Index(name = "idx_mentor_turn_status_created", columnList = "status, created_at"),
            @Index(name = "idx_mentor_turn_workspace_surface", columnList = "workspace_id, surface"),
        })
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorTurn implements Persistable<UUID> {

    public enum Status {
        QUEUED,
        RUNNING,
        DONE,
        FAILED,
        STOPPED;

        public boolean isTerminal() {
            return this == DONE || this == FAILED || this == STOPPED;
        }
    }

    /**
     * A model call of an attempt that failed, as the LLM proxy saw it. It holds no text of the provider, the call or
     * an exception.
     *
     * @param kind the exception's simple class name, the stream's outcome, or a closed kind of the proxy, such as
     *     {@code upstream_stalled} or {@code upstream_status}
     * @param status the HTTP status the provider answered; {@code null} when it answered none
     */
    public record FailedCall(String kind, @Nullable Integer status) {}

    /** Where the developer asked. */
    public enum Surface {
        APP,
        SLACK;

        MentorEntry.Surface entrySurface() {
            return this == SLACK ? MentorEntry.Surface.SLACK : MentorEntry.Surface.APP;
        }
    }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "request_id", nullable = false, length = 200)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "surface", nullable = false, length = 8)
    private Surface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "attempt", nullable = false)
    @ColumnDefault("0")
    private int attempt;

    @Nullable
    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Nullable
    @Column(name = "leased_until")
    private Instant leasedUntil;

    /** The whole in-progress answer as the host last reported it; cleared when the attempt ends. */
    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partial", columnDefinition = "jsonb")
    private JsonNode partial;

    /** Prompt tokens that were neither cache reads nor cache writes; those have their own columns. */
    @Column(name = "input_tokens", nullable = false)
    @ColumnDefault("0")
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    @ColumnDefault("0")
    private long outputTokens;

    @Column(name = "cached_tokens", nullable = false)
    @ColumnDefault("0")
    private long cachedTokens;

    @Column(name = "cache_write_tokens", nullable = false)
    @ColumnDefault("0")
    private long cacheWriteTokens;

    /** Already inside {@link #outputTokens}; reported, never priced twice. */
    @Column(name = "reasoning_tokens", nullable = false)
    @ColumnDefault("0")
    private long reasoningTokens;

    @Column(name = "llm_calls", nullable = false)
    @ColumnDefault("0")
    private int llmCalls;

    /** {@code null} while running and when the admitted model has no price. */
    @Nullable
    @Column(name = "cost_usd", precision = 19, scale = 9)
    private BigDecimal costUsd;

    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "admission", columnDefinition = "jsonb")
    private JsonNode admission;

    @Nullable
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Why the attempt failed, for the operator: the host's reason and kind of failure, or why the worker ended the
     * attempt itself. It names closed kinds only, never a host's, a provider's or an exception's text. {@code null}
     * unless the turn is {@code FAILED}.
     */
    @Nullable
    @Column(name = "failure_cause", length = 200)
    private String failureCause;

    /** The kind of the failed attempt's last model call that failed: {@link FailedCall#kind}. */
    @Nullable
    @Column(name = "failed_call", length = 64)
    private String failedCall;

    /** The status the provider answered that call with: {@link FailedCall#status}. */
    @Nullable
    @Column(name = "failed_call_status")
    private Integer failedCallStatus;

    /** The app page the developer asked from, as the app described it; Heph reads it in the turn's situation. */
    @Nullable
    @Column(name = "page_context", length = 500)
    private String pageContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Nullable
    @Column(name = "started_at")
    private Instant startedAt;

    @Nullable
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Transient
    private boolean fresh;

    MentorTurn(
            MentorConversation conversation,
            String requestId,
            Surface surface,
            @Nullable String pageContext,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspaceId = conversation.getWorkspaceId();
        this.conversationId = conversation.getId();
        this.requestId = requestId;
        this.surface = surface;
        this.pageContext = pageContext;
        this.status = Status.QUEUED;
        this.createdAt = now;
        this.fresh = true;
    }

    /** A host still holds this turn: it is running, or stopped while its host commits the aborted answer. */
    public boolean isLeased() {
        return leasedUntil != null && (status == Status.RUNNING || status == Status.STOPPED);
    }

    void start(String worker, Instant now, Instant leaseEnd) {
        this.status = Status.RUNNING;
        this.attempt++;
        this.workerId = worker;
        this.leasedUntil = leaseEnd;
        this.startedAt = now;
        this.partial = null;
        this.admission = null;
        this.error = null;
        this.failureCause = null;
        this.failedCall = null;
        this.failedCallStatus = null;
        resetUsage();
    }

    void renew(Instant leaseEnd) {
        this.leasedUntil = leaseEnd;
    }

    void admit(JsonNode admittedWith) {
        this.admission = admittedWith;
    }

    void progress(JsonNode snapshot) {
        this.partial = snapshot;
    }

    void stop(Instant now) {
        this.status = Status.STOPPED;
        this.finishedAt = now;
        this.partial = null;
    }

    /**
     * Stops a turn that has not ended.
     *
     * @return whether the turn was active and is now {@code STOPPED}
     */
    boolean stopIfActive(Instant now) {
        if (status.isTerminal()) {
            return false;
        }
        stop(now);
        return true;
    }

    /**
     * Ends the attempt. A turn the developer stopped stays {@code STOPPED} whatever its host reports, and only a
     * {@code FAILED} turn keeps the reason.
     */
    void end(Status outcome, @Nullable String reason, Instant now) {
        if (!outcome.isTerminal()) {
            throw new IllegalArgumentException("A turn ends DONE, FAILED or STOPPED, not " + outcome);
        }
        if (status != Status.STOPPED) {
            this.status = outcome;
            this.finishedAt = now;
        }
        this.error = status == Status.FAILED ? reason : null;
        this.leasedUntil = null;
        this.partial = null;
    }

    /** Records why a {@code FAILED} turn failed, for the operator; a turn in any other state records nothing. */
    void recordFailure(@Nullable String cause, @Nullable FailedCall call) {
        if (status != Status.FAILED) {
            return;
        }
        // Cut to the columns: a cause that does not fit must not roll back the end it belongs to.
        this.failureCause = cause == null ? null : cause.substring(0, Math.min(cause.length(), 200));
        this.failedCall = call == null
                ? null
                : call.kind().substring(0, Math.min(call.kind().length(), 64));
        this.failedCallStatus = call == null ? null : call.status();
    }

    /** Returns the turn to the queue for one more attempt after its lease expired. */
    void requeue() {
        this.status = Status.QUEUED;
        this.workerId = null;
        this.leasedUntil = null;
        this.startedAt = null;
        this.partial = null;
        this.admission = null;
        resetUsage();
    }

    void recordCost(@Nullable BigDecimal cost) {
        this.costUsd = cost;
    }

    private void resetUsage() {
        this.inputTokens = 0;
        this.outputTokens = 0;
        this.cachedTokens = 0;
        this.cacheWriteTokens = 0;
        this.reasoningTokens = 0;
        this.llmCalls = 0;
        this.costUsd = null;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
