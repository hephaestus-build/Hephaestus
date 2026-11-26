package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One input of a thread that no person typed (ADR 0120 decision 2): an inbox event, such as a due weekly digest. It
 * holds IDs and values only, never text. Code decides each item before any model runs, and records why: the start
 * policy starts a background turn for it while the thread is idle, defers it while the person is active, and settles it
 * with its reason. A person's message never waits for it: the message's admission cancels the item's turn, and the item
 * waits for the next idle moment.
 */
@Entity
@Table(
        name = "inbox_item",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_inbox_item_source",
                    columnNames = {"conversation_id", "source_key"}),
            @UniqueConstraint(
                    name = "uk_inbox_item_tenant",
                    columnNames = {"id", "workspace_id"})
        },
        indexes = @Index(name = "ix_inbox_item_due", columnList = "due_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxItem {

    /** What the item is. */
    public enum Kind {
        /** A schedule of the person's that came due, such as the weekly digest. */
        SCHEDULE_DUE
    }

    /** Who answers for the item until it ends (ADR 0120 decision 12). */
    public enum Custody {
        /** It may end without a message, by its deadline at the latest. */
        OPTIONAL,
        /** It ends only with its disposition. */
        REQUIRED
    }

    /**
     * Where the item is. Only {@code PENDING} and {@code CLAIMED} are open. A claimed item settles by its turn's end, or
     * waits again; only a pending one expires, so its deadline and attempts are read once its turn has ended.
     */
    public enum State implements Lifecycle<State> {
        PENDING,
        CLAIMED,
        CONSUMED,
        EXPIRED,
        DROPPED;

        public boolean open() {
            return this == PENDING || this == CLAIMED;
        }

        @Override
        public boolean canTransitionTo(State next) {
            return switch (this) {
                case PENDING -> next != PENDING;
                case CLAIMED -> next == PENDING || next == CONSUMED || next == DROPPED;
                case CONSUMED, EXPIRED, DROPPED -> false;
            };
        }
    }

    /** Why the item is where it is: the reason of the last decision about it, recorded by code. */
    public enum Reason {
        /** Waiting for its due time. */
        WAITING,
        /** The person is active in the thread, so a background turn would make them wait. */
        DEFERRED_ACTIVE,
        /** A turn started for it. */
        STARTED,
        /** A message of the person stopped its turn; it waits for the next idle moment. */
        CANCELLED_BY_MESSAGE,
        /** Its turn could not start, and it may start again. */
        NOT_STARTED,
        /** Its turn wrote the message. */
        DELIVERED,
        /** At its start nothing was left to say. */
        NOTHING_TO_SAY,
        /** Its message was already written under the same request, as by an overlapping run. */
        ALREADY_WRITTEN,
        /** It stopped being eligible, as when the person turned the schedule off. */
        NO_LONGER_ELIGIBLE,
        /** Its deadline passed before a turn delivered it. */
        DEADLINE_PASSED,
        /** It started as often as it may. */
        ATTEMPTS_SPENT
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    /** The dedupe key: the same source never writes a second item on the thread. */
    @Column(name = "source_key", nullable = false, length = 255)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "custody", nullable = false, length = 16)
    private Custody custody;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private Reason reason;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    /** How many turns started for the item: each one takes a request id of its own. */
    @Column(name = "starts", nullable = false)
    private int starts;

    /** How many of its turns ran and ended without delivering it; a turn that a question stopped before it ran is not one. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** The turn that holds the item while it is claimed. */
    @Column(name = "turn_id")
    private @Nullable UUID turnId;

    /** IDs and values that the item's producer needs at its start, never text. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private @Nullable Instant settledAt;

    InboxItem(UUID id, InboxItems.NewItem item, Instant now) {
        this.id = id;
        this.workspaceId = item.workspaceId();
        this.conversationId = item.conversationId();
        this.kind = item.kind();
        this.sourceKey = item.sourceKey();
        this.custody = item.custody();
        this.state = State.PENDING;
        this.reason = Reason.WAITING;
        this.dueAt = item.dueAt();
        this.deadlineAt = item.deadlineAt();
        this.payload = item.payload();
        this.createdAt = now;
    }

    /** A turn started for the item. */
    void claim(UUID turn) {
        moveTo(State.CLAIMED);
        reason = Reason.STARTED;
        turnId = turn;
        starts++;
    }

    /**
     * The item waits again, for the next idle moment.
     *
     * @param charged whether its turn ran, so that the attempt counts against the item's limit
     */
    void release(Reason why, boolean charged) {
        moveTo(State.PENDING);
        reason = why;
        turnId = null;
        if (charged) attempts++;
    }

    /** The item stays pending, for this reason. */
    void defer(Reason why) {
        reason = why;
    }

    /** The item ends, for this reason. */
    void finish(State end, Reason why, Instant now) {
        if (end.open()) throw new IllegalArgumentException("An item settles in an end state, not " + end);
        moveTo(end);
        reason = why;
        settledAt = now;
    }

    private void moveTo(State next) {
        if (!state.canTransitionTo(next)) throw new IllegalStateException("An inbox item cannot go from " + state + " to " + next);
        state = next;
    }
}
