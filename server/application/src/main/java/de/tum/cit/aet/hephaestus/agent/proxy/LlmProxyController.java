package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/llm")
@ConditionalOnAgentRuntime
class LlmProxyController {
    private final LlmProxyService proxyService;

    LlmProxyController(LlmProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping({"/chat/completions", "/responses", "/v1/messages"})
    @WorkspaceAgnostic("Authenticated sandbox token carries and constrains the workspace route")
    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders incomingHeaders,
            @RequestBody(required = false) byte @Nullable [] body) {
        return proxyService.proxy(request, response, incomingHeaders, body);
    }
}
