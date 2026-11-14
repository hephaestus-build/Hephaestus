package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Reads the access a person or a team has on GitHub now. A membership webhook only says that something changed: a
 * delivery can arrive again after a later change, so its handler applies what GitHub reports at the time it handles
 * it. Each read returns the current role, or empty when the access does not exist. Any other failure propagates.
 */
@Component
public class GitHubAccessReader {

    private static final String API_VERSION = "2022-11-28";

    private final GitHubGraphQlClientProvider tokens;
    private final GitHubSyncProperties syncProperties;
    private final WebClient restClient;

    public GitHubAccessReader(
            GitHubGraphQlClientProvider tokens,
            GitHubSyncProperties syncProperties,
            @Qualifier("gitHubGraphQlWebClient") WebClient webClient) {
        this.tokens = tokens;
        this.syncProperties = syncProperties;
        this.restClient = webClient
                .mutate()
                .baseUrl(GitHubSyncConstants.GITHUB_API_BASE_URL)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Membership(@Nullable String state, @Nullable String role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepositoryPermission(
            @Nullable String permission,
            @JsonProperty("role_name") @Nullable String roleName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TeamRepository(
            @JsonProperty("role_name") @Nullable String roleName) {}

    /**
     * The person's role in the organization ({@code admin} or {@code member}). An open invitation is not membership,
     * as the organization member list the sync reads leaves it out.
     */
    public Optional<String> organizationRole(long scopeId, String organization, String login) {
        return get(scopeId, Membership.class, "/orgs/{org}/memberships/{login}", organization, login)
                .filter(membership -> "active".equals(membership.state()))
                .map(Membership::role);
    }

    /** The person's role in the team ({@code member} or {@code maintainer}); an open invitation is not membership. */
    public Optional<String> teamRole(long scopeId, String organization, String teamSlug, String login) {
        return get(
                        scopeId,
                        Membership.class,
                        "/orgs/{org}/teams/{team}/memberships/{login}",
                        organization,
                        teamSlug,
                        login)
                .filter(membership -> "active".equals(membership.state()))
                .map(Membership::role);
    }

    /**
     * The person's role on the repository when they are one of its collaborators, as the sync's collaborator list
     * counts them. The permission read alone cannot tell: it also answers for people who are not collaborators.
     */
    public Optional<String> collaboratorRole(long scopeId, String owner, String repository, String login) {
        if (!exists(scopeId, "/repos/{owner}/{repo}/collaborators/{login}", owner, repository, login)) {
            return Optional.empty();
        }
        return get(
                        scopeId,
                        RepositoryPermission.class,
                        "/repos/{owner}/{repo}/collaborators/{login}/permission",
                        owner,
                        repository,
                        login)
                .map(permission -> permission.roleName() != null ? permission.roleName() : permission.permission());
    }

    /** The team's role on the repository when the team has access to it. */
    public Optional<String> teamRepositoryRole(
            long scopeId, String organization, String teamSlug, String owner, String repository) {
        try {
            return Optional.ofNullable(restClient
                            .get()
                            .uri(
                                    "/orgs/{org}/teams/{team}/repos/{owner}/{repo}",
                                    organization,
                                    teamSlug,
                                    owner,
                                    repository)
                            .header("X-GitHub-Api-Version", API_VERSION)
                            .header("Accept", "application/vnd.github.v3.repository+json")
                            .headers(headers -> headers.setBearerAuth(tokens.getToken(scopeId)))
                            .retrieve()
                            .bodyToMono(TeamRepository.class)
                            .block(syncProperties.graphqlTimeout()))
                    .map(TeamRepository::roleName);
        } catch (WebClientResponseException.NotFound absent) {
            return Optional.empty();
        }
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
