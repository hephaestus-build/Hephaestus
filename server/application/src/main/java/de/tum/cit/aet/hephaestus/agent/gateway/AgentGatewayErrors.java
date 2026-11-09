package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A sandbox's call whose claim no longer holds its run answers {@code 409}, with the text that the proxy answers a
 * model call of such a claim: the loop reads both the same way and stops the run.
 */
@RestControllerAdvice(assignableTypes = {AgentNextController.class, AgentRunController.class})
@ConditionalOnWorkerRole
class AgentGatewayErrors {

    @ExceptionHandler(StaleRunException.class)
    ProblemDetail stale(StaleRunException stale) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
        problem.setTitle("Lease lost");
        return problem;
    }
}
