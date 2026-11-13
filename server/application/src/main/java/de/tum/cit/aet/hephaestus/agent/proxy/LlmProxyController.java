package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model routes of the sandbox gateway. A {@code review_compose} run's request goes on only when it continues the
 * claim's conversation as the {@link ComposerTranscript} allows it, and the reply and tool calls of its answer go into
 * that transcript before the answer leaves.
 */
@RestController
@RequestMapping("/internal/llm")
@ConditionalOnAgentRuntime
class LlmProxyController {
    private static final Logger log = LoggerFactory.getLogger(LlmProxyController.class);
    private static final String PREFIX = "/internal/llm";

    private final LlmProxyService proxyService;
    private final ComposerTranscript transcript;
    private final ProxyAccounting accounting;

    LlmProxyController(LlmProxyService proxyService, ComposerTranscript transcript, ProxyAccounting accounting) {
        this.proxyService = proxyService;
        this.transcript = transcript;
        this.accounting = accounting;
    }

    @PostMapping({"/chat/completions", "/responses", "/v1/messages"})
    @WorkspaceAgnostic("Authenticated sandbox token carries and constrains the workspace route")
    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders incomingHeaders,
            @RequestBody(required = false) byte @Nullable [] body) {
        ProxyAttempt composing = composing();
        Optional<LlmApiProtocol> protocol = protocol(request);
        ComposerTranscript.Forward forward = null;
        if (composing != null && protocol.isPresent() && body != null) {
            switch (transcript.check(
                    composing.workspaceId(), composing.runId(), composing.epoch(), protocol.get(), body)) {
                case ComposerTranscript.Refuse refuse -> {
                    accounting.refused(ProxyRefusal.NOT_COMPOSER_REQUEST);
                    log.warn(
                            "Refused a model request that does not continue the composer's conversation: runId={}, reason={}",
                            composing.runId(),
                            refuse.reason());
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .header(LlmProxyService.REFUSAL_HEADER, ProxyRefusal.NOT_COMPOSER_REQUEST.name())
                            .body("This request does not continue this composition's conversation: " + refuse.reason());
                }
                case ComposerTranscript.Forward found -> forward = found;
            }
        }
        var answer = proxyService.proxy(request, response, incomingHeaders, body);
        // A streamed answer has no body here and records nothing; the composer never streams.
        if (composing != null
                && protocol.isPresent()
                && forward != null
                && answer != null
                && answer.getStatusCode().is2xxSuccessful()
                && answer.getBody() instanceof byte[] served) {
            try {
                transcript.served(
                        composing.workspaceId(), composing.runId(), composing.epoch(), protocol.get(), forward, served);
            } catch (RuntimeException unrecorded) {
                // An answer that the transcript does not hold could not be continued; the composer asks again instead.
                log.warn("Could not record a composer answer: runId={}", composing.runId(), unrecorded);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        return answer;
    }

    /** The review attempt of the caller when its run composes. */
    private @Nullable ProxyAttempt composing() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JobTokenAuthentication token))
            return null;
        ProxyAttempt job = token.getPrincipal().job();
        return job != null && transcript.composes(job.workspaceId(), job.runId()) ? job : null;
    }

    private static Optional<LlmApiProtocol> protocol(HttpServletRequest request) {
        return Arrays.stream(LlmApiProtocol.values())
                .filter(protocol -> (PREFIX + protocol.upstreamPath()).equals(request.getRequestURI()))
                .findFirst();
    }
}
