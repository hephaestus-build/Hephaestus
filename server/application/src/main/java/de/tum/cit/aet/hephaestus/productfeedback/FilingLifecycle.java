package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one definition of how a report moves. Every state change names an {@link Event}, and an event this table does not
 * allow from the current state is refused and changes nothing. Whether a mail went out is no state, because nothing here
 * can prove it. Erasure is no event: it deletes the row from any state, and every later result finds nothing to change.
 */
final class FilingLifecycle {
    private FilingLifecycle() {}

    enum Event {
        /** A worker takes the report, or takes over one whose lease ended. */
        DRAFT_CLAIMED,
        /** The worker handed back an answer, or that none came. */
        DRAFT_ENDED,
        /** A draft call failed and has attempts left, so the report waits for a worker again. */
        DRAFT_RETRIED,
        /** Routing decided: the report waits out its hold. */
        ROUTED_TO_HOLD,
        /** Routing decided: filing takes the report next. */
        ROUTED_TO_FILE,
        /** The reporter edited a held report, so it drafts again. */
        EDITED,
        /** The reporter kept a held report private. */
        KEPT_PRIVATE,
        /** A check at dispatch sent a public report private. */
        REROUTED_PRIVATE,
        /** Filing handed the report to the outbox, which writes it to GitHub. */
        FILING_CLAIMED,
        /** No earlier write reached GitHub, so the report takes the ordinary path again. */
        FILING_REQUEUED,
        /** The issue exists. */
        FILED,
        /** No destination takes it, or filing is paused: it stays saved for the admins. */
        SAVED,
        /** Filing failed for good; it waits in Needs action. */
        STOPPED,
        /** An admin retried, and an earlier write may have reached GitHub. */
        RETRIED_RECONCILE,
        /** An admin retried a report that has no draft. */
        RETRIED_DRAFT,
        /** An admin retried a report that has a draft. */
        RETRIED_ROUTE,
        /** An admin moved a saved report, and a model drafts it. */
        MOVED_DRAFTED,
        /** An admin moved a saved report whose author was not told about a model, so none reads it. */
        MOVED_UNDRAFTED,
        /** The reporter withdrew a public issue, or a maintainer promoted a private one: the same issue state, a new route. */
        ROUTE_CHANGED_ON_GITHUB
    }

    /**
     * A transition this table does not allow: a defect in the caller. The row keeps its state, and the sweepers report it
     * through {@code ProductFeedbackMeters.failed}, because their broad catches would otherwise read it as an outage.
     */
    static final class IllegalTransition extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final UUID feedbackId;
        private final FilingState from;
        private final Event event;

        IllegalTransition(UUID feedbackId, FilingState from, Event event) {
            super("Report " + feedbackId + " cannot take " + event + " from " + from);
            this.feedbackId = feedbackId;
            this.from = from;
            this.event = event;
        }

        UUID feedbackId() {
            return feedbackId;
        }

        FilingState from() {
            return from;
        }

        Event event() {
            return event;
        }
    }

    private static final Map<FilingState, Map<Event, FilingState>> TABLE = new EnumMap<>(FilingState.class);

    private static void allow(FilingState from, Event event, FilingState to) {
        TABLE.computeIfAbsent(from, ignored -> new EnumMap<>(Event.class)).put(event, to);
    }

    static {
        allow(FilingState.DRAFT_QUEUED, Event.DRAFT_CLAIMED, FilingState.DRAFTING);
        allow(FilingState.DRAFTING, Event.DRAFT_CLAIMED, FilingState.DRAFTING);
        allow(FilingState.DRAFT_QUEUED, Event.DRAFT_ENDED, FilingState.ANSWERED);
        allow(FilingState.DRAFTING, Event.DRAFT_ENDED, FilingState.ANSWERED);
        allow(FilingState.DRAFTING, Event.DRAFT_RETRIED, FilingState.DRAFT_QUEUED);
        // An expired report routes straight from a draft state, so a stopped worker role cannot hold it back.
        for (FilingState from : EnumSet.of(FilingState.ANSWERED, FilingState.DRAFT_QUEUED, FilingState.DRAFTING)) {
            allow(from, Event.ROUTED_TO_HOLD, FilingState.HELD);
            allow(from, Event.ROUTED_TO_FILE, FilingState.QUEUED);
        }
        allow(FilingState.HELD, Event.EDITED, FilingState.DRAFT_QUEUED);
        allow(FilingState.HELD, Event.KEPT_PRIVATE, FilingState.QUEUED);
        allow(FilingState.FILING, Event.FILING_REQUEUED, FilingState.QUEUED);
        allow(FilingState.FILING, Event.FILED, FilingState.FILED);
        // A FILING row may have written, so it only reconciles. It never reroutes or saves: that would skip an issue.
        for (FilingState from : EnumSet.of(FilingState.HELD, FilingState.QUEUED)) {
            allow(from, Event.REROUTED_PRIVATE, FilingState.QUEUED);
            allow(from, Event.SAVED, FilingState.SAVED);
        }
        for (FilingState from : EnumSet.of(FilingState.HELD, FilingState.QUEUED)) {
            allow(from, Event.FILING_CLAIMED, FilingState.FILING);
        }
        for (FilingState from : EnumSet.of(FilingState.HELD, FilingState.QUEUED, FilingState.FILING)) {
            allow(from, Event.STOPPED, FilingState.STOPPED);
        }
        allow(FilingState.STOPPED, Event.RETRIED_RECONCILE, FilingState.FILING);
        allow(FilingState.STOPPED, Event.RETRIED_DRAFT, FilingState.DRAFT_QUEUED);
        allow(FilingState.STOPPED, Event.RETRIED_ROUTE, FilingState.ANSWERED);
        allow(FilingState.SAVED, Event.MOVED_DRAFTED, FilingState.DRAFT_QUEUED);
        allow(FilingState.SAVED, Event.MOVED_UNDRAFTED, FilingState.ANSWERED);
        allow(FilingState.FILED, Event.ROUTE_CHANGED_ON_GITHUB, FilingState.FILED);
    }

    static Optional<FilingState> next(FilingState from, Event event) {
        return Optional.ofNullable(TABLE.getOrDefault(from, Map.of()).get(event));
    }

    static Set<Event> eventsFrom(FilingState from) {
        return Set.copyOf(TABLE.getOrDefault(from, Map.of()).keySet());
    }

    /** What a reporter sees. A new state fails to compile here until it has a meaning, and no caller maps one itself. */
    static FilingDTOs.ReportState reporterState(FilingState state, @Nullable FilingRoute route) {
        return switch (state) {
            case DRAFT_QUEUED, DRAFTING, ANSWERED -> FilingDTOs.ReportState.DRAFTING;
            case HELD -> FilingDTOs.ReportState.HELD;
            // A report bound for the admins can wait here behind the hourly limit, and it is not going to GitHub.
            case QUEUED ->
                route == FilingRoute.ADMINS ? FilingDTOs.ReportState.SAVED_FOR_ADMINS : FilingDTOs.ReportState.FILING;
            case FILING -> FilingDTOs.ReportState.FILING;
            case FILED -> FilingDTOs.ReportState.FILED;
            case SAVED -> FilingDTOs.ReportState.SAVED_FOR_ADMINS;
            case STOPPED -> FilingDTOs.ReportState.STOPPED;
        };
    }

    /** Whether an admin must act for the report to go on; every other state moves by itself or has arrived. */
    static boolean waitsForAdmin(FilingState state) {
        return switch (state) {
            case SAVED, STOPPED -> true;
            case DRAFT_QUEUED, DRAFTING, ANSWERED, HELD, QUEUED, FILING, FILED -> false;
        };
    }
}
