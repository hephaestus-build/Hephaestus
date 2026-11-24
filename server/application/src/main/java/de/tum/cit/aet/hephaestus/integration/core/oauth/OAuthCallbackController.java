package de.tum.cit.aet.hephaestus.integration.core.oauth;

import de.tum.cit.aet.hephaestus.core.exception.MaintenanceInProgressException;
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
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.server.ResponseStatusException;
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

    /**
     * Each failure sends its reason code and a sentence written for this page. An exception message never reaches the
     * browser: a provider or library writes it for developers, and it can name internal values.
     */
    private static final String TRANSITION_CONFLICT =
            "The connection changed while you connected it. Reload the integration page and try again.";

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
            @RequestParam Map<String, String> allParams,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleCallback(kind, allParams.get("state"), allParams.get("error"), allParams, request, response);
    }

    @PostMapping("/{kind}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> callbackPost(
            @PathVariable String kind,
            @RequestParam Map<String, String> allParams,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleCallback(kind, allParams.get("state"), allParams.get("error"), allParams, request, response);
    }

    /** Shared core for GET + POST callbacks. Package-visible for unit tests. */
    ResponseEntity<?> handleCallback(
            String kindPathSegment,
            @Nullable String state,
            @Nullable String vendorError,
            Map<String, String> allParams,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean wantsJson = wantsJson(request);
        if (state != null && !state.isBlank()) {
            // A state is used once, whatever happens next.
            OAuthBrowserBinding.release(response, state);
        }
        Optional<IntegrationKind> kindOpt = kindRouting.resolve(kindPathSegment);
        if (kindOpt.isEmpty()) {
            log.info("OAuth callback for unknown kind path segment: {}", sanitize(kindPathSegment));
            return failure(
                    kindPathSegment,
                    "unknown_kind",
                    "This integration is not available on this instance.",
                    HttpStatus.NOT_FOUND,
                    wantsJson);
        }
        IntegrationKind kind = kindOpt.get();

        if (vendorError != null && !vendorError.isBlank()) {
            // The provider's error and its description arrive in the query, so anyone can write them into a link.
            // The page shows a code and a sentence of its own; the log keeps the provider's code.
            log.info("OAuth callback for kind={} carried a provider error: {}", kind, sanitize(vendorError));
            boolean denied = "access_denied".equals(vendorError);
            var cancelled = failure(
                    kind.name(),
                    denied ? "access_denied" : "provider_error",
                    denied
                            ? "You cancelled the connection. Start it again to connect."
                            : "The provider refused the connection. Start it again.",
                    HttpStatus.BAD_REQUEST,
                    wantsJson);
            // Cancellation needs no state; only a verified binding can supply workspace return context.
            if (kind == IntegrationKind.SLACK
                    && !wantsJson
                    && state != null
                    && !state.isBlank()
                    && OAuthBrowserBinding.startedHere(request, state)) {
                try {
                    var binding = oauthStateService.consume(state);
                    if (binding.kind() == kind) {
                        return workspaceRedirect(cancelled, binding);
                    }
                } catch (IllegalArgumentException ignored) {
                    return cancelled;
                }
            }
            return cancelled;
        }

        if (state == null || state.isBlank()) {
            ConnectionStrategy strategy = strategies.get(kind);
            Optional<String> landing = strategy == null
                    ? Optional.empty()
                    : strategy.providerInitiatedLanding(allParams == null ? Map.of() : allParams);
            if (landing.isPresent()) {
                return redirect(properties.applicationUrl(landing.get()));
            }
            log.info("OAuth callback for kind={} missing state parameter", kind);
            return failure(
                    kind.name(),
                    "missing_state",
                    "The state parameter is required.",
                    HttpStatus.BAD_REQUEST,
                    wantsJson);
        }
        if (!OAuthBrowserBinding.startedHere(request, state)) {
            // Another browser, a reload of a finished callback, an expired flow and a forged state all end here, so
            // the text names no cause. The state stays usable for the browser that started the flow.
            log.warn("OAuth callback for kind={} arrived without the browser binding of its state", kind);
            return failure(
                    kind.name(),
                    "browser_unconfirmed",
                    "Hephaestus could not confirm that this browser started the connection. Start it again from"
                            + " Hephaestus in this browser.",
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
            return failure(
                    kind.name(),
                    "invalid_state",
                    "This connection link expired or was already used. Start the connection again.",
                    HttpStatus.BAD_REQUEST,
                    wantsJson);
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
                    "This connection link belongs to another integration. Start the connection again.",
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
                    "This integration is not available on this instance.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Connection connection;
        try {
            connection = callbackService.findAttemptConnection(binding);
        } catch (EntityNotFoundException e) {
            return failure(
                    kind.name(),
                    "workspace_not_found",
                    "The workspace no longer exists.",
                    HttpStatus.NOT_FOUND,
                    wantsJson);
        } catch (IllegalStateException superseded) {
            return failure(
                    kind.name(),
                    "superseded",
                    "This connection request was superseded. Start a new connection.",
                    HttpStatus.CONFLICT,
                    wantsJson);
        }
        try {
            callbackService.validateKnownCallbackTarget(connection, allParams == null ? Map.of() : allParams);
        } catch (IllegalStateException | OAuthCallbackService.InstanceConnectedElsewhereException refused) {
            return workspaceRedirect(
                    failure(
                            kind.name(),
                            refused instanceof OAuthCallbackService.InstanceConnectedElsewhereException
                                    ? "connected_elsewhere"
                                    : "transition_conflict",
                            refused instanceof OAuthCallbackService.InstanceConnectedElsewhereException elsewhere
                                    ? elsewhere.getMessage()
                                    : TRANSITION_CONFLICT,
                            HttpStatus.CONFLICT,
                            wantsJson),
                    binding);
        }
        IntegrationRef ref =
                new IntegrationRef(kind, binding.workspaceId(), connection.getInstanceKey(), connection.getId());

        ConnectFinalization result;
        try {
            Map<String, String> callbackParams = new HashMap<>(allParams == null ? Map.of() : allParams);
            callbackParams.remove("state");
            result = strategy.finalizeConnect(ref, callbackParams);
        } catch (MaintenanceInProgressException updating) {
            // The exchange never ran, so nothing reached the provider: the person starts again after the update.
            log.info("OAuth callback for kind={} arrived during a database migration", kind);
            return workspaceRedirect(
                    failure(
                            kind.name(),
                            "maintenance",
                            "Hephaestus was updating, so the connection did not finish. Start it again in a few"
                                    + " minutes.",
                            HttpStatus.SERVICE_UNAVAILABLE,
                            wantsJson),
                    binding);
        } catch (RuntimeException e) {
            log.warn(
                    "Strategy.finalizeConnect threw for kind={} workspace={}: {}",
                    kind,
                    binding.workspaceId(),
                    e.getClass().getSimpleName());
            return workspaceRedirect(
                    failure(
                            kind.name(),
                            "strategy_error",
                            "The provider did not finish the connection. Start it again.",
                            HttpStatus.BAD_REQUEST,
                            wantsJson),
                    binding);
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
        } catch (ResponseStatusException pendingResource) {
            if (pendingResource.getStatusCode().value() != 409) throw pendingResource;
            return failure(
                    kind.name(),
                    "provider_resource_pending",
                    pendingResource.getReason(),
                    HttpStatus.CONFLICT,
                    wantsJson);
        } catch (IllegalStateException e) {
            log.warn(
                    "OAuth complete rejected by transition guard for connection={}: {}",
                    connection.getId(),
                    e.getClass().getSimpleName());
            return failure(kind.name(), "transition_conflict", TRANSITION_CONFLICT, HttpStatus.CONFLICT, wantsJson);
        }
        return redirect(properties.successRedirect());
    }

    private ResponseEntity<?> handleFailed(
            IntegrationKind kind, StateBinding binding, ConnectFinalization.Failed failed, boolean wantsJson) {
        log.warn("OAuth callback failed for kind={} workspace={}: {}", kind, binding.workspaceId(), failed.reason());
        // Each connection strategy writes its failure reasons for the person who connects.
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
    private static boolean wantsJson(HttpServletRequest request) {
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
