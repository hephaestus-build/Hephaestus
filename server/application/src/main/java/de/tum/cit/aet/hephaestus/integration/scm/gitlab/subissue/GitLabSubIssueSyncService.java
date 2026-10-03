package de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import java.time.Duration;
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
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs sub-issue (parent-child) relationships for GitLab issues.
 * <p>
 * Uses the GitLab GraphQL Work Item hierarchy API to fetch parent-child
 * relationships. GitLab models hierarchy through WorkItem widgets, not
 * through issue links (the REST issue links API does not support
 * {@code is_child_of}/{@code is_parent_of} — those types do not exist).
 * <p>
 * Uses a single paginated GraphQL query per repository.
 * <p>
 * Includes stale parent cleanup: if a parent-child relationship is
 * removed in GitLab, the local DB is updated to clear the parent.
 * <p>
 * No webhook support — runs only during scheduled sync.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabSubIssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabSubIssueSyncService.class);
    private static final String DOCUMENT_NAME = "GetProjectWorkItemHierarchy";
    private static final int PAGE_SIZE = 50;

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;

    public GitLabSubIssueSyncService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
    }

    /**
     * Syncs parent-child relationships for all issues in a repository
     * using the GitLab GraphQL WorkItem hierarchy API.
     *
     * @param scopeId    workspace scope ID
     * @param repository the repository to sync sub-issues for
     * @return sync result
     */
    @Transactional
    public SyncResult syncSubIssuesForRepository(Long scopeId, Repository repository) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");

        // Issues only: GitLab keeps issue IIDs and merge-request IIDs in separate per-project
        // namespaces (issue #5 and MR !5 coexist), and both live in the single-table `issue` table. The
        // polymorphic list would collide the two in issueByIid (last-writer-wins) and let a merge request
        // be mistaken for the issue of the same IID — writing a bogus parent onto the MR while the real
        // issue never gets parented, and clearStaleParents clearing the real issue's legitimate parent.
        // Sub-issue parenting is an issue-domain concept a merge request has no part in.
        List<Issue> issues = issueRepository.findAllIssuesByRepositoryId(repository.getId());
        if (issues.isEmpty()) return SyncResult.completed(0);

        // Build IID -> Issue lookup map
        Map<Integer, Issue> issueByIid = new HashMap<>();
        for (Issue issue : issues) {
            if (issue.getNumber() > 0) {
                issueByIid.put(issue.getNumber(), issue);
            }
        }

        // Track which issues have a parent according to GitLab
        Set<Long> issuesWithParentInGitLab = new HashSet<>();
        int totalLinked = 0;

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        boolean partialView = false;

        try {
            do {
                if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                    log.warn("Reached max pagination: project={}", safeProjectPath);
                    partialView = true;
                    break;
                }

                graphQlClientProvider.acquirePermission();

                ClientGraphQlResponse response = client.documentName(DOCUMENT_NAME)
                        .variable("fullPath", projectPath)
                        .variable("first", PAGE_SIZE)
                        .variable("after", cursor)
                        .execute()
                        .block(Duration.ofSeconds(30));

                var handleResult = responseHandler.handle(response, "sub-issues for " + safeProjectPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    partialView = true;
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodes = (List<Map<String, Object>>) (List<?>) Objects.requireNonNull(response)
                        .field("project.workItems.nodes")
                        .toEntityList(Map.class);
                if (nodes == null) {
                    // A response the handler passed but whose nodes field is missing is a page we did
                    // not read, not an empty project.
                    partialView = true;
                    break;
                }
                if (nodes.isEmpty()) break;

                for (Map<String, Object> node : nodes) {
                    int linked = processWorkItemNode(node, issueByIid, issuesWithParentInGitLab, repository);
                    totalLinked += linked;
                }

                // Pagination
                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("project.workItems.pageInfo")
                        .toEntity(GitLabPageInfo.class);
                if (pageInfo == null || !pageInfo.hasNextPage()) break;
                cursor = pageInfo.endCursor();
                if (responseHandler.isPaginationLoop(
                        cursor, previousCursor, "sub-issues for " + safeProjectPath, log)) {
                    partialView = true;
                    break;
                }
                previousCursor = cursor;
                page++;
            } while (cursor != null);

            // Clearing parentIssue for issues GitLab no longer reports a parent for is only safe on a
            // COMPLETE walk: issuesWithParentInGitLab is built page by page, so after a run that stopped
            // early every issue whose parent lived on an unfetched page looks parentless and would have
            // its real link deleted. Anything that can end the loop before the last page therefore has
            // to set partialView.
            int staleCleared = partialView ? 0 : clearStaleParents(issues, issuesWithParentInGitLab);
            if (partialView) {
                log.warn("Skipping stale-parent cleanup after an incomplete walk: project={}", safeProjectPath);
            }

            if (totalLinked > 0 || staleCleared > 0) {
                log.info(
                        "GitLab sub-issue sync: project={}, linked={}, staleCleared={}",
                        safeProjectPath,
                        totalLinked,
                        staleCleared);
            }

            return SyncResult.completed(totalLinked);
        } catch (Exception e) {
            log.warn("Failed sub-issue sync: project={}", safeProjectPath, e);
            return SyncResult.abortedError(0);
        }
    }

    @SuppressWarnings("unchecked")
    private int processWorkItemNode(
            Map<String, Object> node,
            Map<Integer, Issue> issueByIid,
            Set<Long> issuesWithParentInGitLab,
            Repository repository) {
        String iidStr = node.get("iid") != null ? node.get("iid").toString() : null;
        if (iidStr == null) return 0;

        int iid;
        try {
            iid = Integer.parseInt(iidStr);
        } catch (NumberFormatException e) {
            return 0;
        }

        Issue issue = issueByIid.get(iid);
        if (issue == null) return 0;

        // Extract hierarchy widget
        List<Map<String, Object>> widgets = (List<Map<String, Object>>) node.get("widgets");
        if (widgets == null) return 0;

        Integer parentIid = null;
        String parentNamespacePath = null;
        for (Map<String, Object> widget : widgets) {
            String widgetType = (String) widget.get("type");
            if ("HIERARCHY".equals(widgetType)) {
                Map<String, Object> parent = (Map<String, Object>) widget.get("parent");
                if (parent != null) {
                    parentIid = extractIid(parent);
                    parentNamespacePath = extractNamespacePath(parent);
                }
                break;
            }
        }

        if (parentIid != null) {
            issuesWithParentInGitLab.add(issue.getId());
            Issue parentIssue = issueByIid.get(parentIid);
            if (parentIssue == null) {
                // Try same-repo DB lookup first
                parentIssue = issueRepository
                        .findByRepositoryIdAndNumber(repository.getId(), parentIid)
                        .orElse(null);
            }
            if (parentIssue == null && parentNamespacePath != null) {
                // Cross-repo: look up parent in a different monitored repo by namespace path
                parentIssue = findCrossRepoIssue(parentNamespacePath, parentIid, repository);
            }

            if (parentIssue != null && !parentIssue.getId().equals(issue.getId())) {
                if (issue.getParentIssue() == null
                        || !issue.getParentIssue().getId().equals(parentIssue.getId())) {
                    issue.setParentIssue(parentIssue);
                    issueRepository.save(issue);
                    return 1;
                }
            }
        }

        return 0;
    }

    /**
     * Clears parentIssue for issues that had a parent locally but no longer have one in GitLab.
     */
    private int clearStaleParents(List<Issue> issues, Set<Long> issuesWithParentInGitLab) {
        int cleared = 0;
        for (Issue issue : issues) {
            if (issue.getParentIssue() != null && !issuesWithParentInGitLab.contains(issue.getId())) {
                issue.setParentIssue(null);
                issueRepository.save(issue);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Looks up an issue in a different monitored repository by its namespace path (fullPath) and IID.
     */
    @Nullable
    private Issue findCrossRepoIssue(String namespacePath, int iid, Repository currentRepo) {
        Repository crossRepo =
                repositoryRepository.findByNameWithOwner(namespacePath).orElse(null);
        if (crossRepo == null || crossRepo.getId().equals(currentRepo.getId())) {
            return null;
        }
        return issueRepository
                .findByRepositoryIdAndNumber(crossRepo.getId(), iid)
                .orElse(null);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static String extractNamespacePath(Map<String, Object> parentMap) {
        Object ns = parentMap.get("namespace");
        if (ns instanceof Map<?, ?> nsMap) {
            Object fullPath = nsMap.get("fullPath");
            return fullPath != null ? fullPath.toString() : null;
        }
        return null;
    }

    @Nullable
    private static Integer extractIid(Map<String, Object> map) {
        Object iid = map.get("iid");
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
