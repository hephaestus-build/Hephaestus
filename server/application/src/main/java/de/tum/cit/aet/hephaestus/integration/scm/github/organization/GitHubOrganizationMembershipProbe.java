package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubTokenService;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@ConditionalOnServerRole
public class GitHubOrganizationMembershipProbe implements OrganizationMembershipProbe {
    private final GitHubTokenService tokens;
    private final WebClient client;

    public GitHubOrganizationMembershipProbe(GitHubTokenService tokens, WebClient.Builder builder) {
        this.tokens = tokens;
        this.client = builder.baseUrl(GitHubSyncConstants.GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2026-03-10")
                .build();
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public Status check(Target target) {
        if (!"https://github.com".equals(target.serverUrl())
                || target.userNativeId() <= 0
                || target.organizationNativeId() <= 0) {
            return Status.UNAVAILABLE;
        }
        try {
            var token = tokens.getAccessToken(target.workspaceId());
            var user = client.get()
                    .uri("/user/{id}", target.userNativeId())
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Identity.class)
                    .block(Duration.ofSeconds(10));
            var organization = client.get()
                    .uri("/organizations/{id}", target.organizationNativeId())
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Identity.class)
                    .block(Duration.ofSeconds(10));
            if (user == null
                    || organization == null
                    || user.login() == null
                    || organization.login() == null
                    || !Objects.equals(user.id(), target.userNativeId())
                    || !Objects.equals(organization.id(), target.organizationNativeId())) {
                return Status.UNAVAILABLE;
            }
            var membership = client.get()
                    .uri("/orgs/{org}/memberships/{user}", organization.login(), user.login())
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Membership.class)
                    .block(Duration.ofSeconds(10));
            // The membership response itself binds both native IDs, closing username/org-rename races.
            if (membership == null
                    || membership.user() == null
                    || membership.organization() == null
                    || !Objects.equals(membership.user().id(), target.userNativeId())
                    || !Objects.equals(membership.organization().id(), target.organizationNativeId()))
                return Status.UNAVAILABLE;
            return "active".equals(membership.state()) ? Status.CONFIRMED : Status.NOT_CONFIRMED;
        } catch (WebClientResponseException failure) {
            return failure.getStatusCode() == HttpStatus.NOT_FOUND ? Status.NOT_CONFIRMED : Status.UNAVAILABLE;
        } catch (RuntimeException failure) {
            return Status.UNAVAILABLE;
        }
    }

    record Identity(@Nullable Long id, @Nullable String login) {}

    record Membership(
            @Nullable String state,
            @Nullable Identity user,
            @Nullable Identity organization) {}
}
