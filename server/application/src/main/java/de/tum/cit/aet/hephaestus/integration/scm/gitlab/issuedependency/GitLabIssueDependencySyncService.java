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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
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
    private final GitLabProjectAdmission admission;

    public GitLabIssueDependencySyncService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            GitLabTokenService tokenService,
            GitLabRateLimitTracker rateLimitTracker,
            WebClient.Builder webClientBuilder,
            GitLabProjectAdmission admission) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.tokenService = tokenService;
        this.rateLimitTracker = rateLimitTracker;
        this.admission = admission;
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
    public SyncResult syncDependenciesForRepository(
            Long scopeId, Repository repository, @Nullable OffsetDateTime updatedAfter) {
        return admission
                .capture(scopeId, repository)
                .map(read -> syncDependenciesForRepository(scopeId, repository, updatedAfter, read))
                .orElseGet(() -> SyncResult.completed(0));
    }

    public SyncResult syncDependenciesForRepository(
            Long scopeId,
            Repository repository,
            @Nullable OffsetDateTime updatedAfter,
            GitLabProjectAdmission.ProjectRead read) {
        return syncDependenciesInternal(scopeId, repository, updatedAfter, read);
    }

    private SyncResult syncDependenciesInternal(
            Long scopeId,
            Repository repository,
            @Nullable OffsetDateTime updatedAfter,
            GitLabProjectAdmission.ProjectRead read) {
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
                int deps = processIssueDependencies(scopeId, serverUrl, token, nativeId, issue, repository, read);
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

        return admission.current(read) ? SyncResult.completed(totalDeps) : SyncResult.completed(0);
    }

    @SuppressWarnings("unchecked")
    private int processIssueDependencies(
            long scopeId,
            String serverUrl,
            String token,
            long projectId,
            Issue issue,
            Repository repository,
            GitLabProjectAdmission.ProjectRead read) {
        List<Map<String, Object>> links = webClient
                .get()
                .uri(serverUrl + "/api/v4/projects/{projectId}/issues/{iid}/links", projectId, issue.getNumber())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(REQUEST_TIMEOUT);

        return admission
                .write(
                        read,
                        () -> issueRepository
                                .findByIdWithBlockedBy(issue.getId())
                                .map(current -> applyIssueDependencies(links, current, repository, read))
                                .orElse(0))
                .orElse(0);
    }

    private int applyIssueDependencies(
            @Nullable List<Map<String, Object>> links,
            Issue issue,
            Repository repository,
            GitLabProjectAdmission.ProjectRead read) {
        // A response without a body says nothing about this issue's links, so no stored edge is removed.
        if (links == null) return 0;
        if (links.isEmpty()) {
            // No links — clear the stale blockedBy entries this read may change
            clearBlockersInScope(issue, read);
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
                Repository referenced = blocker != null ? blocker.getRepository() : null;
                if (blocker != null && referenced != null && admission.allowsReference(read, referenced)) {
                    currentBlockerIds.add(blocker.getId());
                    if (!issueWithDeps.getBlockedBy().contains(blocker)) {
                        issueWithDeps.getBlockedBy().add(blocker);
                        depsFound++;
                    }
                }
            } else if ("blocks".equals(linkType)) {
                // This issue blocks the linked issue
                Issue blocked = findIssueByLink(link, linkedIid, repository);
                Repository referenced = blocked != null ? blocked.getRepository() : null;
                if (blocked != null && referenced != null && admission.allowsReference(read, referenced)) {
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
        issueWithDeps
                .getBlockedBy()
                .removeIf(blocker -> !currentBlockerIds.contains(blocker.getId()) && mayChange(read, blocker));

        // Bidirectional stale cleanup: remove this issue as blocker from issues
        // it no longer blocks (the "blocks" direction)
        cleanStalBlocksDirection(issue, repository, currentBlockingIds);

        issueRepository.save(issueWithDeps);
        return depsFound;
    }

    /**
     * Clears the blockers this read may change for an issue that has no links in GitLab.
     */
    private void clearBlockersInScope(Issue issue, GitLabProjectAdmission.ProjectRead read) {
        Issue issueWithDeps =
                issueRepository.findByIdWithBlockedBy(issue.getId()).orElse(null);
        if (issueWithDeps != null && issueWithDeps.getBlockedBy().removeIf(blocker -> mayChange(read, blocker))) {
            issueRepository.save(issueWithDeps);
        }
    }

    /**
     * Whether this read may change an edge to {@code blocker}. An edge to a project the workspace may not refer to
     * may be used by another workspace. A refusal to read that project does not establish deletion of the edge.
     */
    private boolean mayChange(GitLabProjectAdmission.ProjectRead read, Issue blocker) {
        Repository referenced = blocker.getRepository();
        return referenced != null && admission.allowsReference(read, referenced);
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
     * Finds an issue from a link response by its project first: an IID is unique only within one project, so
     * another project's issue #5 must never resolve to this project's #5. A link without a project id names this
     * project; an unreadable project id names none.
     */
    @Nullable
    private Issue findIssueByLink(Map<String, Object> link, int linkedIid, Repository repository) {
        Object projectId = link.get("project_id");
        Repository linkedRepository;
        if (projectId == null
                || (projectId instanceof Number number && number.longValue() == repository.getNativeId())) {
            linkedRepository = repository;
        } else if (projectId instanceof Number other && other.longValue() > 0) {
            linkedRepository = repositoryRepository
                    .findByNativeIdAndProviderId(
                            other.longValue(),
                            Objects.requireNonNull(repository.getProvider().getId()))
                    .orElse(null);
        } else {
            linkedRepository = null;
        }
        if (linkedRepository == null) return null;
        return issueRepository
                .findByRepositoryIdAndNumber(linkedRepository.getId(), linkedIid)
                .orElse(null);
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
