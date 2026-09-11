package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A review whose observations the server will not record: the run finished, and what it submitted
 * does not support a claim about anyone's work. This is a decision the review stage took, not a
 * failure of the machinery around it, so the sandbox is told so and stops rather than repeating the
 * submission against a server that will refuse it again for the same reason.
 *
 * <p>The reason code is a stable, lowercase name for the refusal, for metrics and logs — never the
 * message, which names one job.
 */
public class ObservationsRefusedException extends JobDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final String reason;
    private final transient JsonNode verificationFailures;

    public ObservationsRefusedException(String reasonCode, String reason) {
        this(reasonCode, reason, JsonNodeFactory.instance.arrayNode());
    }

    public ObservationsRefusedException(String reasonCode, String reason, JsonNode verificationFailures) {
        super(reason);
        this.verificationFailures = verificationFailures.deepCopy();
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    public JsonNode verificationFailures() {
        return verificationFailures.deepCopy();
    }

    public String reasonCode() {
        return reasonCode;
    }

    /** The refusal in words, always present — unlike {@code getMessage()}, which a caller must null-check. */
    public String reason() {
        return reason;
    }
}
