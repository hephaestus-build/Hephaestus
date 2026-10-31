package de.tum.cit.aet.hephaestus.agent;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUpstreamIdConflictException;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingSlotMismatchException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Explicit handlers run before GlobalControllerAdvice, which precedes ResponseStatusExceptionResolver.
@RestControllerAdvice(basePackageClasses = AgentControllerAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentControllerAdvice {

    /**
     * An optimistic-lock failure that outlived its retries is a conflict, not a server fault.
     *
     * <p>Agent entities carry version columns because an admin screen and a scheduler write the same rows,
     * so this is a contended-row outcome the caller can act on by repeating the request. Without the
     * mapping it falls through to {@code GlobalControllerAdvice}'s
     * {@code @ExceptionHandler(Exception.class)} and becomes a 500, which tells the admin the server is
     * broken when what happened is that somebody else got there first.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException exception) {
        return problem(
                HttpStatus.CONFLICT,
                ProblemType.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                new IllegalStateException("The record changed while the server processed this request. Try again."));
    }

    @ExceptionHandler(ReviewJobStateConflictException.class)
    ProblemDetail handleAgentJobStateConflict(ReviewJobStateConflictException exception) {
        return problem(
                HttpStatus.CONFLICT, ProblemType.AGENT_JOB_STATE_CONFLICT, "Agent job state conflict", exception);
    }

    /** {@code declaredTier} lets the client name the tier from its own label registry. */
    @ExceptionHandler(AgentBindingSlotMismatchException.class)
    ProblemDetail handleBindingSlotMismatch(AgentBindingSlotMismatchException exception) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                ProblemType.AGENT_BINDING_SLOT_MISMATCH,
                "Model does not fill this row",
                exception);
        DataHandlingTier declaredTier = exception.declaredTier();
        if (declaredTier != null) {
            problem.setProperty("declaredTier", declaredTier);
        }
        return problem;
    }

    @ExceptionHandler(LlmConnectionInUseException.class)
    ProblemDetail handleConnectionInUse(LlmConnectionInUseException exception) {
        return problem(HttpStatus.CONFLICT, ProblemType.LLM_CONNECTION_IN_USE, "LLM connection is in use", exception);
    }

    @ExceptionHandler(LlmModelInUseException.class)
    ProblemDetail handleModelInUse(LlmModelInUseException exception) {
        return problem(HttpStatus.CONFLICT, ProblemType.LLM_MODEL_IN_USE, "LLM model is in use", exception);
    }

    @ExceptionHandler(LlmConnectionSlugConflictException.class)
    ProblemDetail handleConnectionSlugConflict(LlmConnectionSlugConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                ProblemType.LLM_CONNECTION_SLUG_CONFLICT,
                "LLM connection slug conflict",
                exception);
    }

    @ExceptionHandler(LlmModelSlugConflictException.class)
    ProblemDetail handleModelSlugConflict(LlmModelSlugConflictException exception) {
        return problem(HttpStatus.CONFLICT, ProblemType.LLM_MODEL_SLUG_CONFLICT, "LLM model slug conflict", exception);
    }

    @ExceptionHandler(LlmCallPoolInUseException.class)
    ProblemDetail handleCallPoolInUse(LlmCallPoolInUseException exception) {
        return problem(HttpStatus.CONFLICT, ProblemType.LLM_CALL_POOL_IN_USE, "Call pool is in use", exception);
    }

    @ExceptionHandler(LlmCallPoolSlugConflictException.class)
    ProblemDetail handleCallPoolSlugConflict(LlmCallPoolSlugConflictException exception) {
        return problem(
                HttpStatus.CONFLICT, ProblemType.LLM_CALL_POOL_SLUG_CONFLICT, "LLM call pool slug conflict", exception);
    }

    @ExceptionHandler(LlmModelUpstreamIdConflictException.class)
    ProblemDetail handleModelUpstreamIdConflict(LlmModelUpstreamIdConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                ProblemType.LLM_MODEL_UPSTREAM_ID_CONFLICT,
                "LLM model upstream id conflict",
                exception);
    }

    /** Hephaestus declines the member; the detail names the setting that changes it. */
    @ExceptionHandler(MentorRefusedException.class)
    ProblemDetail handleMentorRefused(MentorRefusedException exception) {
        return ProblemType.MENTOR_REFUSED.problem(
                HttpStatus.CONFLICT,
                "Hephaestus cannot reply",
                exception.reason().userMessage());
    }

    /** The ledger refused a write that conflicts with its state; the detail is worded for the developer. */
    @ExceptionHandler(MentorRejectedException.class)
    ProblemDetail handleMentorRejected(MentorRejectedException exception) {
        return ProblemType.MENTOR_REJECTED.problem(
                HttpStatus.CONFLICT, "Conversation conflict", exception.reason().userMessage());
    }

    /** A Hephaestus host wrote for a turn attempt that lost its lease. */
    @ExceptionHandler(MentorLeaseLostException.class)
    ProblemDetail handleMentorLeaseLost(MentorLeaseLostException exception) {
        return problem(HttpStatus.CONFLICT, ProblemType.MENTOR_LEASE_LOST, "Turn lease lost", exception);
    }

    private ProblemDetail problem(HttpStatus status, ProblemType type, String title, Exception exception) {
        return type.problem(
                status,
                title,
                Optional.ofNullable(exception.getMessage())
                        .map(LoggingUtils::sanitizeForLog)
                        .filter(s -> !s.isBlank())
                        .orElse("The server could not process this request."));
    }
}
