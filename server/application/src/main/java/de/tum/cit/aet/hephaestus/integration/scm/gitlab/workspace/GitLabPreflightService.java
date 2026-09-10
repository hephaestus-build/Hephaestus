package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabGroupDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightResponseDTO;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Pre-creation validation service for GitLab workspace setup.
 *
 * <p>Validates PATs and lists accessible groups <b>before</b> a workspace entity exists.
 * This service is always available (not gated by {@code hephaestus.integration.gitlab.enabled})
 * because it serves the workspace creation wizard, not sync operations.
 *
 * <h2>Token Validation Flow</h2>
 * <ol>
 *   <li>Try {@code GET /api/v4/user} — works for personal access tokens</li>
 *   <li>If 401 and {@code groupFullPath} provided, try {@code GET /api/v4/groups/{path}}
 *       — works for group/project access tokens</li>
 *   <li>Return structured success/failure result</li>
 * </ol>
 *
 * @see ScmServerEndpointPolicy for SSRF protection on user-provided server URLs
 */
@Service
public class GitLabPreflightService {

    private static final Logger log = LoggerFactory.getLogger(GitLabPreflightService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final GitLabProperties gitLabProperties;
    private final ScmServerEndpointPolicy endpoints;

    public GitLabPreflightService(GitLabProperties gitLabProperties, ScmServerEndpointPolicy endpoints) {
        this.gitLabProperties = gitLabProperties;
        this.endpoints = endpoints;
    }

    /**
     * Validates a GitLab PAT by attempting to authenticate against the GitLab API.
     *
     * @param token         the personal access token
     * @param serverUrl     custom server URL (nullable, defaults to gitlab.com)
     * @param groupFullPath optional group path for group/project token fallback
     * @return validation result with user/group info on success, or error message on failure
     */
    public GitLabPreflightResponseDTO validateToken(
            String token, @Nullable String serverUrl, @Nullable String groupFullPath) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);

        // Try personal token endpoint first
        try {
            GitLabUserResponse user = endpoints
                    .clientFor(resolvedUrl)
                    .get()
                    .uri(resolvedUrl + "/api/v4/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(GitLabUserResponse.class)
                    .block(REQUEST_TIMEOUT);

            if (user != null && user.id() != null) {
                log.info("GitLab preflight: token valid for user={}, serverUrl={}", user.username(), resolvedUrl);
                return GitLabPreflightResponseDTO.success(user.username(), user.id());
            }
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.debug("GitLab /api/v4/user returned {}: trying group fallback", status);
            } else {
                log.warn("GitLab /api/v4/user failed: status={}, serverUrl={}", status, resolvedUrl);
                return GitLabPreflightResponseDTO.failure("GitLab API returned status " + status);
            }
        } catch (Exception e) {
            log.warn("GitLab /api/v4/user failed: serverUrl={}, error={}", resolvedUrl, e.getMessage());
            return GitLabPreflightResponseDTO.failure("Failed to connect to GitLab server");
        }

        // Fallback: try group endpoint for group/project access tokens
        if (groupFullPath != null && !groupFullPath.isBlank()) {
            return validateGroupToken(token, resolvedUrl, groupFullPath.trim());
        }

        return GitLabPreflightResponseDTO.failure(
                "Token is invalid or is a group/project token. Provide groupFullPath for group token validation.");
    }

    private GitLabPreflightResponseDTO validateGroupToken(String token, String serverUrl, String groupFullPath) {
        try {
            GitLabGroupResponse group = endpoints
                    .clientFor(serverUrl)
                    .get()
                    .uri(serverUrl + "/api/v4/groups/{groupPath}", groupFullPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(GitLabGroupResponse.class)
                    .block(REQUEST_TIMEOUT);

            if (group != null && group.id() != null) {
                log.info("GitLab preflight: group token valid for group={}, serverUrl={}", group.fullPath(), serverUrl);
                return GitLabPreflightResponseDTO.success(group.name(), group.id());
            }
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                return GitLabPreflightResponseDTO.failure("Token does not have access to group: " + groupFullPath);
            }
            if (status == 404) {
                return GitLabPreflightResponseDTO.failure("Group not found: " + groupFullPath);
            }
            return GitLabPreflightResponseDTO.failure("GitLab API returned status " + status);
        } catch (Exception e) {
            log.warn(
                    "GitLab group token validation failed: serverUrl={}, group={}, error={}",
                    serverUrl,
                    groupFullPath,
                    e.getMessage());
            return GitLabPreflightResponseDTO.failure("Failed to validate group token");
        }

        return GitLabPreflightResponseDTO.failure("Failed to validate group token");
    }

    /**
     * Lists GitLab groups accessible to the provided PAT.
     *
     * @param token     the personal access token
     * @param serverUrl custom server URL (nullable, defaults to gitlab.com)
     * @return list of accessible groups
     */
    public List<GitLabGroupDTO> listAccessibleGroups(String token, @Nullable String serverUrl) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);

        try {
            List<GitLabGroupListItem> groups = endpoints
                    .clientFor(resolvedUrl)
                    .get()
                    .uri(resolvedUrl + "/api/v4/groups?min_access_level=10&per_page=100&order_by=name&sort=asc")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToFlux(GitLabGroupListItem.class)
                    .collectList()
                    .block(REQUEST_TIMEOUT);

            if (groups == null) {
                return List.of();
            }

            return groups.stream()
                    .map(g -> new GitLabGroupDTO(
                            g.id(), g.name(), g.fullPath(), g.avatarUrl(), g.webUrl(), g.visibility()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list accessible GitLab groups: serverUrl={}, error={}", resolvedUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolves and validates the server URL, applying SSRF protections.
     */
    private String resolveAndValidateServerUrl(@Nullable String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return gitLabProperties.defaultServerUrl();
        }

        String trimmed = serverUrl.trim();
        String normalized = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        endpoints.validate(normalized);

        return normalized;
    }

    // GitLab REST API Response Records

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitLabUserResponse(
            Long id,
            String username,
            String name,
            @JsonProperty("avatar_url") @Nullable String avatarUrl,
            @JsonProperty("web_url") @Nullable String webUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitLabGroupResponse(
            Long id,
            String name,
            @JsonProperty("full_path") String fullPath,
            @JsonProperty("avatar_url") @Nullable String avatarUrl,
            @JsonProperty("web_url") @Nullable String webUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitLabGroupListItem(
            Long id,
            String name,
            @JsonProperty("full_path") String fullPath,
            @JsonProperty("avatar_url") @Nullable String avatarUrl,
            @JsonProperty("web_url") @Nullable String webUrl,
            @Nullable String visibility) {}
}
