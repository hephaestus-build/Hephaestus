package de.tum.cit.aet.hephaestus.core.auth.nativesession;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The native app's session endpoints. Each authenticates with a secret in the request body — a handoff
 * code with its PKCE verifier, or a refresh secret — and never with a cookie: a browser session must not
 * be exchangeable for a token a script could read, so a request that carries the session cookie is
 * refused before anything else happens.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/auth/native")
@Tag(name = "Native session", description = "Sign-in, refresh and sign-out for the native app")
@PreAuthorize("permitAll()")
public class NativeSessionController {

    private final NativeSessionService sessionService;
    private final NativeClientProperties properties;
    private final String cookieName;
    private final String webappUrl;

    public NativeSessionController(
            NativeSessionService sessionService,
            NativeClientProperties properties,
            AuthProperties authProperties,
            @Value("${hephaestus.webapp.url}") String webappUrl) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.cookieName = authProperties.cookieName();
        this.webappUrl = webappUrl;
    }

    public record NativeClientConfigurationDTO(
            @NonNull String minimumAppVersion,

            @NonNull
            @Schema(description = "Web application address for privacy, account export and browser-only workflows")
            String webappUrl) {}

    public record NativeTokenRequestDTO(
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Size(min = 43, max = 128) String codeVerifier) {}

    public record NativeRefreshRequestDTO(
            @NotBlank @Size(max = 128) String refreshToken) {}

    public record NativeSessionTokensDTO(
            @NonNull String accessToken,
            @NonNull Instant accessTokenExpiresAt,
            @NonNull String refreshToken,
            @NonNull Instant sessionExpiresAt,
            @NonNull UUID nativeSessionId) {}

    @GetMapping("/configuration")
    @Operation(
            summary = "What this server requires of the native app",
            description = "Public. An app that finds no such endpoint is talking to a server without native sign-in.",
            operationId = "getNativeClientConfiguration")
    public NativeClientConfigurationDTO configuration() {
        return new NativeClientConfigurationDTO(properties.minimumAppVersion(), webappUrl);
    }

    @PostMapping("/token")
    @Operation(summary = "Redeem a sign-in handoff code with its PKCE verifier", operationId = "exchangeNativeSignIn")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(
            responseCode = "400",
            description = "The code is unknown, expired, used, or does not match the verifier")
    public ResponseEntity<NativeSessionTokensDTO> token(
            @Valid @RequestBody NativeTokenRequestDTO body, HttpServletRequest request) {
        refuseCookieSession(request);
        return sessionService
                .exchange(body.code(), body.codeVerifier(), request)
                .map(NativeSessionController::tokens)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sign-in could not be completed"));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate the native session's access token and refresh secret",
            operationId = "refreshNativeSession")
    @ApiResponse(responseCode = "200", description = "Rotated")
    @ApiResponse(
            responseCode = "401",
            description = "The session has ended; sign in again",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<NativeSessionTokensDTO> refresh(
            @Valid @RequestBody NativeRefreshRequestDTO body, HttpServletRequest request) {
        refuseCookieSession(request);
        return sessionService
                .refresh(body.refreshToken(), request)
                .map(NativeSessionController::tokens)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The session has ended"));
    }

    @PostMapping("/logout")
    @Operation(summary = "End the native session its refresh secret belongs to", operationId = "logoutNativeSession")
    @ApiResponse(responseCode = "204", description = "Signed out, or the secret was already unknown")
    public ResponseEntity<Void> logout(@Valid @RequestBody NativeRefreshRequestDTO body, HttpServletRequest request) {
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
            if (cookieName.equals(cookie.getName())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A browser session cannot be used for a native sign-in");
            }
        }
    }

    private static ResponseEntity<NativeSessionTokensDTO> tokens(NativeSessionService.NativeTokens tokens) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new NativeSessionTokensDTO(
                        tokens.accessToken(),
                        tokens.accessTokenExpiresAt(),
                        tokens.refreshToken(),
                        tokens.sessionExpiresAt(),
                        tokens.nativeSessionId()));
    }
}
