package de.tum.cit.aet.hephaestus.integration.access.github;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure.Reason;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.RsaPrivateKeys;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** GitHub.com membership boundary. Reads never authorize writes; callers must persist an approved action first. */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubAccessClient {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 1000;
    private final GitHubAccessProperties properties;
    private final long normalAppId;
    private final RestClient http;
    private final Clock clock;
    private final OutboundEgressGuard egress;

    public GitHubAccessClient(
            GitHubAccessProperties properties,
            GitHubProperties github,
            @Qualifier("oidcRequestFactory") ClientHttpRequestFactory requests,
            Clock clock,
            OutboundEgressGuard egress) {
        this.properties = properties;
        this.normalAppId = github.app().id();
        this.clock = clock;
        this.egress = egress;
        this.http = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public boolean configured() {
        return properties.appId() > 0
                && properties.appId() != normalAppId
                && !properties.privateKey().isBlank()
                && properties.appSlug().matches("[a-z0-9][a-z0-9-]{0,99}");
    }

    public Optional<String> installationUrl() {
        return configured()
                ? Optional.of("https://github.com/apps/" + properties.appSlug() + "/installations/new")
                : Optional.empty();
    }

    public long appId() {
        return properties.appId();
    }

    public record Session(
            long installationId,
            long organizationId,
            String organizationLogin,
            long scopeId,
            String scopeName,
            Map<String, String> permissions,
            String token,
            Instant expiresAt,
            Instant startedAt,
            @Nullable SyncExecutionHandle handle) {
        public Session {
            permissions = Map.copyOf(permissions);
        }

        public Session withProgress(SyncExecutionHandle progress) {
            return new Session(
                    installationId,
                    organizationId,
                    organizationLogin,
                    scopeId,
                    scopeName,
                    permissions,
                    token,
                    expiresAt,
                    startedAt,
                    progress);
        }

        @Override
        public String toString() {
            return "GitHubAccessSession[installationId=" + installationId + ", token=***]";
        }
    }

    public enum State {
        ABSENT,
        PENDING,
        ACTIVE,
        WAITING_ORGANIZATION,
        PROTECTED
    }

    public record Membership(
            long userId,
            String login,
            State state,
            @Nullable Long invitationId,
            @Nullable String explanation) {}

    public record Inventory(Instant capturedAt, List<Membership> members, int unlinkedInvitations) {
        public Inventory {
            members = List.copyOf(members);
        }
    }

    /** An initial lookup accepts names only to discover the native IDs that all subsequent work pins. */
    public Session describe(long installationId, String organizationLogin, @Nullable String teamSlug) {
        requireLogin(organizationLogin);
        if (teamSlug != null && !teamSlug.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
            throw new IllegalArgumentException("Provide a GitHub team slug, not a URL");
        return redacted(() -> {
            Session organization = open(installationId, null, 0);
            if (!organization.organizationLogin().equalsIgnoreCase(organizationLogin))
                throw failure(Reason.TARGET_CHANGED, "This installation belongs to a different GitHub organization");
            if (teamSlug == null) return organization;
            Team team = get(
                    organization.token(),
                    "/orgs/{org}/teams/{team}",
                    Team.class,
                    organization.organizationLogin(),
                    teamSlug);
            return teamSession(organization, team, null);
        });
    }

    /** Re-verifies the App, organization, permission set and suspension before minting a short-lived token. */
    // Keep provider failures sanitized, as in redacted().
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Session open(long installationId, @Nullable Long expectedOrganizationId, long scopeId) {
        if (installationId <= 0 || scopeId < 0 || scopeId == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid GitHub installation or target ID");
        return redacted(() -> {
            Installation installation;
            String jwt = appJwt();
            try {
                installation = get(jwt, "/app/installations/{id}", Installation.class, installationId);
            } catch (HttpClientErrorException.NotFound missing) {
                throw failure(
                        Reason.UNINSTALLED,
                        "The Access App installation is unavailable; reinstall it and obtain fresh organization-owner approval");
            }
            User organization = required(installation.account());
            long organizationId = nativeId(organization.id());
            String organizationLogin = login(organization);
            if (!Objects.equals(installation.id(), installationId)
                    || !Objects.equals(installation.appId(), properties.appId())
                    || !"Organization".equals(installation.targetType())
                    || !"Organization".equals(organization.type())
                    || !Objects.equals(installation.targetId(), organizationId)
                    || (expectedOrganizationId != null && expectedOrganizationId != organizationId))
                throw failure(
                        Reason.TARGET_CHANGED,
                        "The Access App installation or organization identity changed; configure and authorize the intended target again");
            if (installation.suspendedAt() != null)
                throw failure(
                        Reason.SUSPENDED,
                        "The Access App is suspended. External access remains; ask the organization owner to restore the installation");
            Map<String, String> permissions = required(installation.permissions());
            if (!"write".equals(permissions.get("members"))
                    || permissions.entrySet().stream()
                            .anyMatch(entry -> !entry.getKey().equals("members")
                                    && !(entry.getKey().equals("metadata") && "read".equals(entry.getValue()))))
                throw failure(
                        Reason.PERMISSIONS,
                        "Hephaestus Access needs organization Members: write and no additional permissions except Metadata: read; review the App installation");
            // Token minting is authentication, not a membership change, so inspection works in Silent Mode.
            Token token = required(http.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .headers(headers -> headers.setBearerAuth(jwt))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("permissions", Map.of("members", "write")))
                    .retrieve()
                    .body(Token.class));
            String bearer = required(token.token());
            Instant expiresAt = required(token.expiresAt());
            if (!bearer.matches("[A-Za-z0-9_.-]{1,8192}")
                    || !expiresAt.isAfter(clock.instant().plusSeconds(30)))
                throw failure(
                        Reason.CREDENTIALS,
                        "GitHub did not return a usable Access App token; check the App credentials");
            Session session = new Session(
                    installationId,
                    organizationId,
                    organizationLogin,
                    0,
                    organizationLogin,
                    permissions,
                    bearer,
                    expiresAt,
                    clock.instant(),
                    null);
            if (scopeId == 0) return session;
            Team team = get(session.token(), "/organizations/{org}/team/{team}", Team.class, organizationId, scopeId);
            return teamSession(session, team, scopeId);
        });
    }

    public void requireOrganizationOwner(Session session, long userId) {
        redacted(() -> {
            User user = user(session, userId);
            OrgMembership membership = organizationMembership(session, user)
                    .orElseThrow(() -> failure(
                            Reason.AUTHORITY_LOST, "The authorizing GitHub identity is not an organization owner"));
            if (!"active".equals(membership.state()) || !"admin".equals(membership.role()))
                throw failure(Reason.AUTHORITY_LOST, "An active GitHub organization owner must authorize this target");
            return true;
        });
    }

    public Membership inspect(Session session, long userId) {
        return redacted(() -> inspectPerson(session, user(session, userId)));
    }

    /** A full preview inventories unmanaged access too; an interrupted or oversized inventory is never published. */
    public Inventory inventory(Session session, SyncExecutionHandle handle) {
        return redacted(() -> {
            Map<Long, Membership> members = new LinkedHashMap<>();
            String path = session.scopeId() == 0
                    ? "/orgs/" + session.organizationLogin() + "/members"
                    : "/organizations/" + session.organizationId() + "/team/" + session.scopeId() + "/members";
            for (User member : pages(session, path, new ParameterizedTypeReference<List<User>>() {})) {
                checkInventory(session, handle, members.size());
                long id = nativeId(member.id());
                if (members.put(id, inspectPerson(session, member)) != null)
                    throw failure(Reason.INCOMPLETE, "GitHub returned duplicate inventory entries; retry the preview");
            }
            int unlinked = 0;
            for (Invitation invitation : invitations(session)) {
                checkInventory(session, handle, members.size());
                String inviteeLogin = invitation.login();
                if (inviteeLogin == null) {
                    unlinked++;
                    continue;
                }
                requireLogin(inviteeLogin);
                User member = get(session.token(), "/users/{login}", User.class, inviteeLogin);
                long id = nativeId(member.id());
                Membership state = inspectPerson(session, member);
                if (state.state() != State.PENDING
                        && state.state() != State.PROTECTED
                        && state.state() != State.WAITING_ORGANIZATION)
                    throw failure(Reason.INCOMPLETE, "A GitHub invitation changed during inventory; retry the preview");
                members.put(id, state);
            }
            return new Inventory(clock.instant(), List.copyOf(members.values()), unlinked);
        });
    }

    /** Called only for a persisted grant whose last confirmed state was absent. Never adopts a race winner. */
    public Membership grant(Session session, long userId) {
        return redacted(() -> {
            Membership before = inspect(session, userId);
            if (before.state() != State.ABSENT)
                throw failure(
                        Reason.WRITE_UNCONFIRMED,
                        "Access already exists or is protected; inspect it and explicitly adopt it rather than claiming a new grant");
            User user = user(session, userId);
            verifyUsername(session, user);
            allowed(session, "github-access-grant");
            Long createdInvitationId = null;
            if (session.scopeId() == 0) {
                Invitation created = required(http.post()
                        .uri("/orgs/{org}/invitations", session.organizationLogin())
                        .headers(headers -> headers.setBearerAuth(session.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("invitee_id", userId, "role", "direct_member", "team_ids", List.of()))
                        .retrieve()
                        .body(Invitation.class));
                createdInvitationId = nativeId(created.id());
                if (!login(user).equalsIgnoreCase(created.login()) || !"direct_member".equals(created.role()))
                    throw failure(
                            Reason.WRITE_UNCONFIRMED,
                            "GitHub did not bind the created invitation to the requested identity and role");
            } else {
                required(http.put()
                        .uri(
                                "/organizations/{org}/team/{team}/memberships/{login}",
                                session.organizationId(),
                                session.scopeId(),
                                login(user))
                        .headers(headers -> headers.setBearerAuth(session.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("role", "member"))
                        .retrieve()
                        .body(TeamMembership.class));
            }
            verifyUsername(session, user);
            Membership after = inspect(session, userId);
            if (createdInvitationId != null
                    && after.state() == State.PENDING
                    && !createdInvitationId.equals(after.invitationId()))
                throw failure(
                        Reason.WRITE_UNCONFIRMED,
                        "GitHub returned a different pending invitation; inspect it before adoption");
            if (after.state() != State.ACTIVE && after.state() != State.PENDING)
                throw failure(
                        Reason.WRITE_UNCONFIRMED,
                        "GitHub has not confirmed the requested access; inspect the pending action before retrying");
            return after;
        });
    }

    /** A successful DELETE is only a request. The read-back must prove absence before the ledger can say revoked. */
    public Membership revoke(Session session, long userId) {
        return redacted(() -> {
            Membership before = inspect(session, userId);
            if (before.state() == State.ABSENT) return before;
            if (before.state() == State.WAITING_ORGANIZATION)
                return new Membership(userId, before.login(), State.ABSENT, null, null);
            if (before.state() == State.PROTECTED)
                throw failure(
                        Reason.WRITE_UNCONFIRMED,
                        "This access is protected; ask the organization owner to resolve it without removing unrelated access");
            User user = user(session, userId);
            verifyUsername(session, user);
            if (session.scopeId() == 0 && before.state() == State.ACTIVE) {
                for (Team candidate : pages(
                        session,
                        "/orgs/" + session.organizationLogin() + "/teams",
                        new ParameterizedTypeReference<List<Team>>() {})) {
                    long teamId = nativeId(candidate.id());
                    if (optional(
                                    session.token(),
                                    "/organizations/{org}/team/{team}/memberships/{login}",
                                    TeamMembership.class,
                                    session.organizationId(),
                                    teamId,
                                    login(user))
                            .isPresent())
                        throw failure(
                                Reason.WRITE_UNCONFIRMED,
                                "Team access still exists. Remove managed team access first; the organization owner must review unmanaged or inherited teams before organization departure");
                }
            }
            allowed(session, "github-access-revoke");
            try {
                if (session.scopeId() == 0 && before.state() == State.PENDING) {
                    long invitationId = required(before.invitationId());
                    http.delete()
                            .uri("/orgs/{org}/invitations/{invitation}", session.organizationLogin(), invitationId)
                            .headers(headers -> headers.setBearerAuth(session.token()))
                            .retrieve()
                            .toBodilessEntity();
                } else if (session.scopeId() == 0) {
                    http.delete()
                            .uri("/orgs/{org}/members/{login}", session.organizationLogin(), login(user))
                            .headers(headers -> headers.setBearerAuth(session.token()))
                            .retrieve()
                            .toBodilessEntity();
                } else {
                    http.delete()
                            .uri(
                                    "/organizations/{org}/team/{team}/memberships/{login}",
                                    session.organizationId(),
                                    session.scopeId(),
                                    login(user))
                            .headers(headers -> headers.setBearerAuth(session.token()))
                            .retrieve()
                            .toBodilessEntity();
                }
            } catch (HttpClientErrorException.NotFound absent) {
                // A stale URL or lost installation is not absence; the authenticated read-back decides.
            }
            verifyUsername(session, user);
            Membership after = inspect(session, userId);
            if (after.state() != State.ABSENT && after.state() != State.WAITING_ORGANIZATION)
                throw failure(
                        Reason.WRITE_UNCONFIRMED,
                        "GitHub still reports access, possibly inherited from another team or enterprise; removal is not confirmed");
            return after.state() == State.WAITING_ORGANIZATION
                    ? new Membership(userId, after.login(), State.ABSENT, null, null)
                    : after;
        });
    }

    private Membership inspectPerson(Session session, User user) {
        long id = nativeId(user.id());
        String login = login(user);
        verifyUsername(session, user);
        Optional<OrgMembership> membership = organizationMembership(session, user);
        if (membership.isPresent()) {
            OrgMembership organization = membership.get();
            if (!"member".equals(organization.role())
                    || Boolean.FALSE.equals(organization.directMembership())
                    || (organization.enterpriseTeams() != null
                            && !organization.enterpriseTeams().isEmpty()))
                return new Membership(
                        id,
                        login,
                        State.PROTECTED,
                        null,
                        "Organization owners, billing managers and enterprise-managed access remain outside automatic management");
        }
        if (session.scopeId() == 0) {
            if (membership.isEmpty()) {
                boolean outside = pages(
                                session,
                                "/orgs/" + session.organizationLogin() + "/outside_collaborators",
                                new ParameterizedTypeReference<List<User>>() {})
                        .stream()
                        .anyMatch(collaborator -> nativeId(collaborator.id()) == id);
                return new Membership(
                        id,
                        login,
                        outside ? State.PROTECTED : State.ABSENT,
                        null,
                        outside
                                ? "Outside-collaborator access must be reviewed manually before changing organization membership"
                                : null);
            }
            OrgMembership organization = membership.get();
            State state = state(organization.state());
            if (state == State.ACTIVE) return new Membership(id, login, state, null, null);
            List<Invitation> matching = invitations(session).stream()
                    .filter(invitation -> login.equalsIgnoreCase(invitation.login()))
                    .toList();
            if (matching.size() != 1
                    || !"direct_member".equals(matching.getFirst().role()))
                return new Membership(
                        id,
                        login,
                        State.PROTECTED,
                        null,
                        "The pending invitation needs an organization owner's review");
            Invitation invitation = matching.getFirst();
            if (invitation.teamCount() == null || invitation.teamCount() > 0)
                return new Membership(
                        id,
                        login,
                        State.PROTECTED,
                        null,
                        "This invitation includes team access outside this organization-only action");
            return new Membership(id, login, State.PENDING, nativeId(invitation.id()), null);
        }
        Optional<TeamMembership> team = optional(
                session.token(),
                "/organizations/{org}/team/{team}/memberships/{login}",
                TeamMembership.class,
                session.organizationId(),
                session.scopeId(),
                login);
        if (team.isEmpty()) {
            if (membership.isEmpty() || !"active".equals(membership.get().state()))
                return new Membership(
                        id,
                        login,
                        State.WAITING_ORGANIZATION,
                        null,
                        "Accept an organization invitation first; a team target never grants organization membership implicitly");
            return new Membership(id, login, State.ABSENT, null, null);
        }
        if (!"member".equals(team.get().role()))
            return new Membership(
                    id, login, State.PROTECTED, null, "Team maintainers remain outside automatic management");
        return new Membership(id, login, state(team.get().state()), null, null);
    }

    private Optional<OrgMembership> organizationMembership(Session session, User user) {
        Optional<OrgMembership> result = optional(
                session.token(),
                "/orgs/{org}/memberships/{login}",
                OrgMembership.class,
                session.organizationLogin(),
                login(user));
        result.ifPresent(membership -> {
            if (nativeId(required(membership.user()).id()) != nativeId(user.id())
                    || nativeId(required(membership.organization()).id()) != session.organizationId())
                throw failure(
                        Reason.IDENTITY_CHANGED,
                        "GitHub returned a membership for a different immutable identity; no change was authorized");
            state(membership.state());
        });
        return result;
    }

    // Keep provider failures sanitized, as in redacted().
    @SuppressWarnings("PMD.PreserveStackTrace")
    private User user(Session session, long id) {
        ensureToken(session);
        User user;
        try {
            user = get(session.token(), "/user/{id}", User.class, id);
        } catch (HttpClientErrorException.NotFound missing) {
            throw failure(
                    Reason.IDENTITY_CHANGED,
                    "The linked GitHub identity is unavailable; do not substitute its old username");
        }
        if (nativeId(user.id()) != id || !"User".equals(user.type()))
            throw failure(Reason.IDENTITY_CHANGED, "GitHub did not confirm the linked human identity");
        login(user);
        return user;
    }

    // Username-addressed writes have no provider-side identity precondition. Keep the historical
    // alias in a sanitized failure so an uncertain write cannot be retried against a different login.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void verifyUsername(Session session, User expected) {
        String expectedLogin = login(expected);
        User current;
        try {
            current = get(session.token(), "/users/{login}", User.class, expectedLogin);
        } catch (HttpClientErrorException.NotFound missing) {
            throw changedUsername(expected);
        }
        if (nativeId(current.id()) != nativeId(expected.id()) || !"User".equals(current.type()))
            throw changedUsername(expected);
    }

    private GitHubAccessFailure changedUsername(User expected) {
        return failure(
                Reason.IDENTITY_CHANGED,
                "GitHub login " + login(expected)
                        + " no longer identifies linked native user " + nativeId(expected.id())
                        + ". A prior request may have changed access; inspect this login and the approved scope before resolving any pending action.");
    }

    private List<Invitation> invitations(Session session) {
        String path = session.scopeId() == 0
                ? "/orgs/" + session.organizationLogin() + "/invitations"
                : "/organizations/" + session.organizationId() + "/team/" + session.scopeId() + "/invitations";
        return pages(session, path, new ParameterizedTypeReference<List<Invitation>>() {});
    }

    private <T> List<T> pages(Session session, String path, ParameterizedTypeReference<List<T>> type) {
        List<T> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ensureToken(session);
            List<T> values = required(http.get()
                    .uri(path + "?per_page=" + PAGE_SIZE + "&page=" + page)
                    .headers(headers -> headers.setBearerAuth(session.token()))
                    .retrieve()
                    .body(type));
            SyncExecutionHandle handle = session.handle();
            if (handle != null)
                handle.progress(
                        null, null, SyncProgress.of(SyncPhase.TEAMS, "Reading a complete GitHub membership inventory"));
            if (values.isEmpty()) return List.copyOf(result);
            result.addAll(values);
        }
        throw failure(
                Reason.INCOMPLETE,
                "GitHub inventory exceeded its safety bound; narrow the target or contact the operator");
    }

    private Session teamSession(Session organization, Team team, @Nullable Long expectedTeamId) {
        long teamId = nativeId(team.id());
        if (teamId == Long.MAX_VALUE
                || nativeId(required(team.organization()).id()) != organization.organizationId()
                || (expectedTeamId != null && expectedTeamId != teamId))
            throw failure(
                    Reason.TARGET_CHANGED,
                    "The team no longer matches its approved GitHub organization and immutable ID");
        String name = required(team.name());
        if (name.isBlank()) throw failure(Reason.INCOMPLETE, "GitHub did not identify the target team");
        return new Session(
                organization.installationId(),
                organization.organizationId(),
                organization.organizationLogin(),
                teamId,
                name,
                organization.permissions(),
                organization.token(),
                organization.expiresAt(),
                organization.startedAt(),
                organization.handle());
    }

    private String appJwt() {
        if (!configured())
            throw failure(
                    Reason.NOT_CONFIGURED,
                    "Ask the operator to configure a separate Hephaestus Access App; the normal GitHub App cannot provision access");
        var key = RsaPrivateKeys.parse(properties.privateKey());
        if (!(key instanceof RSAPrivateKey rsa))
            throw failure(Reason.CREDENTIALS, "The Access App requires an RSA private key");
        Instant now = clock.instant();
        return JWT.create()
                .withIssuer(String.valueOf(properties.appId()))
                .withIssuedAt(Date.from(now.minusSeconds(60)))
                .withExpiresAt(Date.from(now.plusSeconds(540)))
                .sign(Algorithm.RSA256(null, rsa));
    }

    private void ensureToken(Session session) {
        SyncExecutionHandle handle = session.handle();
        if (handle != null && handle.isCancellationRequested()) {
            handle.reportCancelled();
            throw failure(Reason.CANCELLED, "GitHub reconciliation was cancelled; pending changes remain unconfirmed");
        }
        if (!clock.instant().isBefore(session.startedAt().plus(Duration.ofMinutes(10))))
            throw failure(Reason.INCOMPLETE, "This GitHub pass exceeded its safety window; retry with fresh evidence");
        if (!clock.instant().isBefore(session.expiresAt().minusSeconds(10)))
            throw failure(
                    Reason.CREDENTIALS, "The Access App token expired; retry with a freshly verified installation");
    }

    // Keep provider failures sanitized, as in redacted().
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void allowed(Session session, String operation) {
        ensureToken(session);
        try {
            egress.requireDeliveryAllowed(operation);
        } catch (OutboundEgressSuppressedException suppressed) {
            throw failure(
                    Reason.SILENT_MODE,
                    "Silent Mode blocks external membership changes; access remains until writes resume and removal is confirmed");
        }
    }

    private void checkInventory(Session session, SyncExecutionHandle handle, int count) {
        ensureToken(session);
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
            throw failure(Reason.CANCELLED, "GitHub inventory was cancelled; no partial preview was published");
        }
        handle.progress(
                count, null, SyncProgress.of(SyncPhase.TEAMS, "Inspecting GitHub memberships and pending invitations"));
    }

    private <T> T get(String token, String path, Class<T> type, Object... variables) {
        return required(http.get()
                .uri(path, variables)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(type));
    }

    private <T> Optional<T> optional(String token, String path, Class<T> type, Object... variables) {
        try {
            return Optional.of(get(token, path, type, variables));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        }
    }

    private static State state(@Nullable String state) {
        if ("active".equals(state)) return State.ACTIVE;
        if ("pending".equals(state)) return State.PENDING;
        throw failure(Reason.INCOMPLETE, "GitHub returned an unknown membership state; no change is authorized");
    }

    private static long nativeId(@Nullable Long id) {
        if (id == null || id <= 0) throw failure(Reason.INCOMPLETE, "GitHub omitted an immutable identity");
        return id;
    }

    private static String login(User user) {
        String login = required(user.login());
        requireLogin(login);
        return login;
    }

    private static void requireLogin(String login) {
        if (!login.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
            throw new IllegalArgumentException("Provide a GitHub organization or user login, not a URL");
    }

    private static <T> T required(@Nullable T value) {
        if (value == null)
            throw failure(Reason.INCOMPLETE, "GitHub returned an incomplete response; retry without changing access");
        return value;
    }

    // Transport exceptions retain response bodies and authorization context. Do not retain them as causes.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private <T> T redacted(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GitHubAccessFailure known) {
            throw known;
        } catch (RestClientResponseException response) {
            int status = response.getStatusCode().value();
            HttpHeaders headers = response.getResponseHeaders();
            if (status == 429
                    || (status == 403
                            && headers != null
                            && ("0".equals(headers.getFirst("X-RateLimit-Remaining"))
                                    || headers.containsHeader("Retry-After"))))
                throw new GitHubAccessFailure(
                        Reason.RATE_LIMITED,
                        "GitHub is rate limiting this target; pending changes remain and will retry",
                        retryAt(headers));
            if (status == 401 || status == 403)
                throw failure(
                        Reason.CREDENTIALS,
                        "GitHub refused the Access App credentials or permissions; external access has not been revoked");
            throw failure(
                    status >= 500 ? Reason.UNAVAILABLE : Reason.WRITE_UNCONFIRMED,
                    "GitHub could not confirm this operation; inspect the target and retry without assuming access changed");
        } catch (RestClientException transport) {
            throw failure(
                    Reason.UNAVAILABLE,
                    "GitHub could not be reached or its response was unreadable; pending changes remain unconfirmed");
        } catch (IllegalStateException invalid) {
            throw failure(
                    Reason.CREDENTIALS,
                    "The Access App configuration is invalid; ask the operator to check its credentials");
        }
    }

    private Instant retryAt(@Nullable HttpHeaders headers) {
        long seconds = 60;
        if (headers != null) {
            try {
                String after = headers.getFirst("Retry-After");
                String reset = headers.getFirst("X-RateLimit-Reset");
                if (after != null) seconds = Long.parseLong(after);
                else if (reset != null)
                    seconds = Long.parseLong(reset) - clock.instant().getEpochSecond();
            } catch (NumberFormatException invalid) {
                seconds = 60;
            }
        }
        return clock.instant().plusSeconds(Math.clamp(seconds, 60, 86_400));
    }

    private static GitHubAccessFailure failure(Reason reason, String message) {
        return new GitHubAccessFailure(reason, message);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record User(
            @Nullable Long id,
            @Nullable String login,
            @Nullable String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Installation(
            @Nullable Long id,
            @Nullable User account,
            @JsonProperty("app_id") @Nullable Long appId,
            @JsonProperty("target_id") @Nullable Long targetId,
            @JsonProperty("target_type") @Nullable String targetType,
            @Nullable Map<String, String> permissions,
            @JsonProperty("suspended_at") @Nullable Instant suspendedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Token(
            @Nullable String token,
            @JsonProperty("expires_at") @Nullable Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Team(
            @Nullable Long id,
            @Nullable String name,
            @Nullable User organization) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrgMembership(
            @Nullable String state,
            @Nullable String role,
            @Nullable User user,
            @Nullable User organization,
            @JsonProperty("direct_membership") @Nullable Boolean directMembership,

            @JsonProperty("enterprise_teams_providing_indirect_membership") @Nullable
            List<String> enterpriseTeams) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TeamMembership(
            @Nullable String state, @Nullable String role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Invitation(
            @Nullable Long id,
            @Nullable String login,
            @Nullable String role,
            @JsonProperty("team_count") @Nullable Integer teamCount) {}
}
