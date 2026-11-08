import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.ResponseEntity;
import org.springframework.graphql.ResponseError;

class LoggingExamples {
    Logger log;
    Logger logger;

    void direct(Exception failure, RestClientResponseException response) {
        // ruleid: java-exception-content-in-logs
        log.trace("Failure: {}", failure.getMessage());
        // ruleid: java-exception-content-in-logs
        logger.debug("Failure: " + failure.getLocalizedMessage());
        // ruleid: java-exception-content-in-logs
        log.info("Body: {}", response.getResponseBodyAsString());
        // ruleid: java-exception-content-in-logs
        log.warn("Body: {}", response.getResponseBodyAsString(StandardCharsets.UTF_8));
        // ruleid: java-exception-content-in-logs
        this.logger.error("Failure: {}", failure.getMessage(), failure);
    }

    void variables(Exception failure) {
        String detail = failure.getMessage();
        String formatted = "Failure: " + detail;
        // ruleid: java-exception-content-in-logs
        log.warn(formatted);
        String localized = failure.getLocalizedMessage();
        // ruleid: java-exception-content-in-logs
        logger.error("Failure: {}", localized);
    }

    void fluent(Exception failure) {
        // ruleid: java-exception-content-in-logs
        log.atWarn().log("Failure: {}", failure.getMessage());
        // ruleid: java-exception-content-in-logs
        logger.atError().addKeyValue("detail", failure.getLocalizedMessage()).log("Request failed");
        var event = log.atInfo();
        event.addArgument(failure.getMessage());
        // ruleid: java-exception-content-in-logs
        event.log("Request failed: {}");
    }

    void fluentVariableChain(Exception failure) {
        var event = log.atWarn().addKeyValue("detail", failure.getMessage());
        // ruleid: java-exception-content-in-logs
        event.log("Request failed");
    }

    void typedBuilder(Exception failure, LoggingEventBuilder builder, Logger audit) {
        String detail = failure.getMessage();
        builder.setMessage(detail);
        // ruleid: java-exception-content-in-logs
        builder.log();
        // ruleid: java-exception-content-in-logs
        audit.warn("Failure: {}", detail);
    }

    void bodyVariable(RestClientResponseException response) {
        String body = response.getResponseBodyAsString();
        // ruleid: java-exception-content-in-logs
        logger.error("Provider failed: {}", body);
    }

    ResponseEntity<String> httpOnly(RestClientResponseException response) {
        String detail = response.getMessage();
        // ok: java-exception-content-in-logs
        return ResponseEntity.status(response.getStatusCode()).body(detail);
    }

    void unusedBuilder(Exception failure) {
        var event = logger.atWarn();
        // ok: java-exception-content-in-logs
        event.addArgument(failure.getMessage());
    }

    String safeUses(Exception failure, RestClientResponseException response, UUID jobId) {
        String detail = failure.getMessage();
        String classification = detail != null && detail.contains("retry") ? "RETRYABLE" : "FAILED";
        // ok: java-exception-content-in-logs
        log.warn("Outcome: {}", classification);
        // ok: java-exception-content-in-logs
        log.warn("Request failed");
        // ok: java-exception-content-in-logs
        logger.warn("Request failed", failure);
        // ok: java-exception-content-in-logs
        log.atWarn().setCause(failure).log("Request failed");
        // ok: java-exception-content-in-logs
        logger.debug("Failure class: {}", failure.getClass().getSimpleName());
        // ok: java-exception-content-in-logs
        log.info("Status: {}", response.getStatusCode().value());
        // ok: java-exception-content-in-logs
        log.info("Job: {}", jobId.toString());
        // ok: java-exception-content-in-logs
        log.atWarn().addKeyValue("type", failure.getClass().getSimpleName()).log("Request failed");
        // ok: java-exception-content-in-logs
        return response.getResponseBodyAsString();
    }

    void classificationOnly(Exception failure) {
        String detail = failure.getMessage();
        if (detail != null && detail.contains("already closed")) {
            // ok: java-exception-content-in-logs
            log.debug("Resource was already closed");
        } else {
            // ok: java-exception-content-in-logs
            logger.warn("Resource failed");
        }
    }
}

class GitHubGraphQlLoggingExamples {
    Logger log;

    void structuredDiagnostic(ResponseError error, RestClientResponseException response) {
        // ok: java-exception-content-in-logs
        log.warn("GraphQL message: {}", GitHubExceptionClassifier.graphQlMessageForLog(error));
        // ruleid: java-exception-content-in-logs
        log.warn("GraphQL message: {}", error.getMessage());
        // ruleid: java-exception-content-in-logs
        log.warn("GraphQL message: {}", LoggingUtils.sanitizeForLog(error.getMessage()));
        // ruleid: java-exception-content-in-logs
        log.warn("Body: {}", response.getResponseBodyAsString());
    }
}
