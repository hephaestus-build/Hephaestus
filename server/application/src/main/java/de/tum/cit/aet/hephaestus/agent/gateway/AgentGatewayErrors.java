package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.conversation.host.ConversationTurnController;
import de.tum.cit.aet.hephaestus.agent.job.ReviewSandboxController;
import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.proxy.ResultAdmissionController;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The answers that every route a sandbox calls gives the same way: the gateway, Hephaestus's turn, the review runtime, result
 * admission and the model proxy. It comes before the global advice, which would answer {@code 409} or {@code 500}.
 */
@RestControllerAdvice(
        basePackageClasses = {
            AgentRunController.class,
            ConversationTurnController.class,
            ReviewSandboxController.class,
            ResultAdmissionController.class
        })
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnAgentRuntime
public class AgentGatewayErrors {
    private static final Logger log = LoggerFactory.getLogger(AgentGatewayErrors.class);

    /**
     * A claim that no longer holds its run answers {@code 409}, with the text that the proxy answers a model call of
     * such a claim: the loop reads both the same way and stops the run.
     */
    @ExceptionHandler(StaleRunException.class)
    ProblemDetail stale(StaleRunException stale) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
        problem.setTitle("Lease lost");
        return problem;
    }

    /**
     * A run whose authority narrowed answers as a claim that lost its run, so the loop stops: the recheck already ended
     * the run. The reason names the authority that narrowed.
     */
    @ExceptionHandler(AuthorityRefusedException.class)
    ProblemDetail authorityRefused(AuthorityRefusedException refused) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
        problem.setTitle("Authority changed");
        problem.setProperty("boundary", refused.refusal().boundary().name());
        problem.setProperty("reason", refused.refusal().reason().name());
        return problem;
    }

    /**
     * A write waited past the pool's lock bound, or lost a deadlock: the sandbox calls again ({@code pi-runner-retry}
     * and gateway-run.ts retry a {@code 503}). A {@code 409} would read as a refusal and stop the run.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail busy(PessimisticLockingFailureException busy) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Another write holds this run's data. Try again.");
        problem.setTitle("Busy");
        return problem;
    }

    /**
     * An answer that a route already decided, such as preparation's {@code 503} with {@code Retry-After}, keeps its own
     * status, headers and detail, as the global advice answers it. Spring matches a handler by an exception's causes
     * too, so without this the cause would choose the answer instead, through {@link #database}.
     */
    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ProblemDetail> decided(ErrorResponseException decided) {
        return ResponseEntity.status(decided.getStatusCode())
                .headers(decided.getHeaders())
                .body(decided.getBody());
    }

    /**
     * This server lost its database connection, for example when PostgreSQL restarted: the sandbox calls again, as for
     * {@link #busy}, and the attempt keeps its work. A {@code 500} would end the attempt as the server's own error.
     * A rollback on the lost connection replaces the original exception, so the wrappers are read by their causes.
     * Any other failure of these kinds answers as {@link GlobalControllerAdvice#unhandled} does.
     */
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        RecoverableDataAccessException.class,
        CannotCreateTransactionException.class,
        TransactionSystemException.class,
        JpaSystemException.class
    })
    ProblemDetail database(RuntimeException failure) {
        if (!connectionLost(failure)) return GlobalControllerAdvice.unhandled(failure);
        log.atWarn()
                .addKeyValue("event.name", "agent.gateway.database_unavailable")
                .addKeyValue("error.type", failure.getClass().getName())
                .log("A sandbox call lost this server's database connection; the sandbox calls again");
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "This server lost its database connection. Try again.");
        problem.setTitle("Database unavailable");
        return problem;
    }

    /**
     * The SQLSTATEs of a connection that failed or was closed, and of a server that shut down or is starting. Not
     * {@code 08P01}: a protocol violation repeats on the next call.
     */
    private static final String PROTOCOL_VIOLATION = "08P01";

    private static final Set<String> LOST_CONNECTION_STATES =
            Set.of("08000", "08001", "08003", "08004", "08006", "57P01", "57P02", "57P03");

    /** Whether {@code failure} or one of its causes is a lost or refused database connection. */
    static boolean connectionLost(Throwable failure) {
        // Hibernate maps all of SQLSTATE class 08 to JDBCConnectionException, a protocol violation included.
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && PROTOCOL_VIOLATION.equals(sql.getSQLState())) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof JDBCConnectionException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException) return true;
            if (cause instanceof SQLException sql
                    && sql.getSQLState() != null
                    && LOST_CONNECTION_STATES.contains(sql.getSQLState())) return true;
        }
        return false;
    }
}
