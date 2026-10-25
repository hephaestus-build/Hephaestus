package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.io.Serial;

/**
 * A composed text breaks the feedback text policy, so nothing was prepared. It names the rule and the field, never the
 * text, so the composer can rewrite that text and send the package again.
 */
public class FeedbackTextRefusedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final FeedbackDestination destination;
    private final String rule;
    private final String field;

    public FeedbackTextRefusedException(FeedbackDestination destination, String rule, String field) {
        super("A composed feedback text breaks the text policy");
        this.destination = destination;
        this.rule = rule;
        this.field = field;
    }

    public FeedbackDestination destination() {
        return destination;
    }

    /** A {@code FeedbackTextPolicy.Rule} name. */
    public String rule() {
        return rule;
    }

    public String field() {
        return field;
    }
}
