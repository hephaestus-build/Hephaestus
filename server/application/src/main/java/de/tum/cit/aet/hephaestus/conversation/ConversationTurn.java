package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
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
import java.time.Instant;
import java.util.Set;
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
 * One question to Hephaestus and the work to answer it. {@link ConversationTurnService} owns execution and
 * {@link ConversationTurnWaits} owns the queue wait. The turn's run ({@code run_id}) leases its work: who may drive it, under
 * which claim, and until when ({@link de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns}). {@code attempt} is the claim
 * of the run that the turn's current or last attempt ran under.
 *
 * <p>The token columns hold the current attempt's usage, which the LLM proxy adds call by call. {@code admission}
 * freezes the model and rates, so a lost attempt can still be billed. Every state change locks the row first, so it
 * reads the proxy's latest totals and the proxy cannot add to an attempt that has ended.
 */
@Entity
@Table(
        name = "conversation_turn",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_conversation_turn_request", columnNames = "request_id"),
            @UniqueConstraint(
                    name = "uk_conversation_turn_tenant",
                    columnNames = {"id", "workspace_id"}),
            // A run belongs to one workspace, so this keeps one turn per run; it also indexes the run's foreign key.
            @UniqueConstraint(
                    name = "uk_conversation_turn_run",
                    columnNames = {"run_id", "workspace_id"}),
        },
        indexes = {
            @Index(name = "idx_conversation_turn_conversation_status", columnList = "conversation_id, status"),
            @Index(name = "idx_conversation_turn_status_created", columnList = "status, created_at"),
            @Index(name = "idx_conversation_turn_status_queued", columnList = "status, queued_at"),
            @Index(name = "idx_conversation_turn_workspace_surface", columnList = "workspace_id, surface"),
        })
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationTurn implements Persistable<UUID> {

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

    /** The attempts a turn gets: its first, and one retry after its holder is lost. */
    public static final int MAX_ATTEMPTS = 2;

    /** The cause of a queued turn that waited past its start window. */
    static final String START_WAIT_EXPIRED_CAUSE = "start_wait_expired";

    /** The cause of a turn whose holder was lost on its last attempt. */
    static final String LEASE_EXPIRED_CAUSE = "its lease expired on its last attempt";

    /** The cause of a turn whose context was erased while it ran. */
    static final String CONTEXT_CHANGED_CAUSE = "context_changed";

    /**
     * The cause of a turn that stopped because the developer deleted its question while it had not ended. A turn that the
     * developer stopped records no cause, so an operator can tell the two apart.
     */
    static final String QUESTION_REMOVED_CAUSE = "question_removed";

    /** How the cause of a failure that a turn's sandbox reported starts; its reason and detail follow. */
    public static final String SANDBOX_REPORTED_CAUSE = "its conversation sandbox reported ";

    /** Where a running turn's current attempt stands. */
    public enum Phase {
        /** The attempt prepares: the LLM proxy has admitted none of its model calls yet. */
        STARTING,
        /** The LLM proxy admitted a model call of the attempt. */
        WORKING,
    }

    /** Why a turn failed, as a client may act on it. {@link #of} maps the operator's free-text cause. */
    public enum FailureReason {
        /** The turn waited past its start window for a free Hephaestus. */
        START_WAIT_EXPIRED,
        /** The turn's holder was lost on its last attempt. */
        ATTEMPTS_EXHAUSTED,
        /** The model or its provider stopped answering. */
        MODEL_UNAVAILABLE,
        /** Context the turn could read was erased while it ran. */
        CONTEXT_ERASED,
        /** Any other failure, or one whose cause was not recorded. */
        UNKNOWN;

        /** The sandbox's failure details that name the model or its provider. */
        private static final Set<String> MODEL_DETAILS =
                Set.of("provider_unavailable", "upstream_stalled", "upstream_paused", "model_error");

        /** The reason of a failed turn's {@code failureCause}; {@code null} when the turn recorded none. */
        public static FailureReason of(@Nullable String cause) {
            if (cause == null) return UNKNOWN;
            if (cause.equals(START_WAIT_EXPIRED_CAUSE)) return START_WAIT_EXPIRED;
            if (cause.equals(LEASE_EXPIRED_CAUSE)) return ATTEMPTS_EXHAUSTED;
            if (cause.equals(CONTEXT_CHANGED_CAUSE)) return CONTEXT_ERASED;
            if (!cause.startsWith(SANDBOX_REPORTED_CAUSE)) return UNKNOWN;
            // The sandbox reports STALLED when the model stopped streaming, or ERROR with the kind of failure.
            String report = cause.substring(SANDBOX_REPORTED_CAUSE.length());
            int detail = report.indexOf(": ");
            boolean model = report.startsWith("STALLED")
                    || (detail >= 0 && MODEL_DETAILS.contains(report.substring(detail + 2)));
            return model ? MODEL_UNAVAILABLE : UNKNOWN;
        }
    }

    /** Why a queued turn has not started ({@link ConversationTurnWaits}). */
    public enum WaitingFor {
        /** A server may start it, and every Hephaestus slot of the deployment is taken. */
        CAPACITY,
    }

    /** Where the developer asked. */
    public enum Surface {
        APP,
        SLACK,
        /** Proactive work starts from a real system entry, never a developer's fabricated question. */
        SYSTEM;

        ConversationEntry.Surface entrySurface() {
            return switch (this) {
                case APP -> ConversationEntry.Surface.APP;
                case SLACK -> ConversationEntry.Surface.SLACK;
                case SYSTEM -> ConversationEntry.Surface.SYSTEM;
            };
        }
    }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** The conversation's account. The database keeps it equal to the conversation's, also when that one moves. */
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

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

    /** The run that leases the turn's work. A database trigger keeps the turn's status and its run's state together. */
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "admission", columnDefinition = "jsonb")
    private JsonNode admission;

    @Nullable
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Why the attempt failed, for the operator: the host's reason and kind of failure, or why the server ended the
     * attempt itself. It names closed kinds only, never a host's, a provider's or an exception's text. {@code null}
     * unless the turn is {@code FAILED}.
     */
    @Nullable
    @Column(name = "failure_cause", length = 200)
    private String failureCause;

    /** The kind of the failed attempt's last model call that failed, as the LLM proxy names it. */
    @Nullable
    @Column(name = "failed_call", length = 64)
    private String failedCall;

    /** The status the provider answered that call with. */
    @Nullable
    @Column(name = "failed_call_status")
    private Integer failedCallStatus;

    /** The app page the developer asked from, as the app described it; Hephaestus reads it in the turn's situation. */
    @Nullable
    @Column(name = "page_context", length = 500)
    private String pageContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When this question entered its current queue wait, retained across server restarts. */
    @Column(name = "queued_at", nullable = false)
    @ColumnDefault("now()")
    private Instant queuedAt;

    @Nullable
    @Column(name = "started_at")
    private Instant startedAt;

    @Nullable
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** The items that the current attempt loaded into its context folder, on any server. */
    @Column(name = "tools_loads", nullable = false)
    @ColumnDefault("0")
    private int toolsLoads;

    @Transient
    private boolean fresh;

    ConversationTurn(
            Conversation conversation,
            String requestId,
            Surface surface,
            @Nullable String pageContext,
            UUID runId,
            Instant now) {
        this.id = TimeOrderedIds.next();
        this.runId = runId;
        this.workspaceId = conversation.getWorkspaceId();
        this.conversationId = conversation.getId();
        this.accountId = conversation.getAccountId();
        this.requestId = requestId;
        this.surface = surface;
        this.pageContext = pageContext;
        this.status = Status.QUEUED;
        this.createdAt = now;
        this.queuedAt = now;
        this.fresh = true;
    }

    /** Starts the attempt that runs under the run's claim {@code epoch}. */
    void start(Instant now, int epoch) {
        this.status = Status.RUNNING;
        this.attempt = epoch;
        this.startedAt = now;
        this.toolsLoads = 0;
        this.admission = null;
        this.error = null;
        this.failureCause = null;
        this.failedCall = null;
        this.failedCallStatus = null;
    }

    void admit(JsonNode admittedWith) {
        this.admission = admittedWith;
    }

    void stop(Instant now) {
        this.status = Status.STOPPED;
        this.finishedAt = now;
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
     * Stops a turn that has not ended, and records why for the operator. Clients read a cause only on a {@code FAILED}
     * turn, so a stopped turn shows as stopped.
     *
     * @return whether the turn was active and is now {@code STOPPED}
     */
    boolean stopIfActive(Instant now, String cause) {
        if (!stopIfActive(now)) {
            return false;
        }
        this.failureCause = cause;
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
    }

    /** Records why a {@code FAILED} turn failed, for the operator; a turn in any other state records nothing. */
    void recordFailure(@Nullable String cause, @Nullable String callKind, @Nullable Integer callStatus) {
        if (status != Status.FAILED) {
            return;
        }
        // Cut to the columns: a cause that does not fit must not roll back the end it belongs to.
        this.failureCause = cause == null ? null : cause.substring(0, Math.min(cause.length(), 200));
        this.failedCall = callKind == null ? null : callKind.substring(0, Math.min(callKind.length(), 64));
        this.failedCallStatus = callKind == null ? null : callStatus;
    }

    /** Returns the turn to the queue for one more attempt after its lease expired. */
    void requeue(Instant now) {
        this.status = Status.QUEUED;
        this.queuedAt = now;
        this.startedAt = null;
        this.admission = null;
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
