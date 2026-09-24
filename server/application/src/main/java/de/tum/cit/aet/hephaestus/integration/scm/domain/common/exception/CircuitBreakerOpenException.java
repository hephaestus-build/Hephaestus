package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;

/**
 * Exception thrown when a GitHub API call is rejected because the circuit breaker is open.
 * <p>
 * This indicates that recent calls to GitHub have been failing, and the system is
 * protecting itself by failing fast without attempting to call the API.
 * <p>
 * Callers should:
 * <ul>
 * <li>Retry after the circuit breaker timeout (default 30 seconds)</li>
 * <li>Return cached data if available</li>
 * <li>Gracefully degrade functionality</li>
 * </ul>
 */
public class CircuitBreakerOpenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CircuitBreakerOpenException(String message) {
        super(message);
    }

    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
