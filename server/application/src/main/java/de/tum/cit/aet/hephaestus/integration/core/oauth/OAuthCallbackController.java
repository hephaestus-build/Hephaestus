package de.tum.cit.aet.hephaestus.integration.core.oauth;

import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService.StateBinding;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.webhook.IntegrationKindRouting;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Vendor OAuth redirect ingress at {@code /oauth/callback/{kind}}. Unauthenticated —
 * identity comes from the HMAC-signed {@code state} param. Browsers get 302 redirects
 * on both success and failure; only {@code Accept: application/json} requests get
 * 4xx JSON.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/oauth/callback")
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    private final IntegrationKindRouting kindRouting;
    private final OAuthStateService oauthStateService;
    private final OAuthCallbackService callbackService;
    private final Map<IntegrationKind, ConnectionStrategy> strategies;
    private final OAuthCallbackProperties properties;

    public OAuthCallbackController(
            IntegrationKindRouting kindRouting,
            OAuthStateService oauthStateService,
            OAuthCallbackService callbackService,
            List<ConnectionStrategy> strategyBeans,
            OAuthCallbackProperties properties) {
        this.kindRouting = kindRouting;
        this.oauthStateService = oauthStateService;
        this.callbackService = callbackService;
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toUnmodifiableMap(ConnectionStrategy::kind, s -> s, (a, b) -> {
                    throw new IllegalStateException("Duplicate ConnectionStrategy for kind=" + a.kind()
                            + ": "
                            + a.getClass()
                            + " vs "
                            + b.getClass());
                }));
        this.properties = properties;
    }

    @GetMapping("/{kind}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> callbackGet(
            @PathVariable String kind,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "error", required = false) @Nullable String error,
            @RequestParam(value = "error_description", required = false) @Nullable String errorDescription,
            @RequestParam Map<String, String> allParams,
            HttpServletRequest request) {
        return handleCallback(kind, state, error, errorDescription, allParams, wantsJson(request));
    }

    @PostMapping("/{kind}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> callbackPost(
            @PathVariable String kind,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "error", required = false) @Nullable String error,
            @RequestParam(value = "error_description", required = false) @Nullable String errorDescription,
            @RequestParam Map<String, String> allParams,
            HttpServletRequest request) {
        return handleCallback(kind, state, error, errorDescription, allParams, wantsJson(request));
    }

    /** Shared core for GET + POST callbacks. Package-visible for unit tests. */
    ResponseEntity<?> handleCallback(
            String kindPathSegment,
            @Nullable String state,
            @Nullable String vendorError,
            @Nullable String vendorErrorDescription,
            Map<String, String> allParams,
            boolean wantsJson) {
        Optional<IntegrationKind> kindOpt = kindRouting.resolve(kindPathSegment);
        if (kindOpt.isEmpty()) {
            log.info("OAuth callback for unknown kind path segment: {}", sanitize(kindPathSegment));
            return failure(
                    kindPathSegment,
                    "unknown_kind",
                    "No integration is registered for the path segment: " + sanitize(kindPathSegment),
                    HttpStatus.NOT_FOUND,
                    wantsJson);
        }
        IntegrationKind kind = kindOpt.get();

        if (vendorError != null && !vendorError.isBlank()) {
            var response = failure(kind.name(), vendorError, vendorErrorDescription, HttpStatus.BAD_REQUEST, wantsJson);
            // Cancellation needs no state; only a verified binding can supply workspace return context.
            if (kind == IntegrationKind.SLACK && !wantsJson && state != null && !state.isBlank()) {
                try {
                    var binding = oauthStateService.consume(state);
                    if (binding.kind() == kind) {
                        return workspaceRedirect(response, binding);
                    }
                } catch (IllegalArgumentException ignored) {
                    return response;
                }
            }
            return response;
        }

        if (state == null || state.isBlank()) {
            ConnectionStrategy strategy = strategies.get(kind);
            if (strategy != null && strategy.isProviderInitiated(allParams == null ? Map.of() : allParams)) {
                return redirect(properties.homeRedirect());
            }
            log.info("OAuth callback for kind={} missing state parameter", kind);
            return failure(
                    kind.name(),
                    "missing_state",
                    "The state parameter is required.",
                    HttpStatus.BAD_REQUEST,
                    wantsJson);
        }
        StateBinding binding;
        try {
            binding = oauthStateService.consume(state);
        } catch (IllegalArgumentException e) {
            // The response explains the rejection; the log keeps the kind and failure class only.
            log.warn(
                    "OAuth callback for kind={} rejected state: {}",
                    kind,
                    e.getClass().getSimpleName());
            return failure(kind.name(), "invalid_state", e.getMessage(), HttpStatus.BAD_REQUEST, wantsJson);
        }

        if (binding.kind() != kind) {
            log.warn(
                    "OAuth state-kind mismatch: path={} stateKind={} workspace={}",
                    kind,
                    binding.kind(),
                    binding.workspaceId());
            return failure(
                    kind.name(),
                    "kind_mismatch",
                    "The state was issued for kind=" + binding.kind()
                            + ". The request replayed it against the callback path /oauth/callback/"
                            + kindPathSegment + ".",
                    HttpStatus.BAD_REQUEST,
                    wantsJson);
        }

        ConnectionStrategy strategy = strategies.get(kind);
        if (strategy == null) {
            log.error("No ConnectionStrategy bean for kind={} but routing accepted it — wiring bug", kind);
            // 500 is a server-side wiring bug — always problem+json, no point redirecting the user
            // to a broken flow they'll just retry.
            return problemDetail(
                    "no_strategy",
                    "No ConnectionStrategy registered for kind=" + kind,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Connection connection;
        try {
            connection = callbackService.findOrCreatePendingConnection(binding.workspaceId(), kind);
        } catch (EntityNotFoundException e) {
            return failure(
                    kind.name(),
                    "workspace_not_found",
                    "The workspace no longer exists.",
                    HttpStatus.NOT_FOUND,
                    wantsJson);
        }
        IntegrationRef ref = connection.toRef();

        ConnectFinalization result;
        try {
            Map<String, String> callbackParams = new HashMap<>(allParams == null ? Map.of() : allParams);
            callbackParams.remove("state");
            result = strategy.finalizeConnect(ref, callbackParams);
        } catch (RuntimeException e) {
            log.warn(
                    "Strategy.finalizeConnect threw for kind={} workspace={}: {}",
                    kind,
                    binding.workspaceId(),
                    e.getClass().getSimpleName());
            return workspaceRedirect(
                    failure(kind.name(), "strategy_error", e.getMessage(), HttpStatus.BAD_REQUEST, wantsJson), binding);
        }

        return workspaceRedirect(
                switch (result) {
                    case ConnectFinalization.Completed c -> handleCompleted(connection, c, binding, kind, wantsJson);
                    case ConnectFinalization.Failed f -> handleFailed(kind, binding, f, wantsJson);
                },
                binding);
    }

    private ResponseEntity<?> handleCompleted(
            Connection connection,
            ConnectFinalization.Completed completed,
            StateBinding binding,
            IntegrationKind kind,
            boolean wantsJson) {
        try {
            callbackService.completeConnection(connection, completed, binding.actorAccountId());
        } catch (OAuthCallbackService.InstanceConnectedElsewhereException e) {
            return failure(kind.name(), "connected_elsewhere", e.getMessage(), HttpStatus.CONFLICT, wantsJson);
        } catch (IllegalStateException e) {
            log.warn(
                    "OAuth complete rejected by transition guard for connection={}: {}",
                    connection.getId(),
                    e.getClass().getSimpleName());
            return failure(kind.name(), "transition_conflict", e.getMessage(), HttpStatus.CONFLICT, wantsJson);
        }
        return redirect(properties.successRedirect());
    }

    private ResponseEntity<?> handleFailed(
            IntegrationKind kind, StateBinding binding, ConnectFinalization.Failed failed, boolean wantsJson) {
        log.warn("OAuth callback failed for kind={} workspace={}: {}", kind, binding.workspaceId(), failed.reason());
        return failure(kind.name(), "finalize_failed", failed.reason(), HttpStatus.BAD_REQUEST, wantsJson);
    }

    /**
     * Browser path: 302 to {@code failureRedirect} with {@code status|reason|kind} query params.
     * JSON path ({@code Accept: application/json}): an RFC-7807 {@code application/problem+json} body
     * ({@code detail} = description, {@code callbackError} member) with the given
     * status so curl / devtools / E2E suites surface the failure clearly.
     */
    private ResponseEntity<?> failure(
            @Nullable String kind,
            String error,
            @Nullable String description,
            HttpStatus jsonStatus,
            boolean wantsJson) {
        if (wantsJson) {
            return problemDetail(error, description, jsonStatus);
        }
        return redirect(buildFailureRedirect(kind, error, description));
    }

    private String buildFailureRedirect(@Nullable String kind, String error, @Nullable String description) {
        String base = properties.resolvedFailureRedirect();
        StringBuilder sb = new StringBuilder(base);
        sb.append(base.indexOf('?') < 0 ? '?' : '&');
        sb.append("reason=").append(URLEncoder.encode(error, StandardCharsets.UTF_8));
        if (description != null) {
            sb.append("&description=").append(URLEncoder.encode(description, StandardCharsets.UTF_8));
        }
        if (kind != null) {
            sb.append("&kind=").append(URLEncoder.encode(kind, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private ResponseEntity<?> workspaceRedirect(ResponseEntity<?> response, StateBinding binding) {
        var location = response.getHeaders().getLocation();
        if (location == null || binding.kind() != IntegrationKind.SLACK) {
            return response;
        }
        String slug;
        try {
            slug = callbackService.workspaceSlug(binding.workspaceId());
        } catch (EntityNotFoundException e) {
            return failure(
                    binding.kind().name(),
                    "workspace_not_found",
                    "The workspace no longer exists.",
                    HttpStatus.NOT_FOUND,
                    false);
        }
        String target = UriComponentsBuilder.fromUri(location)
                .queryParam("workspaceSlug", slug)
                .build(true)
                .toUriString();
        return redirect(target);
    }

    private ResponseEntity<?> redirect(String location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(location));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /** True iff the caller wants JSON (curl/devtools): {@code Accept} contains application/json AND not text/html. */
    private static boolean wantsJson(@Nullable HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null) return false;
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") && !lower.contains("text/html");
    }

    /**
     * RFC-7807 {@code application/problem+json} body for an OAuth-callback failure, matching the
     * project's central error-handling contract. {@code callbackError} names the failure as data, with
     * the same value as the browser redirect's {@code reason}.
     */
    private static ResponseEntity<ProblemDetail> problemDetail(
            String error, @Nullable String description, HttpStatus status) {
        ProblemDetail pd = ProblemType.OAUTH_CALLBACK_FAILED.problem(
                status, "OAuth callback failed", description != null ? description : error);
        pd.setProperty("callbackError", error);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Nullable
    private static String sanitize(@Nullable String input) {
        if (input == null) return null;
        String stripped = input.replaceAll("[\\p{Cntrl}]", "_");
        return stripped.length() > 200 ? stripped.substring(0, 200) + "…" : stripped;
    }
}
