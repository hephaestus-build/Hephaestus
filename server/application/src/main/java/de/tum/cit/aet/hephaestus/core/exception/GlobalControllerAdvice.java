package de.tum.cit.aet.hephaestus.core.exception;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditUnavailableException;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyViolationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Global exception handler providing RFC 7807 ProblemDetail responses for all controllers.
 *
 * <p>This handler has the lowest precedence, allowing package-specific handlers
 * (like WorkspaceControllerAdvice) to take priority for their domains.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalControllerAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalControllerAdvice.class);

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleNotFound(EntityNotFoundException exception) {
        log.debug(
                "Handled entity not found exception: message={}, lookup={}",
                messageOf(exception),
                exception.getLookup());
        return problem(HttpStatus.NOT_FOUND, "Resource not found", messageOf(exception));
    }

    @ExceptionHandler(AccessForbiddenException.class)
    ProblemDetail handleForbidden(AccessForbiddenException exception) {
        log.warn("Handled access forbidden exception: message={}", messageOf(exception));
        return problem(HttpStatus.FORBIDDEN, "Access denied", messageOf(exception));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException exception) {
        log.debug("Authorization denied: message={}", messageOf(exception));
        return problem(HttpStatus.FORBIDDEN, "Access denied", "You do not have permission to do this.");
    }

    @ExceptionHandler(InvalidInputException.class)
    ProblemDetail handleInvalidInput(InvalidInputException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    /**
     * Any code, a library included, throws this type, so its message is not written for the person who sent the
     * request and can name internal values. A message for that person goes in a {@code ResponseStatusException} or a
     * domain exception that its own advice maps.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        log.atInfo()
                .addKeyValue("event.name", "request.refused")
                .addKeyValue("error.type", exception.getClass().getName())
                .log("Refused a request that an argument check rejected");
        return problem(
                HttpStatus.BAD_REQUEST, "Invalid request", "We could not accept this request. Check it and try again.");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleInvalidSort(PropertyReferenceException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The requested sort field is not available.");
    }

    /** As for {@link IllegalArgumentException}: the message stays on the server. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException exception) {
        log.atWarn()
                .addKeyValue("event.name", "request.state_conflict")
                .addKeyValue("error.type", exception.getClass().getName())
                .log("Refused a request that a state check rejected");
        return problem(
                HttpStatus.CONFLICT,
                "Invalid state",
                "This request does not fit the current data. Reload and try again.");
    }

    @ExceptionHandler(ConfigAuditUnavailableException.class)
    ProblemDetail handleConfigAuditUnavailable(ConfigAuditUnavailableException exception) {
        // 500, not 4xx: the caller did nothing wrong, and a 4xx would keep this out of the error budget
        // that makes a fail-closed audit trail's failure visible. The specific cause stays server-side.
        log.error("Config audit unavailable, change refused: message={}", messageOf(exception));
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Change not recorded",
                "We could not record this change in the audit log, so the change was not saved. Try again in a moment.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        // Unique/FK constraint races (e.g. concurrent connection installs) → 409, not a 500.
        // Database diagnostics can include SQL and rejected row values. Keep the failure class,
        // not the payload, at this shared boundary.
        Throwable cause = exception.getMostSpecificCause();
        var event = log.atWarn()
                .addKeyValue("event.name", "database.constraint.conflict")
                .addKeyValue("error.type", cause.getClass().getName());
        if (cause instanceof SQLException sqlException) {
            String state = sqlException.getSQLState();
            if (state != null && state.matches("[A-Z0-9]{5}")) {
                event = event.addKeyValue("db.response.status_code", state);
            }
        }
        event.log("Request conflicts with database constraints");
        return problem(
                HttpStatus.CONFLICT, "Conflict", "This request conflicts with the current data. Reload and try again.");
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail handleConcurrentChange(ConcurrencyFailureException exception) {
        log.atWarn()
                .addKeyValue("event.name", "database.concurrent.change")
                .addKeyValue("error.type", exception.getClass().getName())
                .log("Request conflicts with another write");
        return problem(
                HttpStatus.CONFLICT,
                "Concurrent change",
                "Another request is changing this data. Reload and try again.");
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(
                        FieldError::getField, Collectors.mapping(FieldError::getDefaultMessage, Collectors.toList())));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Some fields need changes. Check your entries and try again.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, List<String>> errors = exception.getConstraintViolations().stream()
                .collect(Collectors.groupingBy(
                        violation -> leafProperty(violation).orElse("value"),
                        Collectors.mapping(ConstraintViolation::getMessage, Collectors.toList())));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Some values need changes. Check your entries and try again.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(WebClientRequestException.class)
    ProblemDetail handleWebClientRequestException(WebClientRequestException exception) {
        // URLs, headers and exception messages may contain provider credentials or private work.
        // Keep enough structure to distinguish DNS, connection and timeout failures without them.
        log.atWarn()
                .addKeyValue("event.name", "integration.request.failed")
                .addKeyValue("http.request.method", exception.getMethod().name())
                .addKeyValue("server.address", exception.getUri().getHost())
                .addKeyValue(
                        "error.type",
                        exception.getMostSpecificCause().getClass().getName())
                .log("External service request failed");

        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service unavailable",
                "We could not reach a service that Hephaestus depends on. Try again in a moment.");
    }

    @ExceptionHandler(TenancyViolationException.class)
    ProblemDetail handleTenancyViolation(TenancyViolationException ex) {
        // Server bug — log full SQL context server-side, sanitize client-facing message.
        log.error("Tenancy violation: unguardedTables={}", ex.unguardedTables(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "We could not finish that. Try again. If it keeps failing, contact your instance operator.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception exception) {
        return unhandled(exception);
    }

    /** The answer and log line for an exception that no handler maps; other advice that only narrows a type uses it. */
    public static ProblemDetail unhandled(Exception exception) {
        log.error(
                "Caught unhandled exception: exceptionType={}",
                exception.getClass().getSimpleName(),
                exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "We could not finish that. Try again. If it keeps failing, contact your instance operator.");
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() != null
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private static Optional<String> leafProperty(ConstraintViolation<?> violation) {
        String lastNode = null;
        for (Path.Node node : violation.getPropertyPath()) {
            lastNode = node.getName();
        }
        return Optional.ofNullable(lastNode);
    }
}
