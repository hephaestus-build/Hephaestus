package de.tum.cit.aet.hephaestus.agent;

import de.tum.cit.aet.hephaestus.agent.backfill.ReviewBackfillConflictException;
import de.tum.cit.aet.hephaestus.agent.backfill.ReviewSweepScheduleConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUpstreamIdConflictException;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingSlotMismatchException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.net.URI;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapper for agent-module exceptions, per {@code docs/contributor/api-error-handling.md}.
 *
 * <p>An agent exception without an explicit handler below returns 500 regardless of any
 * {@code @ResponseStatus} on it: {@code GlobalControllerAdvice}'s
 * {@code @ExceptionHandler(Exception.class)} catches it before
 * {@link org.springframework.web.servlet.mvc.support.ResponseStatusExceptionResolver} can run.
 */
@RestControllerAdvice(basePackageClasses = AgentControllerAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentControllerAdvice {

    @ExceptionHandler(ReviewBackfillConflictException.class)
    ProblemDetail handleReviewBackfillConflict(ReviewBackfillConflictException exception) {
        return problem(HttpStatus.CONFLICT, "review-backfill-conflict", "Review backfill conflict", exception);
    }

    @ExceptionHandler(ReviewSweepScheduleConflictException.class)
    ProblemDetail handleReviewSweepScheduleConflict(ReviewSweepScheduleConflictException exception) {
        return problem(
                HttpStatus.CONFLICT, "review-sweep-schedule-conflict", "Review sweep schedule conflict", exception);
    }

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
                "concurrent-modification",
                "Concurrent modification",
                new IllegalStateException("The record changed while the server processed this request. Try again."));
    }

    @ExceptionHandler(AgentJobStateConflictException.class)
    ProblemDetail handleAgentJobStateConflict(AgentJobStateConflictException exception) {
        return problem(HttpStatus.CONFLICT, "agent-job-state-conflict", "Agent job state conflict", exception);
    }

    /** {@code declaredTier} lets the client name the tier from its own label registry. */
    @ExceptionHandler(AgentBindingSlotMismatchException.class)
    ProblemDetail handleBindingSlotMismatch(AgentBindingSlotMismatchException exception) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "agent-binding-slot-mismatch", "Model does not fill this row", exception);
        DataHandlingTier declaredTier = exception.declaredTier();
        if (declaredTier != null) {
            problem.setProperty("declaredTier", declaredTier);
        }
        return problem;
    }

    @ExceptionHandler(LlmConnectionInUseException.class)
    ProblemDetail handleConnectionInUse(LlmConnectionInUseException exception) {
        return problem(HttpStatus.CONFLICT, "llm-connection-in-use", "LLM connection is in use", exception);
    }

    @ExceptionHandler(LlmModelInUseException.class)
    ProblemDetail handleModelInUse(LlmModelInUseException exception) {
        return problem(HttpStatus.CONFLICT, "llm-model-in-use", "LLM model is in use", exception);
    }

    @ExceptionHandler(LlmConnectionSlugConflictException.class)
    ProblemDetail handleConnectionSlugConflict(LlmConnectionSlugConflictException exception) {
        return problem(HttpStatus.CONFLICT, "llm-connection-slug-conflict", "LLM connection slug conflict", exception);
    }

    @ExceptionHandler(LlmModelSlugConflictException.class)
    ProblemDetail handleModelSlugConflict(LlmModelSlugConflictException exception) {
        return problem(HttpStatus.CONFLICT, "llm-model-slug-conflict", "LLM model slug conflict", exception);
    }

    @ExceptionHandler(LlmModelUpstreamIdConflictException.class)
    ProblemDetail handleModelUpstreamIdConflict(LlmModelUpstreamIdConflictException exception) {
        return problem(
                HttpStatus.CONFLICT, "llm-model-upstream-id-conflict", "LLM model upstream id conflict", exception);
    }

    /** Heph declines the member; {@code reason} lets the app point at the setting that changes it. */
    @ExceptionHandler(MentorRefusedException.class)
    ProblemDetail handleMentorRefused(MentorRefusedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.reason().userMessage());
        problem.setType(URI.create("/problems/mentor-refused"));
        problem.setTitle("Heph cannot reply");
        problem.setProperty("reason", exception.reason().name());
        return problem;
    }

    /** The ledger refused a write that conflicts with its state; the detail is worded for the developer. */
    @ExceptionHandler(MentorRejectedException.class)
    ProblemDetail handleMentorRejected(MentorRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.reason().userMessage());
        problem.setType(URI.create("/problems/mentor-rejected"));
        problem.setTitle("Heph conversation conflict");
        problem.setProperty("reason", exception.reason().name());
        return problem;
    }

    /** A Heph host wrote for a turn attempt that lost its lease. */
    @ExceptionHandler(MentorLeaseLostException.class)
    ProblemDetail handleMentorLeaseLost(MentorLeaseLostException exception) {
        return problem(HttpStatus.CONFLICT, "mentor-lease-lost", "Turn lease lost", exception);
    }

    /** The {@code type} slug is API surface clients match on — renaming one is a breaking change. */
    private ProblemDetail problem(HttpStatus status, String typeSlug, String title, Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("/problems/" + typeSlug));
        problem.setTitle(title);
        problem.setDetail(Optional.ofNullable(exception.getMessage())
                .map(LoggingUtils::sanitizeForLog)
                .filter(s -> !s.isBlank())
                .orElse("The server could not process this request."));
        return problem;
    }
}
