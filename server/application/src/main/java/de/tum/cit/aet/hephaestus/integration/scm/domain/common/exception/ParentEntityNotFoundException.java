package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;

/**
 * Exception thrown when a parent entity required for processing is not found.
 * <p>
 * This exception is designed to be retryable - when thrown during message processing,
 * the NATS consumer will NAK the message with exponential backoff, allowing it to be
 * reprocessed later when the parent entity likely exists.
 * <p>
 * Common scenarios:
 * <ul>
 *   <li>Comment event arrives before the parent issue/PR is synced</li>
 *   <li>Review event arrives before the parent pull request is synced</li>
 *   <li>Sub-issue event arrives before the parent issue is synced</li>
 * </ul>
 * <p>
 * This follows the principle: don't silently drop data - use the retry mechanism
 * to handle event ordering issues that occur in distributed systems.
 */
public class ParentEntityNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ParentEntityNotFoundException(String message) {
        super(message);
    }

    public ParentEntityNotFoundException(String entityType, Long entityId) {
        super(String.format("Parent %s not found: id=%d", entityType, entityId));
    }
}
