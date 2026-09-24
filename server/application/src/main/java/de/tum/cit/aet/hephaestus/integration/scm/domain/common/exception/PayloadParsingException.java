package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;

/**
 * Exception thrown when parsing a webhook payload fails.
 * <p>
 * This typically occurs when:
 * <ul>
 *   <li>The JSON is malformed or invalid</li>
 *   <li>Required fields are missing from the payload</li>
 *   <li>Field types don't match expected types</li>
 * </ul>
 */
public class PayloadParsingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PayloadParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    public PayloadParsingException(String message) {
        super(message);
    }
}
