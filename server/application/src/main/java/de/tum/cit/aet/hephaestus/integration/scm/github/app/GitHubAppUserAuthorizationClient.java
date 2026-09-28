package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub as the person who just authorized the GitHub App: exchanges the code GitHub returns when an installation
 * requests user authorization, then reads what that person can reach. The user access token exists only for the
 * duration of one connect; it is never stored or logged.
 *
 * @see <a href="https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app">Generating a user access token for a GitHub App</a>
 * @see <a href="https://docs.github.com/en/rest/apps/installations#list-app-installations-accessible-to-the-user-access-token">List app installations accessible to the user access token</a>
 */
@Component
@ConditionalOnServerRole
@EgressExempt(EgressExemption.OAUTH_CONTROL_PLANE)
public class GitHubAppUserAuthorizationClient {

    private static final int PAGE_SIZE = 100;

    private final @Nullable String clientId;
    private final @Nullable String clientSecret;
    private final RestClient web;
    private final RestClient api;

    public GitHubAppUserAuthorizationClient(
            GitHubProperties properties,
            @Value("${hephaestus.integration.github.app.web-base-url:https://github.com}") String webBaseUrl,
            @Value("${hephaestus.integration.github.app.api-base-url:" + GITHUB_API_BASE_URL + "}") String apiBaseUrl) {
        this.clientId = properties.app().clientId();
        this.clientSecret = properties.app().clientSecret();
        this.web = RestClient.builder().baseUrl(webBaseUrl).build();
        this.api = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Whether the App's client credentials are configured, without which no code can be exchanged. */
    public boolean isConfigured() {
        return clientId != null && clientSecret != null;
    }

    /**
     * Exchanges an authorization {@code code} for a user access token. GitHub answers a bad or reused code with
     * {@code 200} and an {@code error} member, so a missing token is the failure signal.
     *
     * @throws GitHubUserAuthorizationException when the credentials are missing, GitHub is unreachable, or it refuses
     */
    public String exchangeCode(String code) {
        if (clientId == null || clientSecret == null) {
            throw new GitHubUserAuthorizationException("GitHub App client credentials are not configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        TokenResponse response;
        try {
            response = web.post()
                    .uri("/login/oauth/access_token")
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            throw new GitHubUserAuthorizationException("GitHub did not complete the code exchange", e);
        }
        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()) {
            String error = response == null || response.error() == null ? "no access token" : response.error();
            throw new GitHubUserAuthorizationException("GitHub refused the code exchange: " + error);
        }
        return response.accessToken();
    }

    /** Installation {@code installationId} of this App, if the token's user can access it. */
    public Optional<UserInstallation> findAccessibleInstallation(String userToken, long installationId) {
        for (int page = 1; ; page++) {
            InstallationsPage body = get(
                    userToken,
                    "/user/installations?per_page={size}&page={page}",
                    InstallationsPage.class,
                    PAGE_SIZE,
                    page);
            List<UserInstallation> installations = body.installations() == null ? List.of() : body.installations();
            Optional<UserInstallation> match = installations.stream()
                    .filter(installation -> installation.id() == installationId)
                    .findFirst();
            if (match.isPresent() || installations.size() < PAGE_SIZE) {
                return match;
            }
        }
    }

    /** The id of the token's user. */
    public long userId(String userToken) {
        return get(userToken, "/user", User.class).id();
    }

    /**
     * Whether the token's user is an active owner of organization {@code login}. GitHub answers {@code 404} to a
     * non-member and {@code 403} when the organization blocks the App; neither is an owner.
     */
    public boolean ownsOrganization(String userToken, String login) {
        Membership membership;
        try {
            membership = get(userToken, "/user/memberships/orgs/{org}", Membership.class, login);
        } catch (GitHubUserAuthorizationException e) {
            if (e.getCause() instanceof HttpClientErrorException http
                    && (http.getStatusCode() == HttpStatus.NOT_FOUND || http.getStatusCode() == HttpStatus.FORBIDDEN)) {
                return false;
            }
            throw e;
        }
        return "admin".equals(membership.role()) && "active".equals(membership.state());
    }

    private <T> T get(String userToken, String uri, Class<T> type, Object... variables) {
        T body;
        try {
            body = api.get()
                    .uri(uri, variables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .retrieve()
                    .body(type);
        } catch (RestClientException e) {
            throw new GitHubUserAuthorizationException("GitHub did not answer GET " + uri, e);
        }
        if (body == null) {
            throw new GitHubUserAuthorizationException("GitHub answered GET " + uri + " with no body");
        }
        return body;
    }

    /**
     * One installation of this App. {@code targetType} is {@code Organization} or {@code User}; an enterprise
     * installation's account has no login.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInstallation(
            @JsonProperty("id") long id,
            @JsonProperty("target_type") @Nullable String targetType,
            @JsonProperty("account") @Nullable Account account) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Account(
                @JsonProperty("id") long id,
                @JsonProperty("login") @Nullable String login) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("error") @Nullable String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InstallationsPage(
            @JsonProperty("installations") @Nullable List<UserInstallation> installations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record User(@JsonProperty("id") long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Membership(
            @JsonProperty("role") @Nullable String role,
            @JsonProperty("state") @Nullable String state) {}
}
