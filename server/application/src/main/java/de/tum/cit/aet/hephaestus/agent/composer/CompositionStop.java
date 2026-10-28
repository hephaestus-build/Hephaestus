package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.Locale;

/**
 * Why a destination ended without composed feedback. A cause that another attempt would meet again is recorded at
 * once as a withholding with the most specific reason; a passing one hands the attempt on, and the attempt at the retry
 * limit records it instead.
 */
public enum CompositionStop {
    /** The workspace's AI choice no longer permits the model. */
    NO_AI(FeedbackStopReason.NO_AI, false),
    /** The budget that funds the attempt is spent. */
    BUDGET(FeedbackStopReason.NOT_COMPOSED, false),
    /**
     * The composition deadline passed. It hands the attempt on: under load the model calls wait for the shared call
     * pool, so the window runs out on calls that never ran, and a new attempt composes only the destinations still
     * open, in a fresh window. Withholding at once let one destination be delivered while another of the same review
     * was withheld for a cause that a later attempt does not meet.
     */
    DEADLINE(FeedbackStopReason.NOT_COMPOSED, true),
    /** The review's model is no longer available to the workspace. */
    MODEL_UNAVAILABLE(FeedbackStopReason.NOT_COMPOSED, false),
    /** The provider refused the request itself, such as a revoked key. */
    MODEL_REFUSED(FeedbackStopReason.NOT_COMPOSED, false),
    /** The inputs exceed the model's context window. */
    CONTEXT_WINDOW(FeedbackStopReason.NOT_COMPOSED, false),
    /** The server refused to publish the review on the work; its withholding records the server's reason. */
    PUBLICATION_REFUSED(FeedbackStopReason.NOT_COMPOSED, false),
    /** The model decided nothing for the destination, within its calls and after it was asked once more. */
    NO_DECISION(FeedbackStopReason.NOT_COMPOSED, false),
    /** The inputs the review was measured from are no longer retained. */
    INPUTS_GONE(FeedbackStopReason.NOT_COMPOSED, false),
    /** The inputs the review was measured from were erased. */
    INPUTS_ERASED(FeedbackStopReason.EVIDENCE_NOT_PERMITTED, false),
    /** The attempts the review may use ran out; the attempt after the limit composes nothing. */
    RETRIES_EXHAUSTED(FeedbackStopReason.NOT_COMPOSED, false),
    /** The provider failed past the session's calls or deadline. */
    PROVIDER(FeedbackStopReason.NOT_COMPOSED, true),
    /** The server could not read where the destination routes. */
    ROUTING_UNAVAILABLE(FeedbackStopReason.NOT_COMPOSED, true),
    /** Composition failed for a reason it did not expect. */
    UNEXPECTED(FeedbackStopReason.NOT_COMPOSED, true);

    private final FeedbackStopReason reason;
    private final boolean passing;

    CompositionStop(FeedbackStopReason reason, boolean passing) {
        this.reason = reason;
        this.passing = passing;
    }

    /** The withholding reason this cause records. */
    public FeedbackStopReason reason() {
        return reason;
    }

    /** Whether another attempt may compose what this cause left open. */
    public boolean passing() {
        return passing;
    }

    /** The metric tag value. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
