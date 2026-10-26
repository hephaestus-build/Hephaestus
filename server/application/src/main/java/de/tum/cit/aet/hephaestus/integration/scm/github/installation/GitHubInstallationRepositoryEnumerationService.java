package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Enumerates repositories accessible to a GitHub App installation so we can backfill monitors.
 * <p>
 * <b>Why REST API instead of GraphQL:</b>
 * GitHub's GraphQL API does not expose an endpoint to list installation repositories.
 * The only way to enumerate repositories accessible to a GitHub App installation is via
 * the REST API endpoint {@code GET /installation/repositories}. This is a GitHub API limitation,
 * not a design choice.
 * <p>
 * Similarly, installation token minting ({@code POST /app/installations/{id}/access_tokens})
 * is REST-only. These are the only REST endpoints in the integration.scm layer.
 *
 * @see <a href="https://docs.github.com/en/rest/apps/installations#list-repositories-accessible-to-the-app-installation">GitHub REST API - List installation repositories</a>
 */
@Service
public class GitHubInstallationRepositoryEnumerationService {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationRepositoryEnumerationService.class);
    private static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024; // 16MB for large repo lists

    private final GitHubAppTokenService gitHubAppTokenService;
    private final WebClient webClient;

    public GitHubInstallationRepositoryEnumerationService(
            GitHubAppTokenService gitHubAppTokenService, WebClient.Builder webClients) {
        this.gitHubAppTokenService = gitHubAppTokenService;

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE))
                .build();

        this.webClient = webClients
                .clientConnector(WebClientConnectors.systemDns())
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .exchangeStrategies(strategies)
                .build();
    }

    // Pattern to extract URLs from Link header: <url>; rel="next"
    private static final Pattern LINK_NEXT_PATTERN = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    /**
     * Return a lightweight snapshot of every repository visible to the installation.
     * Handles pagination via GitHub's Link header to fetch all repositories, not just the first page.
     * Falls back to an empty list if GitHub App credentials are not configured or enumeration fails.
     */
    public List<InstallationRepositorySnapshot> enumerate(long installationId) {
        if (!gitHubAppTokenService.isConfigured()) {
            log.warn("Skipped installation enumeration: reason=missingCredentials, installationId={}", installationId);
            return List.of();
        }

        try {
            String installationToken = gitHubAppTokenService.getOrRefreshToken(installationId);
            List<InstallationRepositorySnapshot> allRepositories = new ArrayList<>();
            String nextUrl = GITHUB_API_BASE_URL + "/installation/repositories?per_page=100";
            int totalCount = 0;
            int pageCount = 0;

            while (nextUrl != null) {
                pageCount++;
                final String currentUrl = nextUrl;

                ResponseEntity<InstallationRepositoriesResponse> responseEntity = webClient
                        .get()
                        .uri(currentUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                        .retrieve()
                        .toEntity(InstallationRepositoriesResponse.class)
                        .block();

                if (responseEntity == null || responseEntity.getBody() == null) {
                    log.warn("Received empty response: installationId={}, pageNumber={}", installationId, pageCount);
                    break;
                }

                InstallationRepositoriesResponse response = responseEntity.getBody();
                if (pageCount == 1) {
                    totalCount = response.totalCount();
                }

                if (response.repositories() != null) {
                    response.repositories().stream().map(this::toSnapshot).forEach(allRepositories::add);
                }

                // Parse Link header for next page URL
                nextUrl = parseLinkHeaderForNext(responseEntity.getHeaders().getFirst("Link"));
            }

            log.info(
                    "Enumerated installation repositories: installationId={}, repoCount={}, totalReported={}, pageCount={}",
                    installationId,
                    allRepositories.size(),
                    totalCount,
                    pageCount);

            return allRepositories;
        } catch (Exception e) {
            log.warn("Failed to enumerate installation repositories: installationId={}", installationId, e);
            return List.of();
        }
    }

    /**
     * Parse the Link header to extract the "next" page URL.
     * GitHub Link header format: {@code <url>; rel="next", <url>; rel="last"}
     *
     * @param linkHeader the Link header value, may be null
     * @return the next page URL, or null if there is no next page
     */
    private @Nullable String parseLinkHeaderForNext(@Nullable String linkHeader) {
        if (linkHeader == null) {
            return null;
        }
        Matcher matcher = LINK_NEXT_PATTERN.matcher(linkHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    private InstallationRepositorySnapshot toSnapshot(RepositoryDto repo) {
        return new InstallationRepositorySnapshot(repo.id(), repo.fullName(), repo.name(), repo.isPrivate());
    }

    public record InstallationRepositorySnapshot(long id, String nameWithOwner, String name, boolean isPrivate) {}

    // DTOs for GitHub REST API response
    private record InstallationRepositoriesResponse(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("repositories") List<RepositoryDto> repositories) {}

    private record RepositoryDto(
            long id,
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("private") boolean isPrivate) {}
}
