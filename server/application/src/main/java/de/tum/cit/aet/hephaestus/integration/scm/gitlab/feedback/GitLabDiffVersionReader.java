package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebClients;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
class GitLabDiffVersionReader {
    private static final Logger log = LoggerFactory.getLogger(GitLabDiffVersionReader.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private final GitLabTokenService tokens;
    private final WebClient client;

    GitLabDiffVersionReader(GitLabTokenService tokens, GitLabWebClients builder) {
        this.tokens = tokens;
        client = builder.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    // Only the reviewed version can prove a line unpositionable; any other answer stops before a write.
    GitLabDiffPositions.Version read(long workspaceId, GitLabMrResolver.MrCoordinates mr, ItemVersion pinned) {
        String head = ScmVersion.reviewedHead(pinned);
        String root = mergeRequestApi(tokens.resolveServerUrl(workspaceId), mr) + "/versions";
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                var versions = get(workspaceId, root + "?per_page=" + PAGE_SIZE + "&page=" + page)
                        .bodyToMono(new ParameterizedTypeReference<List<GitLabDiffPositions.@Nullable Version>>() {})
                        .block(GRAPHQL_TIMEOUT);
                if (versions == null) throw new FeedbackDeliveryException("GitLab diff versions returned no answer");
                for (var version : versions) {
                    if (version == null) throw new FeedbackDeliveryException("A GitLab diff version was not read");
                    if (!head.equals(version.head()) || !Objects.equals(pinned.baseVersion(), version.base())) continue;
                    if (version.id() <= 0) throw new FeedbackDeliveryException("A GitLab diff version has no id");
                    var detail = get(workspaceId, root + "/" + version.id())
                            .bodyToMono(GitLabDiffPositions.Version.class)
                            .block(GRAPHQL_TIMEOUT);
                    var diffs = detail == null ? null : detail.diffs();
                    if (detail == null
                            || detail.id() != version.id()
                            || !head.equals(detail.head())
                            || !Objects.equals(pinned.baseVersion(), detail.base())
                            || !Objects.equals(version.start(), detail.start())
                            || !GitLabDiffPositions.validSha(detail.start())
                            || diffs == null
                            || diffs.stream().anyMatch(Objects::isNull))
                        throw new FeedbackDeliveryException("A GitLab diff version was not read completely");
                    return detail;
                }
                if (versions.size() < PAGE_SIZE) {
                    log.warn(
                            "GitLab has no diff version for the reviewed pair: workspaceId={}, project={}, iid={}",
                            workspaceId,
                            sanitizeForLog(mr.projectPath()),
                            mr.iid());
                    throw new FeedbackDeliveryException("GitLab has no diff version for the reviewed pair");
                }
            }
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FeedbackDeliveryException("GitLab diff versions could not be read", e);
        }
        throw new FeedbackDeliveryException("GitLab diff version pagination did not reach its end");
    }

    // One encoding of the merge request's REST address serves every GitLab feedback call.
    static String mergeRequestApi(String serverUrl, GitLabMrResolver.MrCoordinates mr) {
        return serverUrl.replaceAll("/+$", "") + "/api/v4/projects/"
                + URLEncoder.encode(mr.projectPath(), StandardCharsets.UTF_8) + "/merge_requests/" + mr.iid();
    }

    private WebClient.ResponseSpec get(long workspaceId, String url) {
        return client.get()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.getAccessToken(workspaceId))
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspaceId)
                .retrieve();
    }
}
