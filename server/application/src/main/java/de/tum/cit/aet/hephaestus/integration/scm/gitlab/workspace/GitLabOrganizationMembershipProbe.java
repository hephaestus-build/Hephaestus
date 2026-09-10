package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
public class GitLabOrganizationMembershipProbe implements OrganizationMembershipProbe {
    private final GitLabTokenService tokens;
    private final WebClient client;
    private final Clock clock;

    public GitLabOrganizationMembershipProbe(GitLabTokenService tokens, WebClient.Builder builder, Clock clock) {
        this.tokens = tokens;
        this.client = builder.build();
        this.clock = clock;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Status check(Target target) {
        if (target.userNativeId() <= 0 || target.organizationNativeId() <= 0) return Status.UNAVAILABLE;
        try {
            String serverUrl = tokens.resolveServerUrl(target.workspaceId());
            if (!serverUrl.equals(target.serverUrl())) return Status.UNAVAILABLE;
            // Member state is distinct from the user's account state. Filter at the membership endpoint
            // so an active user awaiting group approval is never admitted as an active member.
            var result = client.get()
                    .uri(
                            serverUrl + "/api/v4/groups/{group}/members/all?user_ids[]={user}&state=active&per_page=2",
                            target.organizationNativeId(),
                            target.userNativeId())
                    .headers(headers -> headers.setBearerAuth(tokens.getAccessToken(target.workspaceId())))
                    .retrieve()
                    .bodyToMono(Membership[].class)
                    .block(Duration.ofSeconds(10));
            if (result == null || result.length > 1) return Status.UNAVAILABLE;
            if (result.length == 0) return Status.NOT_CONFIRMED;
            var membership = result[0];
            if (membership == null
                    || !Objects.equals(membership.id(), target.userNativeId())
                    || membership.state() == null
                    || membership.accessLevel() == null) return Status.UNAVAILABLE;
            if (!"active".equals(membership.state()) || membership.accessLevel() <= 0) return Status.NOT_CONFIRMED;
            var expiry = membership.expiresAt();
            if (expiry != null && !LocalDate.parse(expiry).isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC))))
                return Status.NOT_CONFIRMED;
            return Status.CONFIRMED;
        } catch (WebClientResponseException failure) {
            return failure.getStatusCode() == HttpStatus.NOT_FOUND ? Status.NOT_CONFIRMED : Status.UNAVAILABLE;
        } catch (RuntimeException failure) {
            return Status.UNAVAILABLE;
        }
    }

    record Membership(
            @Nullable Long id,
            @Nullable String state,
            @JsonProperty("access_level") @Nullable Integer accessLevel,
            @JsonProperty("expires_at") @Nullable String expiresAt) {}
}
