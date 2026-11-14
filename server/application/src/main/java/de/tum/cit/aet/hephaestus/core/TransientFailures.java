package de.tum.cit.aet.hephaestus.core;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Whether a failure is an outage that the same work survives when it runs again: a dropped connection, a provider
 * that answers 5xx, 429 or 408 or a rate limit, or a database that is down, busy or rolled the transaction back. A
 * retry of such a failure logs at WARN; the final verdict logs at ERROR (docs/contributor/structured-logging.mdx §
 * Retries).
 */
public final class TransientFailures {

    /**
     * Connection loss, a session ended for idling in a transaction, serialization failure, deadlock, too many
     * connections, lock timeout, administrator shutdown.
     */
    private static final Set<String> TRANSIENT_SQL_STATES =
            Set.of("25P03", "40001", "40P01", "53300", "55P03", "57P01", "57P02", "57P03");

    private TransientFailures() {}

    /** Whether {@code failure} or any of its causes is transient. */
    public static boolean isTransient(Throwable failure) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SocketException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof WebClientRequestException) {
                return true;
            }
            if (cause instanceof WebClientResponseException response
                    && isTransientResponse(response.getStatusCode(), response.getHeaders())) {
                return true;
            }
            if (cause instanceof RestClientResponseException response
                    && isTransientResponse(response.getStatusCode(), response.getResponseHeaders())) {
                return true;
            }
            if (cause instanceof SQLException sql && isTransientSqlState(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientSqlState(@Nullable String state) {
        return state != null && (state.startsWith("08") || TRANSIENT_SQL_STATES.contains(state));
    }

    private static boolean isTransientResponse(HttpStatusCode status, @Nullable HttpHeaders headers) {
        return status.is5xxServerError()
                || status.value() == 429
                || status.value() == 408
                || (status.value() == 403
                        && headers != null
                        && (headers.containsHeader(HttpHeaders.RETRY_AFTER)
                                || "0".equals(headers.getFirst("X-RateLimit-Remaining"))));
    }
}
