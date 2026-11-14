package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.conversation.host.ConversationTurnController;
import de.tum.cit.aet.hephaestus.agent.job.ReviewSandboxController;
import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.proxy.ResultAdmissionController;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
}
