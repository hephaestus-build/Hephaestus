package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateConflictException;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = SyncController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SyncControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(SyncControllerAdvice.class);

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail handleNotFound(NoSuchElementException exception) {
        // The response carries the message; the log names the failure only.
        log.info("Sync lookup failed: {}", exception.getClass().getSimpleName());
        return problem(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                Objects.requireNonNullElse(exception.getMessage(), "Resource not found"));
    }

    @ExceptionHandler(SyncStateConflictException.class)
    ProblemDetail handleStateConflict(SyncStateConflictException exception) {
        log.info("Sync state conflict: {}", exception.getClass().getSimpleName());
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Invalid state",
                Objects.requireNonNullElse(exception.getMessage(), "Invalid state"));
        exception.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(SyncNotSupportedException.class)
    ProblemDetail handleNotSupported(SyncNotSupportedException exception) {
        log.info("Sync not supported: kind={}", exception.kind());
        return ProblemType.SYNC_NOT_SUPPORTED.problem(
                HttpStatus.CONFLICT,
                "Manual sync not supported",
                Objects.requireNonNullElse(exception.getMessage(), "Manual sync not supported"));
    }

    @ExceptionHandler(TaskRejectedException.class)
    ProblemDetail handleDispatchRejected(TaskRejectedException exception) {
        log.warn("Sync dispatch rejected: {}", exception.getClass().getSimpleName());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Sync dispatch rejected",
                "The server is busy and could not start the sync. Try again.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
