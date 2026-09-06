package de.tum.cit.aet.hephaestus.contributors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Service for fetching Hephaestus project contributors from GitHub.
 *
 * <p>Workspace-agnostic: This service fetches contributors to the Hephaestus project
 * itself (meta information about the application), not tenant-specific data.
 */
@Service
@WorkspaceAgnostic("Fetches Hephaestus project contributors - meta info, not tenant data")
public class ContributorService {

    private static final Logger log = LoggerFactory.getLogger(ContributorService.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";

    private final WebClient webClient;
    private final @Nullable String githubAuthToken;

    public ContributorService(WebClient.Builder webClientBuilder, ContributorProperties contributorProperties) {
        this.webClient = webClientBuilder
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.githubAuthToken = contributorProperties.authToken();
    }

    /**
     * Get all contributors to the Hephaestus project.
     * Results are cached to avoid excessive GitHub API calls.
     *
     * @return list of contributors sorted by contribution count (descending)
     */
    @Cacheable(value = "contributors", key = "'global'")
    public List<ContributorDTO> getGlobalContributors() {
        log.info("Fetching global contributors from GitHub.");

        if (githubAuthToken == null || githubAuthToken.isBlank()) {
            log.warn("Contributor endpoint requires hephaestus.contributors.github.auth-token to be configured.");
            return new ArrayList<>();
        }

        try {
            Map<Long, ContributorDTO> uniqueContributors = new LinkedHashMap<>();
            collectContributorsForRepository("hephaestus-build", "Hephaestus", uniqueContributors);
            return sortContributors(uniqueContributors);
        } catch (WebClientResponseException e) {
            log.error("HTTP error fetching global contributors: {} - {}", e.getStatusCode(), e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @CacheEvict(value = "contributors", allEntries = true)
    @Scheduled(fixedRateString = "${hephaestus.cache.contributors.evict-rate:3600000}")
    public void evictContributorsCache() {}

    private void collectContributorsForRepository(String owner, String repo, Map<Long, ContributorDTO> accumulator) {
        try {
            List<ContributorDTO.GitHubContributorResponse> contributors = webClient
                    .get()
                    .uri("/repos/{owner}/{repo}/contributors?per_page=100", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + githubAuthToken)
                    .retrieve()
                    .bodyToFlux(ContributorDTO.GitHubContributorResponse.class)
                    .collectList()
                    .block();

            if (contributors != null) {
                for (var contributor : contributors) {
                    safelyAddContributor(contributor, accumulator);
                }
            }
        } catch (WebClientResponseException e) {
            log.warn(
                    "HTTP error fetching contributors for repository {}/{}: {} - {}",
                    owner,
                    repo,
                    e.getStatusCode(),
                    e.getMessage(),
                    e);
        }
    }

    private static final Set<String> EXCLUDED_LOGINS =
            Set.of("semantic-release-bot", "dependabot[bot]", "github-actions[bot]", "renovate[bot]");

    private void safelyAddContributor(
            ContributorDTO.GitHubContributorResponse contributor, Map<Long, ContributorDTO> accumulator) {
        try {
            String login = contributor.login();
            if (login != null && EXCLUDED_LOGINS.contains(login.toLowerCase())) {
                log.debug("Skipping excluded contributor: {}", login);
                return;
            }

            // Fetch full user details to get the name
            String fullName = fetchUserFullName(login);
            ContributorDTO dto = contributor.toContributorDTO(fullName);
            accumulator.putIfAbsent(dto.id(), dto);
        } catch (IllegalArgumentException e) {
            log.error("Failed to convert contributor to DTO", e);
        }
    }

    private @Nullable String fetchUserFullName(@Nullable String login) {
        if (login == null) {
            return null;
        }
        try {
            GitHubUserResponse user = webClient
                    .get()
                    .uri("/users/{login}", login)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + githubAuthToken)
                    .retrieve()
                    .bodyToMono(GitHubUserResponse.class)
                    .block();
            return user != null ? user.name() : null;
        } catch (WebClientResponseException e) {
            log.debug("HTTP error fetching full name for user {}: {} - {}", login, e.getStatusCode(), e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubUserResponse(
            @JsonProperty("login") @Nullable String login,
            @JsonProperty("name") @Nullable String name) {}

    private List<ContributorDTO> sortContributors(Map<Long, ContributorDTO> uniqueContributors) {
        if (uniqueContributors.isEmpty()) {
            return List.of();
        }

        return uniqueContributors.values().stream()
                .sorted(Comparator.comparingInt(ContributorDTO::contributions).reversed())
                .toList();
    }
}
