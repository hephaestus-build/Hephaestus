package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.auth.spi.McpWorkspaceDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Checks every request to a workspace MCP endpoint against its grant (ADR 0110 decision 10): the token's
 * audience is this endpoint, its session holds an active grant for this endpoint, the grant's workspace is
 * the workspace that the slug names now, and the grant holds the scope. A slug that another workspace took
 * over therefore never reaches it with an old token.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
public class McpGrantGuard implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final McpClientGrantRepository grants;
    private final McpWorkspaceDirectory workspaces;
    private final McpResources resources;
    private final McpChallenges challenges;
    private final Clock clock;

    McpGrantGuard(
            McpClientGrantRepository grants,
            McpWorkspaceDirectory workspaces,
            McpResources resources,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.grants = grants;
        this.clock = clock;
        this.workspaces = workspaces;
        this.resources = resources;
        this.challenges = new McpChallenges(resources, meterRegistry);
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        String slug = request.pathVariable("workspaceSlug");
        String resource = resources.resourceFor(slug);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return refuse(slug, HttpStatus.UNAUTHORIZED, "invalid_token", null);
        }
        Optional<McpClientGrant> grant = Optional.ofNullable(jwt.getClaimAsString("sid"))
                .map(McpGrantGuard::uuid)
                .flatMap(sid -> sid.flatMap(grants::findActiveBySession));
        Optional<Long> workspaceId = workspaces.findActiveBySlug(slug).map(McpWorkspaceDirectory.McpWorkspace::id);
        boolean valid = List.of(resource).equals(jwt.getAudience())
                && grant.isPresent()
                && grant.get().getResource().equals(resource)
                && workspaceId.isPresent()
                && workspaceId.get().equals(grant.get().getWorkspaceId());
        if (!valid) {
            return refuse(slug, HttpStatus.UNAUTHORIZED, "invalid_token", null);
        }
        if (!grant.get().grants(McpScope.READ)) {
            return refuse(slug, HttpStatus.FORBIDDEN, "insufficient_scope", McpScope.READ.value());
        }
        // The console shows when a client was last used, so an admin can tell an idle grant from a busy one.
        Instant now = clock.instant();
        grants.markUsed(workspaceId.get(), grant.get().getId(), now, now.minus(McpClientGrant.LAST_USED_PRECISION));
        request.attributes()
                .put(
                        McpCallGrant.ATTRIBUTE,
                        new McpCallGrant(
                                grant.get().getId(),
                                grant.get().grants(McpScope.WRITE),
                                grant.get().getPasskeyVerifiedAt()));
        return next.handle(request);
    }

    /** The answer to a tool call that needs a scope the grant lacks, so the client can ask for it (ADR 0110 decision 14). */
    public ServerResponse insufficientScope(String slug, McpScope scope) {
        return refuse(slug, HttpStatus.FORBIDDEN, "insufficient_scope", scope.value());
    }

    private ServerResponse refuse(String slug, HttpStatus status, String error, @Nullable String scope) {
        challenges.count(error);
        return ServerResponse.status(status)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenges.challenge(slug, error, scope))
                .build();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
