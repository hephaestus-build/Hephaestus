package de.tum.cit.aet.hephaestus.core.action;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** What one attempt found out. The executor records it with a compare-and-set on the attempt's lease. */
public sealed interface ActionResult {

    /** The provider confirmed the effect. */
    record Done(@Nullable String readbackRef) implements ActionResult {}

    /**
     * The provider proved that it holds nothing from this attempt, and a later attempt may succeed. The action waits
     * {@code after} and runs again, or ends {@link ActionStopReason#ATTEMPTS_EXHAUSTED} at its last attempt.
     */
    record Retry(Duration after) implements ActionResult {}

    /**
     * The write may have reached the provider, and the provider can be read back. The action keeps its fence and is
     * taken again after {@code after}; that attempt reads back by the action's marker before it writes again. At its
     * last attempt the action ends {@link ActionState#UNCERTAIN}.
     */
    record Reread(Duration after) implements ActionResult {}

    /** Not now, and nothing was written: a pace or a rate limit. The attempt does not count. */
    record Defer(Duration after) implements ActionResult {}

    /** The effect may exist. Nothing will send it again. */
    record Uncertain(ActionStopReason reason) implements ActionResult {}

    /** The provider refused, or nobody can receive it. Nothing was written. */
    record Failed(ActionStopReason reason) implements ActionResult {}

    /** Nothing was written, and nothing should be. */
    record Cancelled(ActionStopReason reason) implements ActionResult {}

    /** The fence refused: another holder or a cancellation owns the action now. The executor writes nothing. */
    record Abandoned() implements ActionResult {}
}
