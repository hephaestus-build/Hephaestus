package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Installed-client session endpoints. Each authenticates with a secret in the request body — a handoff
 * code with its PKCE verifier, or a refresh secret — and never with a cookie: a browser session must not
 * be exchangeable for a token a script could read, so a request that carries the session cookie is
 * refused before anything else happens.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/auth/client")
@Tag(name = "Client session", description = "Sign-in, refresh and sign-out for installed clients")
@PreAuthorize("permitAll()")
public class ClientSessionController {

    static final String CLIENT_ID_PATTERN = "^[a-p]{32}$";

    /**
     * The CSRF filter answers before this controller: a POST that carries the browser session cookie
     * without a valid CSRF token is refused with Spring Security's plain JSON error, not a problem detail.
     */
    private static final String CSRF_REFUSED =
            "The request carries the browser session cookie without a valid CSRF token; refused before the"
                    + " endpoint runs";

    private static final String TOO_MANY_REQUESTS =
            "Too many installed-client requests from this address; retry after the Retry-After seconds";

    private final ClientSessionService sessionService;
    private final InstalledClientRegistry registry;
    private final String cookieName;

    public ClientSessionController(
            ClientSessionService sessionService, InstalledClientRegistry registry, AuthProperties authProperties) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.cookieName = authProperties.cookieName();
    }

    public record ClientSignInConfigurationDTO(@NonNull Boolean registered) {}

    public record ClientTokenRequestDTO(
            @NotNull @Pattern(regexp = CLIENT_ID_PATTERN) String clientId,
            @NotNull @Size(max = 200) String redirectUri,
            @NotNull @Pattern(regexp = Pkce.SECRET_PATTERN) String code,

            @NotNull @Pattern(regexp = Pkce.VERIFIER_PATTERN)
            String codeVerifier) {}

    public record ClientRefreshRequestDTO(
            @NotNull @Pattern(regexp = Pkce.SECRET_PATTERN) String refreshToken) {}

    public record ClientSessionTokensDTO(
            @NonNull String accessToken,
            @NonNull Instant accessTokenExpiresAt,
            @NonNull String refreshToken,
            @NonNull Instant sessionExpiresAt) {}

    @GetMapping("/configuration")
    @Operation(
            summary = "Whether this instance lets the given installed client sign in",
            description =
                    "Public. A client that finds no such endpoint is talking to a server without installed-client sign-in.",
            operationId = "getClientSignInConfiguration")
    @ApiResponse(
            responseCode = "200",
            description = "Whether the client is registered",
            content = @Content(schema = @Schema(implementation = ClientSignInConfigurationDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The client id is not a Chrome extension id",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ClientSignInConfigurationDTO> configuration(
            @RequestParam("clientId") @Pattern(regexp = CLIENT_ID_PATTERN) String clientId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClientSignInConfigurationDTO(registry.isRegistered(clientId)));
    }

    @PostMapping("/token")
    @Operation(summary = "Redeem a sign-in handoff code with its PKCE verifier", operationId = "exchangeClientSignIn")
    @ApiResponse(
            responseCode = "200",
            description = "Signed in",
            content = @Content(schema = @Schema(implementation = ClientSessionTokensDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The body is malformed, the code is unknown, expired, used, or does not match the verifier,"
                    + " client or callback, or a request with a valid CSRF token also carries the browser session"
                    + " cookie",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = CSRF_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @ApiResponse(
            responseCode = "429",
            description = TOO_MANY_REQUESTS,
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ClientSessionTokensDTO> token(
            @Valid @RequestBody ClientTokenRequestDTO body, HttpServletRequest request) {
        refuseCookieSession(request);
        return sessionService
                .exchange(body.clientId(), body.redirectUri(), body.code(), body.codeVerifier(), request)
                .map(ClientSessionController::tokens)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sign-in could not be completed"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the session's access token and refresh secret", operationId = "refreshClientSession")
    @ApiResponse(
            responseCode = "200",
            description = "Rotated",
            content = @Content(schema = @Schema(implementation = ClientSessionTokensDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The body is malformed, or a request with a valid CSRF token also carries the browser"
                    + " session cookie",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "401",
            description = "The session has ended; sign in again",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = CSRF_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @ApiResponse(
            responseCode = "429",
            description = TOO_MANY_REQUESTS,
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ClientSessionTokensDTO> refresh(
            @Valid @RequestBody ClientRefreshRequestDTO body, HttpServletRequest request) {
        refuseCookieSession(request);
        return sessionService
                .refresh(body.refreshToken(), request)
                .map(ClientSessionController::tokens)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The session has ended"));
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session its refresh secret belongs to", operationId = "logoutClientSession")
    @ApiResponse(responseCode = "204", description = "Signed out, or the secret was already unknown")
    @ApiResponse(
            responseCode = "400",
            description = "The body is malformed, or a request with a valid CSRF token also carries the browser"
                    + " session cookie",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = CSRF_REFUSED,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @ApiResponse(
            responseCode = "429",
            description = TOO_MANY_REQUESTS,
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> logout(@Valid @RequestBody ClientRefreshRequestDTO body, HttpServletRequest request) {
        refuseCookieSession(request);
        sessionService.logout(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private void refuseCookieSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A browser session cannot be used for an installed-client sign-in");
            }
        }
    }

    private static ResponseEntity<ClientSessionTokensDTO> tokens(ClientSessionService.ClientTokens tokens) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ClientSessionTokensDTO(
                        tokens.accessToken(),
                        tokens.accessTokenExpiresAt(),
                        tokens.refreshToken(),
                        tokens.sessionExpiresAt()));
    }
}
