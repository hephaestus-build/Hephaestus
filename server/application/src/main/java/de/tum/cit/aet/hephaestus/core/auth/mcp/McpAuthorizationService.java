package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionService;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInHandoffRepository;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClient;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientKind;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.Pkce;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.McpClientGrants;
import de.tum.cit.aet.hephaestus.core.auth.spi.McpWorkspaceDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorization endpoint's decisions and the consent flow (ADR 0110 decisions 6 to 9). The token
 * endpoint's exchange and refresh stay in {@link ClientSessionService}.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
public class McpAuthorizationService implements McpClientGrants {

    /** How long a consent request may wait for its answer. */
    static final Duration CONSENT_TTL = Duration.ofMinutes(10);

    /** The parameters of an authorization request, as the client sent them. */
    public record AuthorizationRequest(
            @Nullable String responseType,
            @Nullable String clientId,
            @Nullable String redirectUri,
            @Nullable String codeChallenge,
            @Nullable String codeChallengeMethod,
            @Nullable String state,
            @Nullable String resource,
            @Nullable String scope,
            @Nullable String prompt) {}

    /** Where the authorization endpoint sends the browser. */
    public sealed interface Outcome {
        /** The client or its redirect cannot be trusted, so nothing is redirected (RFC 9700 § 4.11). */
        record ErrorPage(ErrorPageReason reason) implements Outcome {}

        /** The browser signs in first and comes back to the same request. */
        record SignIn() implements Outcome {}

        /** A redirect to the client: an error, or the answer to a consent decision. */
        record ClientRedirect(String location) implements Outcome {}

        /** The web app's consent page shows this pending request. */
        record Consent(UUID requestId) implements Outcome {}
    }

    /** Why the authorization endpoint shows an error page instead of answering the client. */
    public enum ErrorPageReason {
        INVALID_CLIENT,
        INVALID_CLIENT_METADATA,
        INVALID_REDIRECT_URI,
    }

    /** Why consent cannot be given, shown on the consent page. */
    public enum Refusal {
        NOT_ADMIN,
        /** The account's passkey recovery is pending; it finishes recovery in the web app first. */
        PASSKEY_RECOVERY_PENDING,
        /** The instance requires a passkey of its instance admins, and that proof is never handed to a client. */
        INSTANCE_ADMIN_PASSKEY_REQUIRED,
    }

    /** What the consent page shows. */
    public record ConsentRequest(
            UUID id,
            String clientHost,
            String clientName,
            String redirectHost,
            boolean loopbackRedirect,
            String workspaceSlug,
            String workspaceName,
            List<String> scopes,
            @Nullable Refusal refusal,
            boolean passkeyRequired) {
        public ConsentRequest {
            scopes = List.copyOf(scopes);
        }
    }

    private final ClientMetadataResolver clients;
    private final McpResources resources;
    private final McpWorkspaceDirectory workspaces;
    private final AdminPasskeyAccess passkeys;
    private final McpClientGrantRepository grants;
    private final ClientSessionService sessions;
    private final AccountRepository accounts;
    private final IssuedJwtRepository issuedJwts;
    private final ClientSignInHandoffRepository handoffs;
    private final AuthEventLogger authEvents;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    McpAuthorizationService(
            ClientMetadataResolver clients,
            McpResources resources,
            McpWorkspaceDirectory workspaces,
            AdminPasskeyAccess passkeys,
            McpClientGrantRepository grants,
            ClientSessionService sessions,
            AccountRepository accounts,
            IssuedJwtRepository issuedJwts,
            ClientSignInHandoffRepository handoffs,
            AuthEventLogger authEvents,
            JsonMapper jsonMapper,
            Clock clock) {
        this.clients = clients;
        this.resources = resources;
        this.workspaces = workspaces;
        this.passkeys = passkeys;
        this.grants = grants;
        this.sessions = sessions;
        this.accounts = accounts;
        this.issuedJwts = issuedJwts;
        this.handoffs = handoffs;
        this.authEvents = authEvents;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Checks an authorization request in the fixed order of decision 6: the form of {@code client_id}, then
     * sign-in, then the metadata document and the redirect, and only then the parameters whose errors may
     * be redirected to the client. No transaction spans it: the metadata document fetch may take seconds, and
     * the one write, the pending grant, is a single save.
     */
    public Outcome authorize(AuthorizationRequest request, @Nullable Long accountId) {
        String clientIdParameter = request.clientId();
        Optional<URI> clientId =
                clientIdParameter == null ? Optional.empty() : ClientMetadataResolver.clientIdUrl(clientIdParameter);
        if (clientId.isEmpty()) {
            return new Outcome.ErrorPage(ErrorPageReason.INVALID_CLIENT);
        }
        if (accountId == null) {
            return new Outcome.SignIn();
        }
        Optional<ClientMetadataResolver.ClientMetadata> client = clients.resolve(clientId.get());
        if (client.isEmpty()) {
            return new Outcome.ErrorPage(ErrorPageReason.INVALID_CLIENT_METADATA);
        }
        String redirectUri = request.redirectUri();
        if (redirectUri == null || !ClientMetadataResolver.redirectMatches(client.get(), redirectUri)) {
            return new Outcome.ErrorPage(ErrorPageReason.INVALID_REDIRECT_URI);
        }
        String state = request.state();
        if (!"code".equals(request.responseType())) {
            return redirectError(redirectUri, "unsupported_response_type", state);
        }
        String challenge = request.codeChallenge();
        if (challenge == null || !"S256".equals(request.codeChallengeMethod()) || !Pkce.isChallenge(challenge)) {
            return redirectError(redirectUri, "invalid_request", state);
        }
        Optional<McpWorkspaceDirectory.McpWorkspace> workspace = Optional.ofNullable(request.resource())
                .flatMap(resources::slugOf)
                .flatMap(workspaces::findActiveBySlug)
                .filter(found -> workspaces.hasMembership(accountId, found.id()));
        if (workspace.isEmpty()) {
            // An unknown workspace and one where the account has no membership answer alike.
            return redirectError(redirectUri, "invalid_target", state);
        }
        List<McpScope> scopes = requestedScopes(request.scope());
        if (scopes.isEmpty()) {
            return redirectError(redirectUri, "invalid_scope", state);
        }
        if ("none".equals(request.prompt())) {
            return redirectError(redirectUri, "consent_required", state);
        }
        McpClientGrant grant = grants.save(new McpClientGrant(
                UUID.randomUUID(),
                workspace.get().id(),
                accountId,
                client.get().clientId(),
                client.get().clientName(),
                redirectUri,
                resources.resourceFor(workspace.get().slug()),
                McpScope.format(scopes),
                challenge,
                state,
                clock.instant().plus(CONSENT_TTL)));
        return new Outcome.Consent(grant.getId());
    }

    /** A pending request of this account, for the consent page; empty once it is answered or expired. */
    @Transactional(readOnly = true)
    public Optional<ConsentRequest> consentRequest(UUID id, Long accountId) {
        return grants.findById(id)
                .filter(grant -> isOpenFor(grant, accountId))
                .flatMap(grant -> workspaceOf(grant).map(workspace -> {
                    Decision decision = decision(accountId, workspace);
                    return new ConsentRequest(
                            grant.getId(),
                            URI.create(grant.getClientId()).getHost(),
                            grant.getClientName(),
                            hostOf(grant.getRedirectUri()),
                            isLoopback(grant.getRedirectUri()),
                            workspace.slug(),
                            workspace.displayName(),
                            McpScope.parse(grant.getScopes()).stream()
                                    .map(McpScope::value)
                                    .toList(),
                            decision.refusal(),
                            decision.passkeyRequired());
                }));
    }

    /**
     * The browser session that approves: its token, its sign-in time, and the passkey proof it carries, if any.
     *
     * @param passkeyCredential the credential that the session's passkey proof names
     */
    public record ApprovingSession(
            UUID jti,
            Instant authTime,
            @Nullable Instant passkeyTime,
            @Nullable String passkeyCredential) {}

    /** The answer to an approval. */
    public sealed interface Approval {
        /** The browser goes to the client with the code. */
        record Approved(String location) implements Approval {}

        /** The account cannot approve; the page shows why, and the account can refuse instead. */
        record NotAllowed(Refusal refusal) implements Approval {}

        /** The policy asks for a passkey, and the approving session carries no fresh passkey proof. */
        record PasskeyRequired() implements Approval {}

        /** The request is answered, expired, or not this account's. */
        record Gone() implements Approval {}
    }

    /**
     * Approves a pending request. It holds the account's issuance lock and checks that the approving token
     * is still active, so an approval that races "sign out everywhere" writes no code (ADR 0110 decision 9).
     * Where the passkey policy asks for a passkey, the approving session must carry a fresh passkey proof. The grant
     * records when and with which credential. Any grant's client session lasts {@link McpClientGrant#IDLE_LAPSE}, and each
     * refresh moves that deadline (decision 8).
     */
    @Transactional
    public Approval approve(UUID id, Long accountId, ApprovingSession session) {
        UUID approvingJti = session.jti();
        Instant authTime = session.authTime();
        Instant passkeyTime = session.passkeyTime();
        String passkeyCredential = session.passkeyCredential();
        Instant now = clock.instant();
        if (!accounts.lockForIssuance(accountId)
                || issuedJwts.findActive(approvingJti, now).isEmpty()) {
            return new Approval.Gone();
        }
        McpClientGrant grant = grants.lockById(id).orElse(null);
        if (grant == null || !isOpenFor(grant, accountId)) {
            return new Approval.Gone();
        }
        Optional<McpWorkspaceDirectory.McpWorkspace> workspace = workspaceOf(grant);
        if (workspace.isEmpty()) {
            return new Approval.Gone();
        }
        Decision decision = decision(accountId, workspace.get());
        if (decision.refusal() != null) {
            return new Approval.NotAllowed(decision.refusal());
        }
        if (decision.passkeyRequired()
                && (passkeyTime == null || passkeyCredential == null || !passkeys.freshPasskeyProof())) {
            return new Approval.PasskeyRequired();
        }
        // The grant does not end with the web session that approved it: it lapses after IDLE_LAPSE without a
        // refresh, and each refresh moves that deadline (decision 8, update of 2026-11-17).
        Instant clientSessionExpiresAt = now.plus(McpClientGrant.IDLE_LAPSE);
        InstalledClient client = new InstalledClient(
                InstalledClientKind.MCP_CLIENT,
                grant.getClientId(),
                grant.getRedirectUri(),
                originOf(grant.getClientId()));
        Optional<String> code = sessions.createHandoff(
                accountId, client, grant.getCodeChallenge(), clientSessionExpiresAt, authTime, grant.getId());
        if (code.isEmpty()) {
            return new Approval.Gone();
        }
        if (decision.passkeyRequired()) {
            grant.approveWithPasskey(
                    now, Objects.requireNonNull(passkeyTime), Objects.requireNonNull(passkeyCredential));
        } else {
            grant.approve(now);
        }
        audit(AuthEvent.EventType.MCP_CLIENT_APPROVED, grant, accountId);
        return new Approval.Approved(clientRedirect(grant.getRedirectUri(), code.get(), null, grant.getState()));
    }

    /** Refuses a pending request; the browser goes to the client with {@code access_denied}. */
    @Transactional
    public Optional<String> refuse(UUID id, Long accountId) {
        McpClientGrant grant = grants.lockById(id).orElse(null);
        if (grant == null || !isOpenFor(grant, accountId)) {
            return Optional.empty();
        }
        grant.refuse(clock.instant());
        audit(AuthEvent.EventType.MCP_CLIENT_REFUSED, grant, accountId);
        return Optional.of(clientRedirect(grant.getRedirectUri(), null, "access_denied", grant.getState()));
    }

    /** The answer of the token endpoint: tokens, or one RFC 6749 § 5.2 error code. */
    public sealed interface TokenOutcome {
        record Issued(ClientSessionService.ClientTokens tokens, String scope) implements TokenOutcome {}

        record Refused(String error) implements TokenOutcome {}
    }

    /**
     * The token endpoint. Every failure of a code or a refresh secret is the same {@code invalid_grant}, so
     * the answer reveals nothing about which check failed. A resource that is not an MCP endpoint of this
     * issuer, or not the grant's, is {@code invalid_target} and consumes nothing.
     */
    @Transactional
    public TokenOutcome token(TokenRequest request, @Nullable HttpServletRequest http) {
        String grantType = request.grantType();
        if ("authorization_code".equals(grantType)) {
            return exchange(request, http);
        }
        if ("refresh_token".equals(grantType)) {
            return refresh(request, http);
        }
        return new TokenOutcome.Refused(grantType == null ? "invalid_request" : "unsupported_grant_type");
    }

    /** The form parameters of a token request. */
    public record TokenRequest(
            @Nullable String grantType,
            @Nullable String code,
            @Nullable String redirectUri,
            @Nullable String clientId,
            @Nullable String codeVerifier,
            @Nullable String refreshToken,
            @Nullable String resource,
            @Nullable String scope) {}

    private TokenOutcome exchange(TokenRequest request, @Nullable HttpServletRequest http) {
        String code = request.code();
        String redirectUri = request.redirectUri();
        String clientId = request.clientId();
        String verifier = request.codeVerifier();
        String resource = request.resource();
        if (code == null || redirectUri == null || clientId == null || verifier == null) {
            return new TokenOutcome.Refused("invalid_request");
        }
        if (resource == null || resources.slugOf(resource).isEmpty()) {
            return new TokenOutcome.Refused("invalid_target");
        }
        // A projection, not the entity: the exchange locks the grant next, and that locked read must be the
        // first load of the row, or a revoke that committed meanwhile would be invisible to it.
        Optional<McpClientGrantRepository.Target> target =
                handoffs.findMcpClientGrantId(Pkce.hash(code)).flatMap(grants::findTarget);
        if (target.isEmpty()) {
            return new TokenOutcome.Refused("invalid_grant");
        }
        if (!target.get().resource().equals(resource)) {
            return new TokenOutcome.Refused("invalid_target");
        }
        String scope = target.get().scopes();
        return sessions.exchangeMcpCode(clientId, redirectUri, code, verifier, http)
                .<TokenOutcome>map(tokens -> new TokenOutcome.Issued(tokens, scope))
                .orElseGet(() -> new TokenOutcome.Refused("invalid_grant"));
    }

    private TokenOutcome refresh(TokenRequest request, @Nullable HttpServletRequest http) {
        String refreshToken = request.refreshToken();
        String clientId = request.clientId();
        String resource = request.resource();
        if (refreshToken == null || clientId == null) {
            return new TokenOutcome.Refused("invalid_request");
        }
        if (resource != null && resources.slugOf(resource).isEmpty()) {
            return new TokenOutcome.Refused("invalid_target");
        }
        Optional<McpClientGrant> grant = issuedJwts
                .findSessionIdByRefreshTokenHash(Pkce.hash(refreshToken))
                .flatMap(grants::findActiveBySession);
        // The grant holds its resource literally, so after a rename the slug no longer leads to its workspace.
        boolean namesItsWorkspace = grant.flatMap(found -> resources
                        .slugOf(found.getResource())
                        .flatMap(workspaces::findActiveBySlug)
                        .filter(workspace -> workspace.id() == found.getWorkspaceId()))
                .isPresent();
        if (!namesItsWorkspace) {
            return new TokenOutcome.Refused("invalid_grant");
        }
        // A refresh never widens what the grant holds (RFC 8707 § 2.2).
        if (resource != null && !grant.get().getResource().equals(resource)) {
            return new TokenOutcome.Refused("invalid_target");
        }
        String scope = request.scope();
        if (scope != null && !requestedScopes(scope).stream().allMatch(grant.get()::grants)) {
            return new TokenOutcome.Refused("invalid_scope");
        }
        String granted = grant.get().getScopes();
        return sessions.refresh(refreshToken, clientId, http)
                .<TokenOutcome>map(tokens -> new TokenOutcome.Issued(tokens, granted))
                .orElseGet(() -> new TokenOutcome.Refused("invalid_grant"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Grant> activeGrants(Long workspaceId) {
        return grants.findActiveByWorkspace(workspaceId, clock.instant()).stream()
                .map(grant -> new Grant(
                        grant.id(),
                        URI.create(grant.clientId()).getHost(),
                        grant.clientName(),
                        grant.accountId(),
                        McpScope.parse(grant.scopes()).stream()
                                .map(McpScope::value)
                                .toList(),
                        grant.approvedAt(),
                        grant.passkeyVerifiedAt() != null,
                        grant.lastUsedAt(),
                        grant.sessionExpiresAt()))
                .toList();
    }

    @Override
    @Transactional
    public boolean revoke(Long workspaceId, UUID grantId, Long actingAccountId) {
        Long owner = grants.findAccountIdInWorkspace(workspaceId, grantId).orElse(null);
        if (owner == null || !sessions.revokeMcpGrant(owner, grantId)) {
            return false;
        }
        grants.findById(grantId)
                .ifPresent(grant -> audit(AuthEvent.EventType.MCP_CLIENT_REVOKED, grant, actingAccountId));
        return true;
    }

    private boolean isOpenFor(McpClientGrant grant, Long accountId) {
        return grant.getStatus() == McpClientGrant.Status.PENDING
                && grant.getAccountId().equals(accountId)
                && grant.getExpiresAt().isAfter(clock.instant());
    }

    private Optional<McpWorkspaceDirectory.McpWorkspace> workspaceOf(McpClientGrant grant) {
        return resources
                .slugOf(grant.getResource())
                .flatMap(workspaces::findActiveBySlug)
                .filter(workspace -> workspace.id() == grant.getWorkspaceId());
    }

    /** Whether this account can approve a client for this workspace, and whether it confirms with a passkey first. */
    private record Decision(@Nullable Refusal refusal, boolean passkeyRequired) {}

    private Decision decision(Long accountId, McpWorkspaceDirectory.McpWorkspace workspace) {
        if (!workspaces.hasAdminMembership(accountId, workspace.id())) {
            return new Decision(Refusal.NOT_ADMIN, false);
        }
        return switch (passkeys.mcpConsentProof(accountId, workspace.adminPasskeyRequired())) {
            case NONE -> new Decision(null, false);
            case PASSKEY -> new Decision(null, true);
            case REFUSED_RECOVERY_PENDING -> new Decision(Refusal.PASSKEY_RECOVERY_PENDING, false);
            case REFUSED_INSTANCE_ADMIN -> new Decision(Refusal.INSTANCE_ADMIN_PASSKEY_REQUIRED, false);
        };
    }

    private Outcome redirectError(String redirectUri, String error, @Nullable String state) {
        return new Outcome.ClientRedirect(clientRedirect(redirectUri, null, error, state));
    }

    /**
     * A redirect to the client that always names this issuer (RFC 9207), errors included. The registered URI
     * stays byte for byte as the metadata document lists it; only the added parameters are encoded.
     */
    private String clientRedirect(
            String redirectUri, @Nullable String code, @Nullable String error, @Nullable String state) {
        StringJoiner query = new StringJoiner("&");
        if (code != null) {
            query.add("code=" + formEncode(code));
        }
        if (error != null) {
            query.add("error=" + formEncode(error));
        }
        if (state != null) {
            query.add("state=" + formEncode(state));
        }
        query.add("iss=" + formEncode(resources.issuer()));
        return redirectUri + (redirectUri.contains("?") ? "&" : "?") + query;
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Records who decided about which client, for which workspace, with which scopes, and whose grant it is. */
    private void audit(AuthEvent.EventType type, McpClientGrant grant, Long actingAccountId) {
        var details = jsonMapper
                .createObjectNode()
                .put("grantId", grant.getId().toString())
                .put("clientId", grant.getClientId())
                .put("scopes", grant.getScopes())
                .put("grantAccountId", grant.getAccountId())
                // The grant row holds the time and the credential; the event says that the approval rested on them.
                .put("passkey", grant.getPasskeyVerifiedAt() != null);
        authEvents
                .event(type, AuthEvent.Result.SUCCESS)
                .account(actingAccountId)
                .workspace(grant.getWorkspaceId())
                .details(details.toString())
                .record();
    }

    private static List<McpScope> requestedScopes(@Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of(McpScope.READ);
        }
        String[] tokens = scope.trim().split(" +");
        List<McpScope> scopes = McpScope.parse(scope);
        // An unknown scope fails the request rather than being dropped from it.
        return scopes.size() == Arrays.stream(tokens).distinct().count() ? scopes : List.of();
    }

    private static String hostOf(String uri) {
        String host = URI.create(uri).getHost();
        return host == null ? uri : host;
    }

    private static boolean isLoopback(String uri) {
        String host = URI.create(uri).getHost();
        return host != null && (host.equals("127.0.0.1") || host.equals("[::1]") || host.equalsIgnoreCase("localhost"));
    }

    private static String originOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
}
