package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
@RequestMapping("/internal/llm")
@PreAuthorize("isAuthenticated()")
@ConditionalOnWorkerRole
class LlmProxyController {
    private final LlmProxyService proxyService;

    LlmProxyController(LlmProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping({"/chat/completions", "/responses"})
    @WorkspaceAgnostic("Authenticated sandbox token carries and constrains the workspace route")
    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders incomingHeaders,
            @RequestBody(required = false) byte @Nullable [] body) {
        return proxyService.proxy(request, response, incomingHeaders, body);
    }

    @PostMapping("/precompute/{slot}/**")
    @WorkspaceAgnostic("Authenticated precompute token carries and constrains the workspace route")
    public @Nullable ResponseEntity<?> proxyPrecompute(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader HttpHeaders incomingHeaders,
            @RequestBody(required = false) byte @Nullable [] body) {
        return proxyService.proxyPrecompute(request, response, incomingHeaders, body);
    }
}
