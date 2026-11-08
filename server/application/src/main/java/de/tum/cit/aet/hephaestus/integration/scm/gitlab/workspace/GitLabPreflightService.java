package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.DEFAULT_PAGE_SIZE;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.identity.GitLabWorkspaceInstance;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.SelectedRepositoryVerifier;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabGroupDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabProjectDTO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pre-creation validation service for GitLab workspace setup.
 *
 * <p>Validates PATs, lists accessible groups and a group's projects, and verifies the projects a new
 * workspace selected, <b>before</b> a workspace entity exists. Every request goes to the default
 * instance with the PAT from the request body. This service is always available (not gated by {@code hephaestus.integration.gitlab.enabled})
 * because it serves the workspace creation wizard, not sync operations.
 *
 * <h2>Token Validation Flow</h2>
 * <ol>
 *   <li>Try {@code GET /api/v4/user}</li>
 *   <li>If that is refused and {@code groupFullPath} is provided, read the group's identity without its projects</li>
 *   <li>Return structured success/failure result</li>
 * </ol>
 *
 * @see ScmServerEndpointPolicy for SSRF protection on user-provided server URLs
 */
@Service
public class GitLabPreflightService implements SelectedRepositoryVerifier {

    private static final Logger log = LoggerFactory.getLogger(GitLabPreflightService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int VERIFY_CONCURRENCY = 4;
    private static final String NEXT_PAGE_HEADER = "X-Next-Page";
    /** Group verification needs no project bodies; project discovery uses its paginated endpoint below. */
    private static final String GROUP_IDENTITY_PATH = "/api/v4/groups/{groupPath}?with_projects=false";

    /** The group's own and its subgroups' projects, without those only shared into it. */
    private static final String GROUP_PROJECTS_PATH = "/api/v4/groups/{group}/projects?include_subgroups=true"
            + "&with_shared=false&simple=true&order_by=path&sort=asc&per_page={perPage}&page={page}";

    private final ScmServerEndpointPolicy endpoints;
    private final GitLabWorkspaceInstance instance;

    public GitLabPreflightService(ScmServerEndpointPolicy endpoints, GitLabWorkspaceInstance instance) {
        this.endpoints = endpoints;
        this.instance = instance;
    }

    /**
     * Validates a GitLab PAT by attempting to authenticate against the GitLab API.
     *
     * @param token         the personal access token
     * @param serverUrl     the default GitLab instance, or null for it
     * @param groupFullPath optional group path for group/project token fallback
     * @return validation result with user/group info on success, or error message on failure
     */
    public GitLabPreflightResponseDTO validateToken(
            String token, @Nullable String serverUrl, @Nullable String groupFullPath) {
        String resolvedUrl = instance.require(serverUrl);

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
                return GitLabPreflightResponseDTO.failure("GitLab returned status " + status + ".");
            }
        } catch (Exception e) {
            log.warn(
                    "GitLab /api/v4/user failed: serverUrl={}, error={}",
                    resolvedUrl,
                    e.getClass().getSimpleName());
            return GitLabPreflightResponseDTO.failure(
                    "We could not connect to the GitLab server. Try again in a moment.");
        }

        // Fallback: validate the token against the group it is meant for
        if (groupFullPath != null && !groupFullPath.isBlank()) {
            return validateGroupToken(token, resolvedUrl, groupFullPath.trim());
        }

        return GitLabPreflightResponseDTO.failure(
                "GitLab did not accept this token. Provide groupFullPath to validate it against the group.");
    }

    private GitLabPreflightResponseDTO validateGroupToken(String token, String serverUrl, String groupFullPath) {
        try {
            GitLabGroupResponse group = endpoints
                    .clientFor(serverUrl)
                    .get()
                    .uri(serverUrl + GROUP_IDENTITY_PATH, groupFullPath)
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
                return GitLabPreflightResponseDTO.failure(
                        "The token does not have access to group " + groupFullPath + ".");
            }
            if (status == 404) {
                return GitLabPreflightResponseDTO.failure("Group " + groupFullPath + " was not found.");
            }
            return GitLabPreflightResponseDTO.failure("GitLab returned status " + status + ".");
        } catch (Exception e) {
            log.warn(
                    "GitLab group token validation failed: serverUrl={}, group={}, error={}",
                    serverUrl,
                    groupFullPath,
                    e.getClass().getSimpleName());
            return GitLabPreflightResponseDTO.failure("We could not validate the group token. Try again in a moment.");
        }

        return GitLabPreflightResponseDTO.failure("We could not validate the group token. Try again in a moment.");
    }

    /**
     * Lists GitLab groups accessible to the provided PAT.
     *
     * @param token     the personal access token
     * @param serverUrl the default GitLab instance, or null for it
     * @return list of accessible groups
     * @throws ResponseStatusException 422 when GitLab refuses the token, 502 when it cannot answer
     */
    public List<GitLabGroupDTO> listAccessibleGroups(String token, @Nullable String serverUrl) {
        String resolvedUrl = instance.require(serverUrl);

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
        } catch (WebClientResponseException.Unauthorized | WebClientResponseException.Forbidden e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "GitLab refused this token. Check it and validate it again.", e);
        } catch (Exception e) {
            log.warn(
                    "Failed to list accessible GitLab groups: serverUrl={}, error={}",
                    resolvedUrl,
                    e.getClass().getSimpleName());
            // An empty list would read as "this token sees no groups"; the caller must learn it failed.
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "GitLab did not return the groups for this token. Try again in a moment.",
                    e);
        }
    }

    /**
     * One page of the projects in {@code groupFullPath} and its subgroups that the PAT can read. Projects that are only
     * shared into the group are left out. Nothing is stored; workspace creation checks a chosen project again.
     *
     * @throws ResponseStatusException 400 without a group, 422 when GitLab refuses the token or the group, 502 when
     *     GitLab cannot answer or does not say whether more pages follow
     */
    public CursorPageDTO<GitLabProjectDTO> listGroupProjects(
            String token,
            @Nullable String serverUrl,
            @Nullable String groupFullPath,
            @Nullable String cursor,
            @Nullable String search) {
        String resolvedUrl = instance.require(serverUrl);
        if (groupFullPath == null || groupFullPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a group to list its projects.");
        }
        String group = groupFullPath.trim();
        Map<String, Object> variables = new HashMap<>();
        variables.put("group", group);
        variables.put("perPage", DEFAULT_PAGE_SIZE);
        int page = pageOf(cursor);
        variables.put("page", page);
        String uri = resolvedUrl + GROUP_PROJECTS_PATH;
        if (search != null && !search.isBlank()) {
            variables.put("search", search.trim());
            uri += "&search={search}";
        }

        @Nullable ResponseEntity<List<GitLabProjectItem>> response;
        try {
            response = endpoints
                    .clientFor(resolvedUrl)
                    .get()
                    .uri(uri, variables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .toEntityList(GitLabProjectItem.class)
                    .block(REQUEST_TIMEOUT);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "GitLab refused this token. Check it and validate it again.",
                        e);
            }
            if (status == 403 || status == 404) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Group " + group + " was not found, or this token cannot read it.",
                        e);
            }
            log.warn("Failed to list GitLab group projects: serverUrl={}, status={}", resolvedUrl, status);
            throw projectsUnavailable(e);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to list GitLab group projects: serverUrl={}, error={}",
                    resolvedUrl,
                    e.getClass().getSimpleName());
            throw projectsUnavailable(e);
        }

        @Nullable List<GitLabProjectItem> items = response == null ? null : response.getBody();
        // Only GitLab's next-page header proves whether the listing ends here.
        @Nullable String next = response == null ? null : response.getHeaders().getFirst(NEXT_PAGE_HEADER);
        if (items == null || next == null) {
            log.warn("GitLab group projects response is incomplete: serverUrl={}", resolvedUrl);
            throw projectsUnavailable();
        }
        Integer nextPage = null;
        if (!next.isBlank()) {
            try {
                nextPage = Integer.valueOf(next.trim());
            } catch (NumberFormatException e) {
                throw projectsUnavailable(e);
            }
            if (nextPage <= page) throw projectsUnavailable();
        }
        List<GitLabProjectDTO> projects = new ArrayList<>();
        for (@Nullable GitLabProjectItem item : items) {
            if (item == null) throw projectsUnavailable();
            Long id = item.id();
            String fullPath = item.pathWithNamespace();
            String name = item.name();
            if (id == null || id <= 0 || fullPath == null || fullPath.isBlank()) throw projectsUnavailable();
            if (!isInside(group, fullPath)) continue;
            projects.add(new GitLabProjectDTO(
                    id, name != null && !name.isBlank() ? name : fullPath, fullPath, item.webUrl()));
        }
        return new CursorPageDTO<>(projects, nextPage == null ? null : nextPage.toString());
    }

    /** GitLab numbers its pages from 1, and that number is the cursor. */
    private static int pageOf(@Nullable String cursor) {
        if (cursor == null) {
            return 1;
        }
        try {
            int page = Integer.parseInt(cursor);
            if (page >= 1) {
                return page;
            }
        } catch (NumberFormatException e) {
            throw invalidCursor(e);
        }
        throw invalidCursor(null);
    }

    private static ResponseStatusException invalidCursor(@Nullable Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cursor is not valid.", cause);
    }

    @Override
    public long resolveAccountId(String token, @Nullable String serverUrl, String accountLogin) {
        String resolvedUrl = instance.require(serverUrl);
        try {
            GitLabGroupResponse group = endpoints
                    .clientFor(resolvedUrl)
                    .get()
                    .uri(resolvedUrl + GROUP_IDENTITY_PATH, accountLogin)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(GitLabGroupResponse.class)
                    .block(REQUEST_TIMEOUT);
            if (group == null
                    || group.id() == null
                    || group.id() <= 0
                    || !accountLogin.equalsIgnoreCase(group.fullPath())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "We could not verify the GitLab group ID. Check the group path and token access.");
            }
            return group.id();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new ResponseStatusException(
                    status == 401 || status == 403 || status == 404
                            ? HttpStatus.UNPROCESSABLE_CONTENT
                            : HttpStatus.BAD_GATEWAY,
                    "We could not verify the GitLab group ID. Check the group path and token access.",
                    e);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "We could not reach GitLab to verify the group ID. Try again.", e);
        }
    }

    /**
     * Reads each selected project with the PAT at the default instance and keeps GitLab's identity for it. The whole
     * check, all projects together, is bounded by one request timeout.
     */
    @Override
    public List<RepositoryIdentity> verify(
            String token, @Nullable String serverUrl, String accountLogin, Collection<Long> nativeIds) {
        String resolvedUrl = instance.require(serverUrl);
        String group = accountLogin.trim();
        WebClient client = endpoints.clientFor(resolvedUrl);
        try {
            List<RepositoryIdentity> verified = Flux.fromIterable(nativeIds)
                    .flatMapSequential(
                            nativeId -> client.get()
                                    .uri(resolvedUrl + "/api/v4/projects/{id}", Map.of("id", nativeId))
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                    .retrieve()
                                    .bodyToMono(GitLabProjectItem.class)
                                    .onErrorMap(WebClientResponseException.class, e -> refusal(nativeId, e))
                                    .switchIfEmpty(Mono.error(() -> misreported(nativeId)))
                                    .map(project -> identity(nativeId, project, group)),
                            VERIFY_CONCURRENCY)
                    .collectList()
                    .block(REQUEST_TIMEOUT);
            if (verified == null || verified.size() != nativeIds.size()) throw selectionUnverifiable();
            return verified;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to verify selected GitLab projects: serverUrl={}, error={}",
                    resolvedUrl,
                    e.getClass().getSimpleName());
            throw selectionUnverifiable(e);
        }
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    private static RepositoryIdentity identity(long nativeId, GitLabProjectItem project, String group) {
        Long reportedId = project.id();
        String fullPath = project.pathWithNamespace();
        if (reportedId == null || reportedId != nativeId || fullPath == null || fullPath.isBlank()) {
            throw misreported(nativeId);
        }
        // The project's own namespace, never a group it is only shared into.
        if (!isInside(group, fullPath)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Project " + nativeId + " is not in group " + group + ".");
        }
        return new RepositoryIdentity(nativeId, fullPath);
    }

    private static ResponseStatusException refusal(long nativeId, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "GitLab refused this token. Check it and validate it again.");
        }
        if (status == 403 || status == 404) {
            return new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Project " + nativeId + " was not found, or this token cannot read it.");
        }
        log.warn("Failed to verify a selected GitLab project: status={}", status);
        return selectionUnverifiable();
    }

    private static ResponseStatusException misreported(long nativeId) {
        log.warn("GitLab misreported a selected project: projectId={}", nativeId);
        return selectionUnverifiable();
    }

    private static ResponseStatusException selectionUnverifiable() {
        return selectionUnverifiable(null);
    }

    private static ResponseStatusException selectionUnverifiable(@Nullable Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "GitLab did not confirm the selected projects. Try again in a moment.", cause);
    }

    private static ResponseStatusException projectsUnavailable() {
        return projectsUnavailable(null);
    }

    private static ResponseStatusException projectsUnavailable(@Nullable Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GitLab did not return the projects of this group. Try again in a moment.",
                cause);
    }

    /** Whether {@code path} lies strictly below {@code group}. GitLab paths compare case-insensitively. */
    private static boolean isInside(String group, String path) {
        return path.toLowerCase(Locale.ROOT).startsWith(group.toLowerCase(Locale.ROOT) + "/");
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
    record GitLabProjectItem(
            @Nullable Long id,
            @Nullable String name,
            @JsonProperty("path_with_namespace") @Nullable String pathWithNamespace,
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
