package de.tum.cit.aet.hephaestus.integration.core.webhook;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ahead of the general handlers: a refusal must reach the provider as a retryable answer, whichever controller read. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebhookReceiverBusyAdvice {

    @ExceptionHandler(WebhookReceiverBusyException.class)
    ResponseEntity<ProblemDetail> busy() {
        return WebhookIngestPipeline.busy();
    }

    @ExceptionHandler(WebhookBodyTooSlowException.class)
    ResponseEntity<ProblemDetail> tooSlow() {
        return WebhookIngestPipeline.problem(HttpStatus.REQUEST_TIMEOUT, "too-slow");
    }
}
