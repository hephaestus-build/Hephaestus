package de.tum.cit.aet.hephaestus.integration.scm.common;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.client.GraphQlTransportException;
import reactor.netty.http.client.PrematureCloseException;

/**
 * Recognises transport-level errors during GitHub GraphQL operations: connection resets, premature closes,
 * aborted connections and blocking read timeouts.
 */
@Slf4j
public final class ScmTransportErrors {

    private ScmTransportErrors() {
        // Utility class
    }

    /**
     * Determines whether the given throwable represents a transport-level error
     * (as opposed to an application-level GraphQL error or rate limit).
     *
     * @param throwable the error to classify
     * @return true if this is a transport error that may be retried
     */
    public static boolean isTransportError(Throwable throwable) {
        // GraphQlTransportException is Spring GraphQL's wrapper for transport failures
        if (throwable instanceof GraphQlTransportException) {
            log.debug("Transport error detected: GraphQlTransportException");
            return true;
        }

        // Walk the cause chain for wrapped transport errors
        Throwable cause = throwable;
        while (cause != null) {
            String className = cause.getClass().getName();
            String message = cause.getMessage();

            // PrematureCloseException: Connection closed during response streaming
            if (cause instanceof PrematureCloseException || className.contains("PrematureCloseException")) {
                log.debug("Transport error detected: PrematureCloseException");
                return true;
            }

            // Other reactor-netty transport errors
            if (className.contains("AbortedException") || className.contains("ConnectionResetException")) {
                log.debug("Transport error detected: {}", className);
                return true;
            }

            // Timeout during blocking read (body consumption timeout)
            if (cause instanceof IllegalStateException
                    && message != null
                    && message.toLowerCase().contains("timeout on blocking read")) {
                log.debug("Transport error detected: blocking read timeout");
                return true;
            }

            // Check for IOException indicating connection issues
            if (cause instanceof IOException && message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connection reset")
                        || lower.contains("broken pipe")
                        || lower.contains("connection abort")
                        || lower.contains("premature")
                        || lower.contains("stream closed")) {
                    log.debug("Transport error detected: IOException - {}", message);
                    return true;
                }
            }

            cause = cause.getCause();
        }
        return false;
    }
}
