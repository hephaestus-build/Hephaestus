package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import de.tum.cit.aet.hephaestus.workspace.exception.InvalidWorkspaceSlugException;
import de.tum.cit.aet.hephaestus.workspace.exception.LastOwnerRemovalException;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryAlreadyMonitoredException;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryManagementNotAllowedException;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceSlugConflictException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkspaceControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceControllerAdvice.class);

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleNotFound(EntityNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                Objects.requireNonNullElse(exception.getMessage(), "Unexpected error"));
    }

    @ExceptionHandler({InvalidWorkspaceSlugException.class, IllegalArgumentException.class})
    ProblemDetail handleBadRequest(RuntimeException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid workspace request",
                userFacingDetail(Objects.requireNonNullElse(exception.getMessage(), "Unexpected error")));
    }

    @ExceptionHandler(WorkspaceSlugConflictException.class)
    ProblemDetail handleConflict(WorkspaceSlugConflictException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Workspace slug conflict",
                userFacingDetail(Objects.requireNonNullElse(exception.getMessage(), "Unexpected error")));
    }

    @ExceptionHandler(InsufficientWorkspacePermissionsException.class)
    ProblemDetail handleInsufficientPermissions(InsufficientWorkspacePermissionsException exception) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Insufficient workspace permissions",
                Objects.requireNonNullElse(exception.getMessage(), "Access denied"));
    }

    @ExceptionHandler(LastOwnerRemovalException.class)
    ProblemDetail handleLastOwnerRemoval(LastOwnerRemovalException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Workspace owner required",
                Objects.requireNonNullElse(exception.getMessage(), "The workspace must retain an owner"));
    }

    @ExceptionHandler(RepositoryAlreadyMonitoredException.class)
    ProblemDetail handleRepositoryConflict(RepositoryAlreadyMonitoredException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Repository already monitored",
                userFacingDetail(Objects.requireNonNullElse(exception.getMessage(), "Unexpected error")));
    }

    @ExceptionHandler(RepositoryManagementNotAllowedException.class)
    ProblemDetail handleRepositoryManagementNotAllowed(RepositoryManagementNotAllowedException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Repository management not allowed",
                userFacingDetail(Objects.requireNonNullElse(exception.getMessage(), "Unexpected error")));
    }

    @ExceptionHandler({WorkspaceLifecycleViolationException.class, WorkspacePurgeBlockedException.class})
    ProblemDetail handleLifecycleViolation(RuntimeException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Workspace lifecycle violation",
                userFacingDetail(Objects.requireNonNullElse(exception.getMessage(), "Unexpected error")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        Map<String, List<String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(
                        FieldError::getField, Collectors.mapping(FieldError::getDefaultMessage, Collectors.toList())));

        return problemWithErrors(
                HttpStatus.BAD_REQUEST, "Validation failed", "Request body contains invalid fields", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, List<String>> errors = exception.getConstraintViolations().stream()
                .collect(Collectors.groupingBy(
                        violation -> leafProperty(violation).orElse("value"),
                        Collectors.mapping(ConstraintViolation::getMessage, Collectors.toList())));

        return problemWithErrors(
                HttpStatus.BAD_REQUEST, "Validation failed", "Request parameters contain invalid values", errors);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException exception) {
        log.error("Encountered unexpected workspace state", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Workspace operation failed",
                "The workspace request could not be completed. Please try again later.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(userFacingDetail(detail));
        return problem;
    }

    private ProblemDetail problemWithErrors(
            HttpStatus status, String title, String detail, Map<String, List<String>> errors) {
        ProblemDetail problem = problem(status, title, detail);
        problem.setProperty("errors", errors);
        return problem;
    }

    private String userFacingDetail(String detail) {
        return Optional.ofNullable(detail)
                .map(LoggingUtils::sanitizeForLog)
                .filter(s -> !s.isBlank())
                .orElse("The workspace request could not be processed.");
    }

    private Optional<String> leafProperty(ConstraintViolation<?> violation) {
        Path path = violation.getPropertyPath();
        String leaf = null;
        for (Path.Node node : path) {
            leaf = node.getName();
        }
        return Optional.ofNullable(leaf);
    }
}
