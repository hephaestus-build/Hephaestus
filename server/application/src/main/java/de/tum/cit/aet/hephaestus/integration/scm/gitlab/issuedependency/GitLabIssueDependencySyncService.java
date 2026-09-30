package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuedependency;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Syncs issue dependency (blocking) relationships for GitLab issues.
 * <p>
 * Uses the GitLab REST API {@code GET /projects/:id/issues/:iid/links}
 * to fetch linked issues. Maps {@code blocks} and {@code is_blocked_by}
 * link types to the {@code issue_blocking} join table.
 * <p>
 * Supports incremental sync: when {@code updatedAfter} is provided, only
 * issues updated since that timestamp are processed (reducing API calls).
 * <p>
 * Integrates with {@link GitLabRateLimitTracker} to pause when rate limit
 * is critically low.
 * <p>
 * Implements bidirectional stale cleanup: when blocking relationships are
 * removed in GitLab, both directions are cleaned from the local DB.
 * <p>
 * No webhook available (GitLab doesn't fire events for issue link changes).
 * Runs only during scheduled sync.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueDependencySyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueDependencySyncService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Max issues to process per sync cycle to bound rate limit consumption. */
    private static final int MAX_ISSUES_PER_CYCLE = 200;

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitLabTokenService tokenService;
    private final GitLabRateLimitTracker rateLimitTracker;
    private final WebClient webClient;

    public GitLabIssueDependencySyncService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            GitLabTokenService tokenService,
            GitLabRateLimitTracker rateLimitTracker,
            WebClient.Builder webClientBuilder) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.tokenService = tokenService;
        this.rateLimitTracker = rateLimitTracker;
        this.webClient = webClientBuilder.build();
    }

    /**
     * Syncs blocking/blocked-by relationships for issues in a repository.
     * <p>
     * When {@code updatedAfter} is provided, only processes issues updated
     * since that time (incremental). Otherwise processes all issues (full sync).
     *
     * @param scopeId      workspace scope ID
     * @param repository   the repository to sync dependencies for
     * @param updatedAfter optional cutoff for incremental sync
     * @return sync result
     */
    @Transactional
    public SyncResult syncDependenciesForRepository(
            Long scopeId, Repository repository, @Nullable OffsetDateTime updatedAfter) {
        return syncDependenciesInternal(scopeId, repository, updatedAfter);
    }

    private SyncResult syncDependenciesInternal(
            Long scopeId, Repository repository, @Nullable OffsetDateTime updatedAfter) {
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(repository.getNameWithOwner()), "<unknown>");

        // Issues only: GitLab keeps issue IIDs and merge-request IIDs in separate per-project namespaces
        // (issue #5 and MR !5 coexist), both under the single-table `issue` table. Iterating the
        // polymorphic list would call GET /projects/{id}/issues/{iid}/links with a merge request's IID —
        // fetching the *issue* of that IID and attaching its links to the MR row (cross-contaminating the
        // dependency graph), plus one wasted rate-limited API call per MR. Issue links are an issue-domain
        // concept a merge request has no part in.
        List<Issue> allIssues = issueRepository.findAllIssuesByRepositoryId(repository.getId());
        if (allIssues.isEmpty()) return SyncResult.completed(0);

        // Filter to issues updated since cutoff (incremental) or all (full sync)
        List<Issue> issuesToProcess;
        if (updatedAfter != null) {
            Instant cutoff = updatedAfter.toInstant();
            issuesToProcess = allIssues.stream()
                    .filter(i -> i.getNumber() > 0)
                    .filter(i -> i.getUpdatedAt() != null && i.getUpdatedAt().isAfter(cutoff))
                    .toList();
        } else {
            issuesToProcess = allIssues.stream().filter(i -> i.getNumber() > 0).toList();
        }

        if (issuesToProcess.isEmpty()) return SyncResult.completed(0);

        // Cap to avoid rate limit exhaustion
        if (issuesToProcess.size() > MAX_ISSUES_PER_CYCLE) {
            log.info(
                    "Capping dependency sync: project={}, total={}, cap={}",
                    safeProjectPath,
                    issuesToProcess.size(),
                    MAX_ISSUES_PER_CYCLE);
            issuesToProcess = issuesToProcess.subList(0, MAX_ISSUES_PER_CYCLE);
        }

        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String token = tokenService.getAccessToken(scopeId);
        long nativeId = repository.getNativeId();

        int totalDeps = 0;

        for (Issue issue : issuesToProcess) {
            // Check rate limit before each API call
            if (rateLimitTracker.isCritical(scopeId)) {
                log.info(
                        "Rate limit critical, stopping dependency sync: project={}, remaining={}",
                        safeProjectPath,
                        rateLimitTracker.getRemaining(scopeId));
                return SyncResult.abortedRateLimit(totalDeps);
            }

            try {
                int deps = processIssueDependencies(scopeId, serverUrl, token, nativeId, issue, repository);
                totalDeps += deps;
            } catch (Exception e) {
                log.debug(
                        "Failed to fetch issue links for dependencies: project={}, issue=#{}",
                        safeProjectPath,
                        issue.getNumber(),
                        e);
            }
        }

        if (totalDeps > 0) {
            log.info(
                    "GitLab dependency sync: project={}, issuesProcessed={}, dependenciesProcessed={}",
                    safeProjectPath,
                    issuesToProcess.size(),
                    totalDeps);
        }

        return SyncResult.completed(totalDeps);
    }

    /**
     * Overload for backward compatibility — delegates to full sync (no incremental filter).
     */
    @Transactional
    public SyncResult syncDependenciesForRepository(Long scopeId, Repository repository) {
        return syncDependenciesInternal(scopeId, repository, null);
    }

    @SuppressWarnings("unchecked")
    private int processIssueDependencies(
            long scopeId, String serverUrl, String token, long projectId, Issue issue, Repository repository) {
        List<Map<String, Object>> links = webClient
                .get()
                .uri(serverUrl + "/api/v4/projects/{projectId}/issues/{iid}/links", projectId, issue.getNumber())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(REQUEST_TIMEOUT);

        if (links == null || links.isEmpty()) {
            // No links — clear any stale blockedBy entries for this issue
            clearAllBlockers(issue);
            return 0;
        }

        // Fetch issue with blockedBy eagerly loaded to avoid lazy loading issues
        Issue issueWithDeps =
                issueRepository.findByIdWithBlockedBy(issue.getId()).orElse(issue);
        Set<Long> currentBlockerIds = new HashSet<>();
        // Track which issues this issue blocks (for bidirectional cleanup)
        Set<Long> currentBlockingIds = new HashSet<>();

        int depsFound = 0;

        for (Map<String, Object> link : links) {
            String linkType = (String) link.get("link_type");
            if (linkType == null) continue;

            Integer linkedIid = extractIid(link);
            if (linkedIid == null) continue;

            if ("is_blocked_by".equals(linkType)) {
                // The linked issue blocks this issue
                Issue blocker = findIssueByLink(link, linkedIid, repository);
                if (blocker != null) {
                    currentBlockerIds.add(blocker.getId());
                    if (!issueWithDeps.getBlockedBy().contains(blocker)) {
                        issueWithDeps.getBlockedBy().add(blocker);
                        depsFound++;
                    }
                }
            } else if ("blocks".equals(linkType)) {
                // This issue blocks the linked issue
                Issue blocked = findIssueByLink(link, linkedIid, repository);
                if (blocked != null) {
                    currentBlockingIds.add(blocked.getId());
                    Issue blockedWithDeps = issueRepository
                            .findByIdWithBlockedBy(blocked.getId())
                            .orElse(blocked);
                    if (!blockedWithDeps.getBlockedBy().contains(issueWithDeps)) {
                        blockedWithDeps.getBlockedBy().add(issueWithDeps);
                        issueRepository.save(blockedWithDeps);
                        depsFound++;
                    }
                }
            }
        }

        // Remove stale blockers: issues that no longer block this issue
        issueWithDeps.getBlockedBy().removeIf(blocker -> !currentBlockerIds.contains(blocker.getId()));

        // Bidirectional stale cleanup: remove this issue as blocker from issues
        // it no longer blocks (the "blocks" direction)
        cleanStalBlocksDirection(issue, repository, currentBlockingIds);

        issueRepository.save(issueWithDeps);
        return depsFound;
    }

    /**
     * Clears all blockers for an issue that has no links in GitLab.
     */
    private void clearAllBlockers(Issue issue) {
        Issue issueWithDeps =
                issueRepository.findByIdWithBlockedBy(issue.getId()).orElse(null);
        if (issueWithDeps != null && !issueWithDeps.getBlockedBy().isEmpty()) {
            issueWithDeps.getBlockedBy().clear();
            issueRepository.save(issueWithDeps);
        }
    }

    /**
     * Removes this issue from the blockedBy set of issues it no longer blocks.
     */
    private void cleanStalBlocksDirection(Issue blocker, Repository repository, Set<Long> currentBlockingIds) {
        // Find all issues in this repo that have this issue in their blockedBy set
        // We only process issues we know about - a full bidirectional pass
        // would require loading all issues with their blockedBy, which is expensive.
        // Instead, we rely on the fact that when the blocked issue is itself processed,
        // its is_blocked_by links will be reconciled. This method handles the case
        // where the blocked issue hasn't been updated recently (incremental skip).
        // Issues only (see syncDependenciesForRepository): a merge request sharing an issue's IID must
        // never enter the bidirectional blocker cleanup.
        List<Issue> allRepoIssues = issueRepository.findAllIssuesByRepositoryId(repository.getId());
        for (Issue candidate : allRepoIssues) {
            if (currentBlockingIds.contains(candidate.getId())) continue;
            // Check if this candidate currently has blocker in its blockedBy
            Issue candidateWithDeps =
                    issueRepository.findByIdWithBlockedBy(candidate.getId()).orElse(null);
            if (candidateWithDeps != null && candidateWithDeps.getBlockedBy().contains(blocker)) {
                candidateWithDeps.getBlockedBy().remove(blocker);
                issueRepository.save(candidateWithDeps);
            }
        }
    }

    /**
     * Finds an issue from a link response. First tries same-repo, then cross-repo
     * using the project_id from the REST response.
     */
    @Nullable
    private Issue findIssueByLink(Map<String, Object> link, int linkedIid, Repository repository) {
        // Same-repo lookup first (fast path)
        Issue issue = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), linkedIid)
                .orElse(null);
        if (issue != null) return issue;

        // Cross-repo: extract project_id from the link response
        Object projectIdObj = link.get("project_id");
        if (projectIdObj instanceof Number projectIdNum) {
            long linkedProjectId = projectIdNum.longValue();
            if (linkedProjectId != repository.getNativeId()) {
                Repository crossRepo = repositoryRepository
                        .findByNativeIdAndProviderId(
                                linkedProjectId,
                                Objects.requireNonNull(repository.getProvider().getId()))
                        .orElse(null);
                if (crossRepo != null) {
                    return issueRepository
                            .findByRepositoryIdAndNumber(crossRepo.getId(), linkedIid)
                            .orElse(null);
                }
            }
        }
        return null;
    }

    @Nullable
    private static Integer extractIid(Map<String, Object> linkMap) {
        Object iid = linkMap.get("iid");
        if (iid instanceof Number n) return n.intValue();
        if (iid instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
