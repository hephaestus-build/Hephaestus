package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The outcome of an advice write. */
public sealed interface AdviceWrite {
    /** The entry now has {@code version}; a retry of the same decision returns the same value. */
    record Written(UUID adviceId, long version) implements AdviceWrite {}

    /** Nothing was written. {@code stopReason} names the gate's reason for {@link AdviceRefusal#GATE_REFUSED}. */
    record Refused(AdviceRefusal reason, @Nullable FeedbackStopReason stopReason) implements AdviceWrite {}
}
