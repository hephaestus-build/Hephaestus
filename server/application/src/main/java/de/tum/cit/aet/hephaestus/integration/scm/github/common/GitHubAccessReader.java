package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Reads the access a person or a team has on GitHub now. A membership webhook only says that something changed: a
 * delivery can arrive again after a later change, so its handler applies what GitHub reports at the time it handles
 * it. Each read returns the current role, or empty when the access does not exist; any other failure propagates.
 *
 * <p>A person is read by their GitHub user id, which never changes, so a login renamed after the delivery cannot
 * name someone else. The roles are the ones the hourly reconciliation stores, so the two do not undo each other.
 */
@Component
public class GitHubAccessReader {

    private static final String API_VERSION = "2022-11-28";

    /** The organization roles that the organization member list the reconciliation reads contains. */
    private static final Set<String> ORGANIZATION_ROLES = Set.of("admin", "member");

    private static final Set<String> TEAM_ROLES = Set.of("member", "maintainer");

    /** The base repository roles; a custom role is read as the base role it extends, as GraphQL reports it. */
    private static final Set<String> REPOSITORY_ROLES = Set.of("admin", "maintain", "write", "triage", "read");

    private final GitHubGraphQlClientProvider tokens;
    private final GitHubSyncProperties syncProperties;
    private final SyncTargetProvider syncTargets;
    private final WebClient restClient;

    public GitHubAccessReader(
            GitHubGraphQlClientProvider tokens,
            GitHubSyncProperties syncProperties,
            SyncTargetProvider syncTargets,
            @Qualifier("gitHubGraphQlWebClient") WebClient webClient) {
        this.tokens = tokens;
        this.syncProperties = syncProperties;
        this.syncTargets = syncTargets;
        this.restClient = webClient
                .mutate()
                .baseUrl(GitHubSyncConstants.GITHUB_API_BASE_URL)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(@Nullable String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Membership(@Nullable String state, @Nullable String role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepositoryPermission(
            @JsonProperty("role_name") @Nullable String roleName,
            @Nullable Grantee user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Grantee(@Nullable Map<String, Boolean> permissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TeamRepository(
            @JsonProperty("role_name") @Nullable String roleName,
            @Nullable Map<String, Boolean> permissions) {}

    /** Whether the workspace can read GitHub now: an inactive or purged workspace has no token to read with. */
    public boolean isReadable(long scopeId) {
        return syncTargets.isScopeActiveForSync(scopeId);
    }

    /** The person's role in the organization, {@code admin} or {@code member}. An open invitation is not membership. */
    public Optional<String> organizationRole(long scopeId, String organization, long userId) {
        return login(scopeId, userId)
                .flatMap(
                        login -> get(scopeId, Membership.class, "/orgs/{org}/memberships/{login}", organization, login))
                .filter(membership -> "active".equals(membership.state()))
                .flatMap(membership -> Optional.ofNullable(membership.role()))
                .filter(ORGANIZATION_ROLES::contains);
    }

    /** The person's role in the team, {@code member} or {@code maintainer}. An open invitation is not membership. */
    public Optional<String> teamRole(long scopeId, String organization, String teamSlug, long userId) {
        return login(scopeId, userId)
                .flatMap(login -> get(
                        scopeId,
                        Membership.class,
                        "/orgs/{org}/teams/{team}/memberships/{login}",
                        organization,
                        teamSlug,
                        login))
                .filter(membership -> "active".equals(membership.state()))
                .flatMap(membership -> Optional.ofNullable(membership.role()))
                .filter(TEAM_ROLES::contains);
    }

    /**
     * The person's role on the repository when they are one of its collaborators, as the reconciliation's collaborator
     * list counts them. The permission read alone cannot tell: it also answers for people who are not collaborators.
     */
    public Optional<String> collaboratorRole(long scopeId, String owner, String repository, long userId) {
        Optional<String> login = login(scopeId, userId);
        if (login.isEmpty()
                || !exists(scopeId, "/repos/{owner}/{repo}/collaborators/{login}", owner, repository, login.get())) {
            return Optional.empty();
        }
        return get(
                        scopeId,
                        RepositoryPermission.class,
                        "/repos/{owner}/{repo}/collaborators/{login}/permission",
                        owner,
                        repository,
                        login.get())
                .map(permission -> isBaseRole(permission.roleName())
                        ? Objects.requireNonNull(permission.roleName())
                        : highest(
                                permission.user() == null
                                        ? null
                                        : permission.user().permissions()));
    }

    /** The team's role on the repository when the team has access to it. */
    public Optional<String> teamRepositoryRole(
            long scopeId, String organization, String teamSlug, String owner, String repository) {
        try {
            var response = restClient
                    .get()
                    .uri("/orgs/{org}/teams/{team}/repos/{owner}/{repo}", organization, teamSlug, owner, repository)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    // Without this media type GitHub answers a team with access with an empty 204.
                    .header("Accept", "application/vnd.github.v3.repository+json")
                    .headers(headers -> headers.setBearerAuth(tokens.getToken(scopeId)))
                    .retrieve()
                    .toEntity(TeamRepository.class)
                    .block(syncProperties.graphqlTimeout());
            TeamRepository body = response == null ? null : response.getBody();
            if (body == null) {
                throw new IllegalStateException("GitHub answered the team repository check without the repository");
            }
            return Optional.of(
                    isBaseRole(body.roleName())
                            ? Objects.requireNonNull(body.roleName())
                            : highest(body.permissions()));
        } catch (WebClientResponseException.NotFound absent) {
            return Optional.empty();
        }
    }

    private static boolean isBaseRole(@Nullable String role) {
        return role != null && REPOSITORY_ROLES.contains(role);
    }

    /** The highest base role among GitHub's permission flags, which name the base role a custom role extends. */
    private static String highest(@Nullable Map<String, Boolean> permissions) {
        Map<String, Boolean> flags = permissions == null ? Map.of() : permissions;
        if (Boolean.TRUE.equals(flags.get("admin"))) return "admin";
        if (Boolean.TRUE.equals(flags.get("maintain"))) return "maintain";
        if (Boolean.TRUE.equals(flags.get("push"))) return "write";
        if (Boolean.TRUE.equals(flags.get("triage"))) return "triage";
        return "read";
    }

    /** The person's current login, or empty when the account no longer exists. */
    private Optional<String> login(long scopeId, long userId) {
        return get(scopeId, Account.class, "/user/{id}", userId)
                .flatMap(account -> Optional.ofNullable(account.login()));
    }

    private <T> Optional<T> get(long scopeId, Class<T> type, String uri, Object... variables) {
        try {
            return Optional.ofNullable(restClient
                    .get()
                    .uri(uri, variables)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .headers(headers -> headers.setBearerAuth(tokens.getToken(scopeId)))
                    .retrieve()
                    .bodyToMono(type)
                    .block(syncProperties.graphqlTimeout()));
        } catch (WebClientResponseException.NotFound absent) {
            return Optional.empty();
        }
    }

    private boolean exists(long scopeId, String uri, Object... variables) {
        try {
            var response = restClient
                    .get()
                    .uri(uri, variables)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .headers(headers -> headers.setBearerAuth(tokens.getToken(scopeId)))
                    .retrieve()
                    .toBodilessEntity()
                    .block(syncProperties.graphqlTimeout());
            return response != null && response.getStatusCode().isSameCodeAs(HttpStatus.NO_CONTENT);
        } catch (WebClientResponseException.NotFound absent) {
            return false;
        }
    }
}
