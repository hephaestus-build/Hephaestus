package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Hephaestus's own resolution of one provider thread on a work item: the state it asks for, and how far it got. One row
 * per thread, whichever revision of the feedback owns the note now. Each new ask raises the generation, so an older
 * action finds itself superseded.
 */
@Entity
@Table(
        name = "feedback_thread_resolution",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_feedback_thread_resolution_thread",
                    columnNames = {"workspace_id", "item_id", "thread_ref"})
        })
@Getter
@NoArgsConstructor
public class FeedbackThreadResolution {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    /** The provider thread: a GitHub review thread node or a GitLab discussion. */
    @Column(name = "thread_ref", nullable = false, columnDefinition = "TEXT")
    private String threadRef;

    /** The copy whose note starts the thread, in the newest revision that owns it; its cited problems decide. */
    @Column(name = "copy_id", nullable = false)
    private UUID copyId;

    /** Whether Hephaestus asks for the thread resolved or open. */
    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private FeedbackThreadResolutionState state;

    @Column(name = "generation", nullable = false)
    private int generation;

    /**
     * The provider account that writes the change, recorded before the write starts, so the thread event that the write
     * causes can be matched to it. Set means a write of this generation may have reached the provider.
     */
    @Column(name = "actor", columnDefinition = "TEXT")
    private @Nullable String actor;

    /** The provider of the thread, recorded with the first writing account; the thread never changes provider. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 16)
    private @Nullable IntegrationKind provider;

    /**
     * Whether the provider lets Hephaestus reopen the thread, recorded with the first writing account. GitLab does not:
     * it cannot tell Hephaestus's own reopen from a person's.
     */
    @Column(name = "reopens")
    private @Nullable Boolean reopens;

    /**
     * Whether a resolve of Hephaestus may be in effect on the provider: a write of one may have started, and no reopen
     * of it has been confirmed since. Kept across asks, so a later ask never forgets a resolve that reached the
     * provider.
     */
    @Column(name = "resolve_written", nullable = false)
    private boolean resolveWritten;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 32)
    private @Nullable FeedbackThreadStopReason stopReason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /**
     * Asks for the thread resolved or open, with the cited problems of {@code copy}. True when an action must carry the
     * ask out. A reopen when no resolve of Hephaestus can be in effect on the provider only cancels that resolve. The
     * same state asked again while its action waits moves the ask to the newest owning copy, which the waiting action
     * reads before it writes.
     */
    boolean ask(boolean resolve, UUID copy, Instant at) {
        if (state == FeedbackThreadResolutionState.STOPPED
                && Objects.requireNonNull(stopReason).isFinal()) return false;
        if (resolve == resolved && state != FeedbackThreadResolutionState.STOPPED) {
            if (state == FeedbackThreadResolutionState.PENDING) copyId = copy;
            return false;
        }
        boolean undo = !resolve && resolved && !resolveWritten;
        boolean flips = resolve != resolved;
        copyId = copy;
        resolved = resolve;
        generation++;
        stopReason = null;
        changedAt = at;
        // The account of a write of the other state answers no ask of this one.
        if (flips) actor = null;
        if (undo) {
            moveTo(FeedbackThreadResolutionState.DONE);
            return false;
        }
        moveTo(FeedbackThreadResolutionState.PENDING);
        return true;
    }

    /**
     * Withdraws a waiting resolve, begun or not, where Hephaestus may not reopen: the problem came back, so the resolve
     * must not land. A write of it may already be in effect, so {@code resolveWritten} stays. An attempt already inside
     * its provider call cannot be stopped; that window is one call. True when the waiting action must go.
     */
    boolean withdrawPending(Instant at) {
        if (state != FeedbackThreadResolutionState.PENDING || !resolved) return false;
        generation++;
        stopReason = FeedbackThreadStopReason.WITHDRAWN;
        changedAt = at;
        moveTo(FeedbackThreadResolutionState.STOPPED);
        return true;
    }

    /**
     * Withdraws a waiting resolve that has not begun to write, when its proof no longer holds. A resolve whose write may
     * have begun in an earlier ask keeps its action: the thread is then already resolved, and the action reads that
     * back without writing. A resolve that may be in
     * effect stays: unknown standing never reopens a thread. True when the waiting action must go.
     */
    boolean withdrawUnstarted(Instant at) {
        if (state != FeedbackThreadResolutionState.PENDING || !resolved || actor != null || resolveWritten)
            return false;
        resolved = false;
        generation++;
        changedAt = at;
        moveTo(FeedbackThreadResolutionState.DONE);
        return true;
    }

    /** Records the writing account just before the write of {@code asked}; false when that ask is no longer current. */
    boolean begin(int asked, FeedbackThreadSteps.Writer writer, Instant at) {
        if (!current(asked)) return false;
        provider = writer.provider();
        reopens = writer.reopens();
        actor = writer.actor();
        if (resolved) resolveWritten = true;
        changedAt = at;
        return true;
    }

    void done(int asked, Instant at) {
        if (!current(asked)) return;
        // A confirmed reopen leaves no resolve of Hephaestus in effect.
        if (!resolved) resolveWritten = false;
        changedAt = at;
        moveTo(FeedbackThreadResolutionState.DONE);
    }

    void stop(int asked, FeedbackThreadStopReason reason, Instant at) {
        if (!current(asked)) return;
        stopReason = reason;
        changedAt = at;
        moveTo(FeedbackThreadResolutionState.STOPPED);
    }

    /** A person's change: Hephaestus never changes the thread again. */
    void handOver(Instant at) {
        stopReason = FeedbackThreadStopReason.HUMAN_CONTROLLED;
        changedAt = at;
        moveTo(FeedbackThreadResolutionState.STOPPED);
    }

    /**
     * Whether a thread event is Hephaestus's own: its actor is the account that writes this row's change, and this row
     * asks for the state the event reports. Any other event, or one without an actor, is a person's.
     */
    boolean owns(boolean resolvedEvent, @Nullable String eventActor) {
        return eventActor != null
                && eventActor.equals(actor)
                && resolvedEvent == resolved
                && state != FeedbackThreadResolutionState.STOPPED;
    }

    /**
     * Whether an event that {@link #owns} refused looks like the event of this row's own write: it reports the asked
     * state while a write of that ask may have reached the provider, but names another account or none.
     */
    boolean resembles(boolean resolvedEvent) {
        return actor != null && resolvedEvent == resolved && state != FeedbackThreadResolutionState.STOPPED;
    }

    boolean current(int asked) {
        return asked == generation && state == FeedbackThreadResolutionState.PENDING;
    }

    private void moveTo(FeedbackThreadResolutionState next) {
        if (!state.canTransitionTo(next)) throw new IllegalStateException(state + " cannot become " + next);
        state = next;
    }
}
