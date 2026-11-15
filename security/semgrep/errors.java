import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

class ErrorExamples {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ErrorExamples.class);

    ProblemDetail generic(IllegalArgumentException exception) {
        // ruleid: java-generic-exception-message-to-client
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    ProblemDetail optional(NoSuchElementException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        // ruleid: java-generic-exception-message-to-client
        problem.setDetail(exception.getMessage());
        return problem;
    }

    void rethrown() {
        try {
            run();
        } catch (IllegalStateException e) {
            String detail = "Failed: " + e.getMessage();
            // ruleid: java-generic-exception-message-to-client
            throw new ResponseStatusException(HttpStatus.CONFLICT, detail, e);
        }
    }

    ProblemDetail untyped(IllegalStateException exception) {
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        // ruleid: java-generic-exception-message-to-client
        problem.setTitle(exception.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail throughHelper(IllegalArgumentException exception) {
        // ruleid: java-generic-exception-message-to-client
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    // A parameter, so only the typed setTitle sink can match, not the block that follows ProblemDetail.$CREATE.
    ProblemDetail typedTitle(ProblemDetail problem, IllegalStateException exception) {
        // ruleid: java-generic-exception-message-to-client
        problem.setTitle(exception.getMessage());
        return problem;
    }

    ProblemDetail typedProperty(ProblemDetail problem, IllegalStateException exception) {
        // ruleid: java-generic-exception-message-to-client
        problem.setProperty("reason", exception.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail throughOwnHelper(IllegalArgumentException exception) {
        // ruleid: java-generic-exception-message-to-client
        return this.problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> adHocBody(IllegalArgumentException exception) {
        // ruleid: java-generic-exception-message-to-client
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ErrorBody constructed(IllegalArgumentException exception) {
        // ruleid: java-generic-exception-message-to-client
        return new ErrorBody(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail logged(IllegalArgumentException exception) {
        // ok: java-generic-exception-message-to-client
        log.warn("Refused: {}", exception.getMessage());
        // ok: java-generic-exception-message-to-client
        log.atInfo().addKeyValue("reason", exception.getMessage()).log("Refused");
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "We could not accept this request.");
    }

    ProblemDetail fixed(IllegalArgumentException exception) {
        // ok: java-generic-exception-message-to-client
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "We could not accept this request.");
    }

    ProblemDetail domain(SlugConflictException exception) {
        // ok: java-generic-exception-message-to-client
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    void run() {}

    record ErrorBody(String error) {}

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    static final class SlugConflictException extends RuntimeException {}
}
