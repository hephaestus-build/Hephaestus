package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.mcp.dto.McpConsentRedirectDTO;
import de.tum.cit.aet.hephaestus.core.auth.mcp.dto.McpConsentRequestDTO;
import de.tum.cit.aet.hephaestus.core.auth.mcp.dto.McpTokenErrorDTO;
import de.tum.cit.aet.hephaestus.core.auth.mcp.dto.McpTokenResponseDTO;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The authorization and token endpoints of the workspace MCP endpoint (ADR 0110 decisions 6 to 12). The
 * authorization endpoint only redirects; the web app's {@code /mcp/authorize} page signs the account in and
 * shows the consent request.
 */
@RestController
@RequestMapping("/mcp/oauth")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
@Tag(name = "MCP authorization", description = "OAuth endpoints and consent for MCP clients")
public class McpAuthorizationController {

    private static final String CONSENT_PAGE = "/mcp/authorize";

    private final McpAuthorizationService authorization;
    private final String webappUrl;
    private final Clock clock;

    McpAuthorizationController(
            McpAuthorizationService authorization, @Value("${hephaestus.webapp.url:}") String webappUrl, Clock clock) {
        this.authorization = authorization;
        this.webappUrl = webappUrl;
        this.clock = clock;
    }

    @GetMapping("/authorize")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Start an MCP client authorization (RFC 6749 § 4.1.1)", operationId = "authorizeMcpClient")
    public ResponseEntity<Void> authorize(
            @RequestParam(name = "response_type", required = false) @Nullable String responseType,
            @RequestParam(name = "client_id", required = false) @Nullable String clientId,
            @RequestParam(name = "redirect_uri", required = false) @Nullable String redirectUri,
            @RequestParam(name = "code_challenge", required = false) @Nullable String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) @Nullable String codeChallengeMethod,
            @RequestParam(name = "state", required = false) @Nullable String state,
            @RequestParam(name = "resource", required = false) @Nullable String resource,
            @RequestParam(name = "scope", required = false) @Nullable String scope,
            @RequestParam(name = "prompt", required = false) @Nullable String prompt,
            HttpServletRequest request) {
        var parameters = new McpAuthorizationService.AuthorizationRequest(
                responseType,
                clientId,
                redirectUri,
                codeChallenge,
                codeChallengeMethod,
                state,
                resource,
                scope,
                prompt);
        // Only the browser session approves; an installed client's bearer token never signs in here.
        Long accountId = CurrentAccount.sessionIdOrNull() == null ? CurrentAccount.idOrNull() : null;
        String location = switch (authorization.authorize(parameters, accountId)) {
            case McpAuthorizationService.Outcome.ErrorPage page -> webappUrl + CONSENT_PAGE + "?error=" + page.reason();
            case McpAuthorizationService.Outcome.SignIn ignored ->
                webappUrl + CONSENT_PAGE + "?" + request.getQueryString();
            case McpAuthorizationService.Outcome.ClientRedirect redirect -> redirect.location();
            case McpAuthorizationService.Outcome.Consent consent ->
                webappUrl + CONSENT_PAGE + "?request=" + consent.requestId();
        };
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/requests/{requestId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Read a pending MCP client consent request", operationId = "getMcpConsentRequest")
    public ResponseEntity<McpConsentRequestDTO> consentRequest(@PathVariable UUID requestId) {
        return authorization
                .consentRequest(requestId, browserAccount())
                .map(found -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new McpConsentRequestDTO(
                                found.id(),
                                found.clientHost(),
                                found.clientName(),
                                found.redirectHost(),
                                found.loopbackRedirect(),
                                found.workspaceSlug(),
                                found.workspaceName(),
                                found.scopes(),
                                found.refusal() == null
                                        ? null
                                        : McpConsentRequestDTO.McpConsentRefusal.valueOf(
                                                found.refusal().name()),
                                found.passkeyRequired())))
                .orElseThrow(McpAuthorizationController::gone);
    }

    @PutMapping("/requests/{requestId}/approval")
    @PreAuthorize("isAuthenticated()")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "MCP_CLIENT_APPROVED")
    @Operation(summary = "Approve an MCP client for a workspace", operationId = "approveMcpConsentRequest")
    public ResponseEntity<McpConsentRedirectDTO> approve(@PathVariable UUID requestId) {
        Long accountId = browserAccount();
        Instant authTime = CurrentAccount.authTime();
        if (authTime == null) {
            // The client's session records the sign-in that approved it.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to approve the client.");
        }
        return switch (authorization.approve(
                requestId,
                accountId,
                new McpAuthorizationService.ApprovingSession(
                        CurrentAccount.requireJti(),
                        authTime,
                        CurrentAccount.passkeyTime(),
                        CurrentAccount.passkeyCredential()))) {
            case McpAuthorizationService.Approval.Approved approved ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new McpConsentRedirectDTO(approved.location()));
            case McpAuthorizationService.Approval.NotAllowed notAllowed ->
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This account cannot approve the client: "
                                + notAllowed.refusal().name());
            case McpAuthorizationService.Approval.PasskeyRequired ignored ->
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Confirm with your passkey, then approve the client.");
            case McpAuthorizationService.Approval.Gone ignored -> throw gone();
        };
    }

    @DeleteMapping("/requests/{requestId}")
    @PreAuthorize("isAuthenticated()")
    @RecentSignInExempt(reason = "refuses a pending client request; nothing is removed and the client can ask again")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "MCP_CLIENT_REFUSED")
    @Operation(summary = "Refuse an MCP client", operationId = "refuseMcpConsentRequest")
    public ResponseEntity<McpConsentRedirectDTO> refuse(@PathVariable UUID requestId) {
        return authorization
                .refuse(requestId, browserAccount())
                .map(location -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new McpConsentRedirectDTO(location)))
                .orElseThrow(McpAuthorizationController::gone);
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "Exchange a code or rotate a refresh token (RFC 6749 § 3.2)", operationId = "mcpToken")
    public ResponseEntity<?> token(
            @RequestParam(name = "grant_type", required = false) @Nullable String grantType,
            @RequestParam(name = "code", required = false) @Nullable String code,
            @RequestParam(name = "redirect_uri", required = false) @Nullable String redirectUri,
            @RequestParam(name = "client_id", required = false) @Nullable String clientId,
            @RequestParam(name = "code_verifier", required = false) @Nullable String codeVerifier,
            @RequestParam(name = "refresh_token", required = false) @Nullable String refreshToken,
            @RequestParam(name = "resource", required = false) @Nullable String resource,
            @RequestParam(name = "scope", required = false) @Nullable String scope,
            HttpServletRequest request) {
        var parameters = new McpAuthorizationService.TokenRequest(
                grantType, code, redirectUri, clientId, codeVerifier, refreshToken, resource, scope);
        return switch (authorization.token(parameters, request)) {
            case McpAuthorizationService.TokenOutcome.Issued issued ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header("Pragma", "no-cache")
                        .body(new McpTokenResponseDTO(
                                issued.tokens().accessToken(),
                                "Bearer",
                                Math.max(
                                        0,
                                        Duration.between(
                                                        clock.instant(),
                                                        issued.tokens().accessTokenExpiresAt())
                                                .toSeconds()),
                                issued.tokens().refreshToken(),
                                issued.scope()));
            case McpAuthorizationService.TokenOutcome.Refused refused ->
                ResponseEntity.badRequest()
                        .cacheControl(CacheControl.noStore())
                        .header("Pragma", "no-cache")
                        .body(new McpTokenErrorDTO(refused.error()));
        };
    }

    /** The signed-in browser account; an installed-client token or a user view never decides consent. */
    private static Long browserAccount() {
        if (CurrentAccount.sessionIdOrNull() != null || UserViewContextHolder.get() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Consent is given in the web app only.");
        }
        return CurrentAccount.requireId();
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "This request was answered, has expired, or is not yours.");
    }
}
