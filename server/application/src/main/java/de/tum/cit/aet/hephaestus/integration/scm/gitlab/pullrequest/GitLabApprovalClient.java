package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/** Reads native standing-row creation dates; the approvals route does not expose the originally approved commit. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabApprovalClient {
    private static final Logger log = LoggerFactory.getLogger(GitLabApprovalClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final GitLabTokenService tokenService;
    private final WebClient webClient;

    public GitLabApprovalClient(GitLabTokenService tokenService, WebClient.Builder builder) {
        this.tokenService = tokenService;
        this.webClient = builder.build();
    }

    public @Nullable Snapshot read(Long scopeId, long projectId, int iid) {
        try {
            return webClient
                    .get()
                    .uri(
                            tokenService.resolveServerUrl(scopeId)
                                    + "/api/v4/projects/{project}/merge_requests/{iid}/approvals",
                            projectId,
                            iid)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken(scopeId))
                    .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                    .retrieve()
                    .bodyToMono(Snapshot.class)
                    .block(REQUEST_TIMEOUT);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not read native approval dates: projectId={}, iid={}, reason={}",
                    projectId,
                    iid,
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    public record Approver(@Nullable Long id) {}

    public record Approval(
            @Nullable Approver user,
            @JsonProperty("approved_at") @Nullable Instant approvedAt) {}

    public record Snapshot(
            @Nullable Long id,
            @Nullable Integer iid,
            @JsonProperty("project_id") @Nullable Long projectId,
            @Nullable String state,
            @JsonProperty("updated_at") @Nullable Instant updatedAt,
            @Nullable String title,
            @Nullable String description,
            @JsonProperty("approved_by") @Nullable List<@Nullable Approval> approvedBy) {
        /** Absent CE metadata is identified by the authenticated request path; supplied identity must agree. */
        public @Nullable Map<Long, Instant> datesFor(
                long project,
                long mergeRequest,
                int number,
                List<GitLabMergeRequestProcessor.SyncUserData> acceptedApprovers) {
            if ((id != null && id != mergeRequest)
                    || (iid != null && iid != number)
                    || (projectId != null && projectId != project)
                    || (state != null && !"merged".equalsIgnoreCase(state))
                    || approvedBy == null) {
                return null;
            }
            Set<Long> expected = new HashSet<>();
            try {
                for (var user : acceptedApprovers) {
                    if (user.globalId() == null) return null;
                    long nativeId = GitLabSyncConstants.extractNumericId(user.globalId());
                    if (nativeId <= 0) return null;
                    expected.add(nativeId);
                }
            } catch (IllegalArgumentException failure) {
                return null;
            }
            Set<Long> actual = new HashSet<>();
            Map<Long, Instant> dates = new HashMap<>();
            Map<Long, @Nullable Instant> seen = new HashMap<>();
            Set<Long> conflicts = new HashSet<>();
            for (Approval approval : approvedBy) {
                if (approval == null) return null;
                var approver = approval.user();
                if (approver == null || approver.id() == null || approver.id() <= 0) return null;
                long user = approver.id();
                actual.add(user);
                if (seen.containsKey(user) && !Objects.equals(seen.get(user), approval.approvedAt()))
                    conflicts.add(user);
                seen.put(user, approval.approvedAt());
                if (approval.approvedAt() != null) dates.put(user, approval.approvedAt());
            }
            if (!actual.equals(expected)) return null;
            conflicts.forEach(dates::remove);
            return Map.copyOf(dates);
        }
    }
}
