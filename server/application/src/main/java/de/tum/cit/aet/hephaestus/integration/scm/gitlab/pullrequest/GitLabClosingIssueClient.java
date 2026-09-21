package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The issues GitLab records a merge request as closing, from
 * {@code GET /projects/:id/merge_requests/:iid/closes_issues} — a REST route, since the GraphQL merge
 * request exposes no closing references. The route returns issues of any project; only the iids of
 * this project's own issues are kept, and a reference into another project is dropped, for the same
 * reason a number from elsewhere must not resolve here.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabClosingIssueClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClosingIssueClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final GitLabTokenService tokenService;
    private final WebClient webClient;

    public GitLabClosingIssueClient(GitLabTokenService tokenService, WebClient.Builder webClientBuilder) {
        this.tokenService = tokenService;
        this.webClient = webClientBuilder.build();
    }

    /**
     * @return the iids of this project's issues the merge request closes, or {@code null} when the
     *     route could not be read, which leaves the stored set as it was
     */
    public @Nullable List<Integer> closesIssues(Long scopeId, long projectId, int mrIid) {
        try {
            String serverUrl = tokenService.resolveServerUrl(scopeId);
            String token = tokenService.getAccessToken(scopeId);
            List<Map<String, Object>> issues = webClient
                    .get()
                    .uri(
                            serverUrl + "/api/v4/projects/{projectId}/merge_requests/{iid}/closes_issues?per_page=100",
                            projectId,
                            mrIid)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block(REQUEST_TIMEOUT);
            return ownIids(issues, projectId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the issues a merge request closes: projectId={}, iid={}, reason={}",
                    projectId,
                    mrIid,
                    sanitizeForLog(e.getMessage()));
            return null;
        }
    }

    static List<Integer> ownIids(@Nullable List<Map<String, Object>> issues, long projectId) {
        List<Integer> iids = new ArrayList<>();
        if (issues == null) {
            return iids;
        }
        for (Map<String, Object> issue : issues) {
            Object iid = issue.get("iid");
            Object project = issue.get("project_id");
            if (iid instanceof Number number && project instanceof Number owner && owner.longValue() == projectId) {
                iids.add(number.intValue());
            }
        }
        return iids;
    }
}
