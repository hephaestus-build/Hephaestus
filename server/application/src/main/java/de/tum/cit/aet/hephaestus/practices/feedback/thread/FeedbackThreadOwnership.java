package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells Hephaestus's own thread change from a person's. The provider's actor decides first: an event is Hephaestus's
 * own only when its actor is the account that writes the thread's open ask, and that ask is for the state the event
 * reports. An event without an actor is a person's. So a person who resolves a thread while Hephaestus's resolve is
 * still pending takes the thread over, and Hephaestus never reopens it.
 */
@Slf4j
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackThreadOwnership {
    private final FeedbackThreadResolutionRepository resolutions;
    private final FeedbackThreadMeters meters;
    private final Clock clock;

    /** True when the change is Hephaestus's own. Otherwise Hephaestus stops changing the thread for good. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean own(long workspaceId, long itemId, String threadRef, boolean resolved, @Nullable String actor) {
        var row = resolutions.lockThread(workspaceId, itemId, threadRef).orElse(null);
        if (row == null) return false;
        if (row.owns(resolved, actor)) return true;
        // It fails toward people: if the writing account and the event's actor never match, every own change would
        // read as a person's and Hephaestus would stop. Count it, so a mismatch shows before it silences the feature.
        if (row.resembles(resolved)) {
            meters.actorMismatch(row.getProvider());
            log.warn(
                    "Thread event resembles Hephaestus's own write but names another actor: workspaceId={}, provider={}, eventActor={}",
                    workspaceId,
                    row.getProvider(),
                    actor == null ? "none" : "other");
        }
        if (row.getState() != FeedbackThreadResolutionState.STOPPED) {
            meters.record(row.isResolved(), FeedbackThreadStopReason.HUMAN_CONTROLLED);
            row.handOver(clock.instant());
        }
        return false;
    }
}
