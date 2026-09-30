package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabNoteSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.BackfillBatchResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;

/**
 * Service for syncing GitLab issues via GraphQL API.
 * <p>
 * Implements cursor-based pagination with {@code updatedAfter} for incremental sync.
 * Confidential issues are skipped. Per-issue error handling ensures one bad issue
 * doesn't abort the entire sync.
 * <p>
 * Nested collections (labels, assignees) are fetched with overflow detection via
 * {@code count} fields and follow-up pagination when the initial page is insufficient.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueSyncService.class);

    private static final String GET_PROJECT_ISSUES_DOCUMENT = "GetProjectIssues";
    private static final String GET_PROJECT_ISSUES_HISTORICAL_DOCUMENT = "GetProjectIssuesHistorical";
    private static final String GET_ISSUE_LABELS_DOCUMENT = "GetIssueLabels";
    private static final String GET_ISSUE_ASSIGNEES_DOCUMENT = "GetIssueAssignees";

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabIssueProcessor issueProcessor;
    private final GitLabNoteSyncService noteSyncService;
    private final GitLabProperties gitLabProperties;

    public GitLabIssueSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabIssueProcessor issueProcessor,
            GitLabNoteSyncService noteSyncService,
            GitLabProperties gitLabProperties) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.issueProcessor = issueProcessor;
        this.noteSyncService = noteSyncService;
        this.gitLabProperties = gitLabProperties;
    }

    /**
     * Syncs all issues for a repository.
     *
     * @param scopeId      the workspace/scope ID for authentication
     * @param repository   the repository to sync issues for
     * @param updatedAfter optional timestamp for incremental sync (null = full sync)
     * @return sync result indicating completion status and count
     */
    public SyncResult syncIssues(Long scopeId, Repository repository, @Nullable OffsetDateTime updatedAfter) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");

        log.info(
                "Starting issue sync: scopeId={}, projectPath={}, updatedAfter={}",
                scopeId,
                safeProjectPath,
                updatedAfter);

        int totalSynced = 0;
        int totalSkipped = 0;
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        boolean rateLimitAborted = false;
        boolean errorAborted = false;
        int reportedTotalCount = -1;

        try {
            do {
                if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                    log.warn("Reached max pagination pages: scopeId={}, projectPath={}", scopeId, safeProjectPath);
                    break;
                }

                graphQlClientProvider.acquirePermission();

                // Wait if rate limited
                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Issue sync interrupted: scopeId={}, projectPath={}", scopeId, safeProjectPath);
                    rateLimitAborted = true;
                    break;
                }

                int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = GitLabSyncConstants.adaptPageSize(GitLabSyncConstants.ISSUE_SYNC_PAGE_SIZE, remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_PROJECT_ISSUES_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .variable("updatedAfter", updatedAfter != null ? updatedAfter.toString() : null)
                        .execute()
                        .block(gitLabProperties.extendedGraphqlTimeout());

                var handleResult = responseHandler.handle(response, "issues for " + safeProjectPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    errorAborted = true;
                    break;
                }

                graphQlClientProvider.recordSuccess();

                // Extract reported total count on first page for post-sync verification
                if (page == 0) {
                    try {
                        Object countField = Objects.requireNonNull(response)
                                .field("project.issues.count")
                                .getValue();
                        if (countField instanceof Number number) {
                            reportedTotalCount = number.intValue();
                            log.info(
                                    "Issue connection reports count={}, projectPath={}",
                                    reportedTotalCount,
                                    safeProjectPath);
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract issue count: projectPath={}", safeProjectPath);
                    }
                }

                // Extract issues from response using dot-notation paths
                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Map<String, Object>> nodes = (List) Objects.requireNonNull(response)
                        .field("project.issues.nodes")
                        .toEntityList(Map.class);

                if (nodes == null || nodes.isEmpty()) {
                    break;
                }

                for (Map<String, Object> issueNode : nodes) {
                    try {
                        if (processIssueNode(issueNode, repository, scopeId) != null) {
                            totalSynced++;
                        } else {
                            totalSkipped++;
                        }
                    } catch (Exception e) {
                        log.warn(
                                "Error processing issue: projectPath={}, issueId={}",
                                safeProjectPath,
                                issueNode.get("iid"),
                                e);
                    }
                }

                // Pagination
                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("project.issues.pageInfo")
                        .toEntity(GitLabPageInfo.class);

                if (pageInfo == null || !pageInfo.hasNextPage()) {
                    break;
                }
                cursor = pageInfo.endCursor();
                if (cursor == null) {
                    log.warn(
                            "Pagination cursor is null despite hasNextPage=true: projectPath={}, page={}",
                            safeProjectPath,
                            page);
                    break;
                }
                if (responseHandler.isPaginationLoop(cursor, previousCursor, "issues for " + safeProjectPath, log)) {
                    errorAborted = true;
                    break;
                }
                previousCursor = cursor;
                page++;

                // Throttle between pages
                try {
                    Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rateLimitAborted = true;
                    break;
                }
            } while (true);
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error("Issue sync failed: scopeId={}, projectPath={}", scopeId, safeProjectPath, e);
            errorAborted = true;
        }

        // Post-sync overflow detection using reported totalCount
        // totalSkipped accounts for confidential issues that are fetched but not persisted
        if (reportedTotalCount >= 0 && totalSynced + totalSkipped < reportedTotalCount) {
            log.warn(
                    "Issue connection overflow detected: projectPath={}, synced={}, reportedCount={}. "
                            + "Some issues may not have been fetched.",
                    safeProjectPath,
                    totalSynced,
                    reportedTotalCount);
        }

        SyncResult result;
        if (errorAborted) {
            result = SyncResult.abortedError(totalSynced);
        } else if (rateLimitAborted) {
            result = SyncResult.abortedRateLimit(totalSynced);
        } else {
            result = SyncResult.completed(totalSynced);
        }

        log.info(
                "Completed issue sync: scopeId={}, projectPath={}, status={}, totalSynced={}, reportedCount={}",
                scopeId,
                safeProjectPath,
                result.status(),
                totalSynced,
                reportedTotalCount);

        return result;
    }

    /**
     * Backfills historical issues for a repository using {@code CREATED_DESC} ordering.
     * Fetches one page of issues at a time, tracking IID range for checkpoint progress.
     *
     * @param scopeId    workspace scope ID
     * @param repository the repository to backfill
     * @param cursor     pagination cursor from a previous batch (null for first batch)
     * @param maxItems   max items to process in this batch
     * @return backfill batch result with IID range and next cursor
     */
    public BackfillBatchResult backfillIssues(
            Long scopeId, Repository repository, @Nullable String cursor, int maxItems) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");

        int totalProcessed = 0;
        int minIid = Integer.MAX_VALUE;
        int maxIid = -1;
        boolean hasMore = false;

        try {
            int remaining = maxItems;
            String currentCursor = cursor;
            String previousBackfillCursor = null;

            while (remaining > 0) {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);

                int rateLimitRemaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = Math.min(
                        GitLabSyncConstants.adaptPageSize(GitLabSyncConstants.ISSUE_SYNC_PAGE_SIZE, rateLimitRemaining),
                        remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
                ClientGraphQlResponse response = client.documentName(GET_PROJECT_ISSUES_HISTORICAL_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("first", pageSize)
                        .variable("after", currentCursor)
                        .execute()
                        .block(gitLabProperties.extendedGraphqlTimeout());

                var handleResult = responseHandler.handle(response, "historical issues for " + safeProjectPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(
                            new GitLabSyncException("Invalid response for historical issues"));
                    return BackfillBatchResult.abortedWithError();
                }
                graphQlClientProvider.recordSuccess();

                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Map<String, Object>> nodes = (List) Objects.requireNonNull(response)
                        .field("project.issues.nodes")
                        .toEntityList(Map.class);

                if (nodes == null || nodes.isEmpty()) break;

                for (Map<String, Object> issueNode : nodes) {
                    try {
                        Object iidObj = issueNode.get("iid");
                        if (iidObj != null) {
                            int iid = iidObj instanceof Number n
                                    ? n.intValue()
                                    : Integer.parseInt(String.valueOf(iidObj));
                            minIid = Math.min(minIid, iid);
                            maxIid = Math.max(maxIid, iid);
                        }
                        processIssueNode(issueNode, repository, scopeId);
                        totalProcessed++;
                    } catch (Exception e) {
                        log.warn(
                                "Error in historical issue backfill: project={}, iid={}",
                                safeProjectPath,
                                issueNode.get("iid"),
                                e);
                    }
                }

                remaining -= nodes.size();

                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("project.issues.pageInfo")
                        .toEntity(GitLabPageInfo.class);

                if (pageInfo == null || !pageInfo.hasNextPage()) break;

                currentCursor = pageInfo.endCursor();
                if (responseHandler.isPaginationLoop(
                        currentCursor, previousBackfillCursor, "historical issues for " + safeProjectPath, log)) {
                    return BackfillBatchResult.abortedWithError();
                }
                previousBackfillCursor = currentCursor;
                hasMore = true;

                Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
            }

            // currentCursor holds the endCursor from the last fetched page.
            // If we stopped because of maxItems limit and there's more data, save it.
            boolean complete = !hasMore;
            String resumeCursor = complete ? null : currentCursor;

            if (totalProcessed > 0) {
                log.info(
                        "Historical issue backfill batch: project={}, processed={}, iidRange=[{},{}]",
                        safeProjectPath,
                        totalProcessed,
                        minIid,
                        maxIid);
            }

            return new BackfillBatchResult(
                    totalProcessed, minIid == Integer.MAX_VALUE ? -1 : minIid, maxIid, resumeCursor, complete, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Historical issue backfill interrupted: project={}", safeProjectPath);
            return BackfillBatchResult.abortedWithError();
        } catch (Exception e) {
            log.warn("Historical issue backfill failed: project={}", safeProjectPath, e);
            return BackfillBatchResult.abortedWithError();
        }
    }

    /**
     * Extracts data from GraphQL response node and delegates to the processor.
     * <p>
     * Label and assignee data is extracted here and passed to the processor, which
     * handles persistence within its {@code @Transactional} boundary.
     * <p>
     * When nested collections (labels, assignees) overflow their initial page,
     * follow-up queries are issued to fetch the remaining items.
     *
     * @return the persisted Issue, or {@code null} if the issue was skipped (e.g. confidential)
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private Issue processIssueNode(Map<String, Object> node, Repository repository, Long scopeId) {
        String globalId = (String) node.get("id");
        String iid = String.valueOf(node.get("iid"));
        String issueContext = sanitizeForLog(repository.getNameWithOwner()) + "#" + iid;
        String title = (String) node.get("title");
        String description = (String) node.get("description");
        String state = (String) node.get("state");
        Boolean confidential = (Boolean) node.get("confidential");
        String webUrl = (String) node.get("webUrl");
        String createdAt = node.get("createdAt") != null ? node.get("createdAt").toString() : null;
        String updatedAt = node.get("updatedAt") != null ? node.get("updatedAt").toString() : null;
        String closedAt = node.get("closedAt") != null ? node.get("closedAt").toString() : null;
        int userNotesCount = node.get("userNotesCount") != null ? ((Number) node.get("userNotesCount")).intValue() : 0;
        Integer milestoneIid = extractMilestoneIid(node);
        String typeName = (String) node.get("type");
        String closedAsDuplicateOfGid = null;
        Map<String, Object> closedAsDuplicateOf = (Map<String, Object>) node.get("closedAsDuplicateOf");
        if (closedAsDuplicateOf != null) {
            closedAsDuplicateOfGid = (String) closedAsDuplicateOf.get("id");
        }

        // Author
        String authorGlobalId = null,
                authorUsername = null,
                authorName = null,
                authorAvatarUrl = null,
                authorWebUrl = null;
        Boolean authorBot = null;
        Map<String, Object> authorMap = (Map<String, Object>) node.get("author");
        if (authorMap != null) {
            authorGlobalId = (String) authorMap.get("id");
            authorUsername = (String) authorMap.get("username");
            authorName = (String) authorMap.get("name");
            authorAvatarUrl = (String) authorMap.get("avatarUrl");
            authorWebUrl = (String) authorMap.get("webUrl");
            authorBot = GitLabUserLookup.botOf(authorMap);
        }

        // Extract labels (with overflow detection and follow-up pagination)
        List<GitLabIssueProcessor.SyncLabelData> syncLabels = null;
        Map<String, Object> labelsMap = (Map<String, Object>) node.get("labels");
        if (labelsMap != null) {
            List<Map<String, Object>> labelNodes = (List<Map<String, Object>>) labelsMap.get("nodes");
            if (labelNodes != null) {
                syncLabels = new ArrayList<>(labelNodes.size());
                for (Map<String, Object> lbl : labelNodes) {
                    syncLabels.add(new GitLabIssueProcessor.SyncLabelData(
                            (String) lbl.get("id"), (String) lbl.get("title"), (String) lbl.get("color")));
                }
                // Detect nested pagination overflow for labels and fetch remaining if needed
                NestedOverflow overflow = detectNestedOverflow(labelsMap, "labels", labelNodes.size(), issueContext);
                if (overflow.hasOverflow()) {
                    List<GitLabIssueProcessor.SyncLabelData> remaining = fetchRemainingLabels(
                            scopeId, repository.getNameWithOwner(), iid, overflow.endCursor(), issueContext);
                    if (remaining != null) {
                        syncLabels.addAll(remaining);
                    }
                }
            }
        }

        // Extract assignees (with overflow detection and follow-up pagination)
        List<GitLabIssueProcessor.SyncAssigneeData> syncAssignees = null;
        Map<String, Object> assigneesMap = (Map<String, Object>) node.get("assignees");
        if (assigneesMap != null) {
            List<Map<String, Object>> assigneeNodes = (List<Map<String, Object>>) assigneesMap.get("nodes");
            if (assigneeNodes != null) {
                syncAssignees = new ArrayList<>(assigneeNodes.size());
                for (Map<String, Object> a : assigneeNodes) {
                    syncAssignees.add(new GitLabIssueProcessor.SyncAssigneeData(
                            (String) a.get("id"),
                            (String) a.get("username"),
                            (String) a.get("name"),
                            (String) a.get("avatarUrl"),
                            (String) a.get("webUrl"),
                            GitLabUserLookup.botOf(a)));
                }
                // Detect nested pagination overflow for assignees and fetch remaining if needed
                NestedOverflow overflow =
                        detectNestedOverflow(assigneesMap, "assignees", assigneeNodes.size(), issueContext);
                if (overflow.hasOverflow()) {
                    List<GitLabIssueProcessor.SyncAssigneeData> remaining = fetchRemainingAssignees(
                            scopeId, repository.getNameWithOwner(), iid, overflow.endCursor(), issueContext);
                    if (remaining != null) {
                        syncAssignees.addAll(remaining);
                    }
                }
            }
        }

        var syncData = new GitLabIssueProcessor.SyncIssueData(
                globalId,
                iid,
                title,
                description,
                state,
                Boolean.TRUE.equals(confidential),
                webUrl,
                createdAt,
                updatedAt,
                closedAt,
                authorGlobalId,
                authorUsername,
                authorName,
                authorAvatarUrl,
                authorWebUrl,
                authorBot,
                userNotesCount,
                syncLabels,
                syncAssignees,
                milestoneIid,
                typeName,
                closedAsDuplicateOfGid);
        Issue issue = issueProcessor.processFromSync(syncData, repository, scopeId);

        // Sync notes for this issue if it has comments and wasn't skipped
        if (issue != null && userNotesCount > 0) {
            try {
                noteSyncService.syncNotesForIssue(scopeId, repository, Integer.parseInt(iid), issue);
            } catch (Exception e) {
                log.error("Note sync failed for issue: context={}", issueContext, e);
            }
        }

        return issue;
    }

    // Milestone extraction

    @SuppressWarnings("unchecked")
    @Nullable
    private static Integer extractMilestoneIid(Map<String, Object> node) {
        Map<String, Object> milestone = (Map<String, Object>) node.get("milestone");
        if (milestone == null) {
            return null;
        }
        Object iid = milestone.get("iid");
        if (iid == null) {
            return null;
        }
        try {
            return Integer.parseInt(iid.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Nested overflow detection and follow-up pagination

    /**
     * Result of checking a nested GraphQL connection for overflow.
     *
     * @param hasOverflow whether more items exist beyond what was fetched
     * @param endCursor   the cursor for fetching the next page (null if no overflow)
     * @param count       the total count reported by the connection (-1 if unavailable)
     */
    private record NestedOverflow(
            boolean hasOverflow, @Nullable String endCursor, int count) {}

    /**
     * Checks if a nested GraphQL connection has more pages than were fetched.
     * Uses both {@code count} and {@code pageInfo.hasNextPage} for detection.
     */
    @SuppressWarnings("unchecked")
    private static NestedOverflow detectNestedOverflow(
            Map<String, Object> connectionMap, String connectionName, int fetchedCount, String context) {
        int count = -1;
        Object countField = connectionMap.get("count");
        if (countField instanceof Number number) {
            count = number.intValue();
        }

        Map<String, Object> pageInfo = (Map<String, Object>) connectionMap.get("pageInfo");
        boolean hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
        String endCursor = pageInfo != null ? (String) pageInfo.get("endCursor") : null;

        boolean overflow = hasNextPage || (count >= 0 && count > fetchedCount);

        if (overflow) {
            log.warn(
                    "GraphQL nested connection overflow: connection={}, fetchedCount={}, count={}, "
                            + "hasNextPage={}, context={}. Will attempt follow-up pagination.",
                    connectionName,
                    fetchedCount,
                    count,
                    hasNextPage,
                    context);
        }

        return new NestedOverflow(overflow, endCursor, count);
    }

    /**
     * Fetches remaining labels for an issue via follow-up paginated queries.
     *
     * @param scopeId     the workspace/scope ID for authentication
     * @param projectPath the full project path
     * @param iid         the issue IID
     * @param afterCursor the cursor from the initial page's endCursor
     * @param context     logging context string
     * @return additional labels fetched, or null on failure
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private List<GitLabIssueProcessor.SyncLabelData> fetchRemainingLabels(
            Long scopeId, String projectPath, String iid, @Nullable String afterCursor, String context) {
        if (afterCursor == null) {
            log.warn("Cannot fetch remaining labels: endCursor is null, context={}", context);
            return null;
        }

        List<GitLabIssueProcessor.SyncLabelData> allRemaining = new ArrayList<>();
        String cursor = afterCursor;
        String previousLabelCursor = null;
        int followUpPages = 0;

        try {
            while (cursor != null && followUpPages < GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_ISSUE_LABELS_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("iid", iid)
                        .variable("first", GitLabSyncConstants.LARGE_PAGE_SIZE)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "remaining labels for " + context, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    break;
                }

                graphQlClientProvider.recordSuccess();

                // Navigate: project.issues.nodes[0].labels
                @SuppressWarnings("rawtypes")
                List issueNodesRaw = Objects.requireNonNull(response)
                        .field("project.issues.nodes")
                        .toEntityList(Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> issueNodes = (List<Map<String, Object>>) issueNodesRaw;

                if (issueNodes == null || issueNodes.isEmpty()) {
                    break;
                }

                Map<String, Object> labelsMap =
                        (Map<String, Object>) issueNodes.get(0).get("labels");
                if (labelsMap == null) {
                    break;
                }

                List<Map<String, Object>> labelNodes = (List<Map<String, Object>>) labelsMap.get("nodes");
                if (labelNodes == null || labelNodes.isEmpty()) {
                    break;
                }

                for (Map<String, Object> lbl : labelNodes) {
                    allRemaining.add(new GitLabIssueProcessor.SyncLabelData(
                            (String) lbl.get("id"), (String) lbl.get("title"), (String) lbl.get("color")));
                }

                // Check for more pages
                Map<String, Object> pageInfo = (Map<String, Object>) labelsMap.get("pageInfo");
                if (pageInfo == null || !Boolean.TRUE.equals(pageInfo.get("hasNextPage"))) {
                    break;
                }
                cursor = (String) pageInfo.get("endCursor");
                if (responseHandler.isPaginationLoop(
                        cursor, previousLabelCursor, "remaining labels for " + context, log)) {
                    break;
                }
                previousLabelCursor = cursor;
                followUpPages++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during label follow-up pagination: context={}", context);
        } catch (Exception e) {
            log.warn("Error during label follow-up pagination: context={}", context, e);
        }

        if (!allRemaining.isEmpty()) {
            log.info("Fetched {} additional labels via follow-up pagination: context={}", allRemaining.size(), context);
        }

        return allRemaining;
    }

    /**
     * Fetches remaining assignees for an issue via follow-up paginated queries.
     *
     * @param scopeId     the workspace/scope ID for authentication
     * @param projectPath the full project path
     * @param iid         the issue IID
     * @param afterCursor the cursor from the initial page's endCursor
     * @param context     logging context string
     * @return additional assignees fetched, or null on failure
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private List<GitLabIssueProcessor.SyncAssigneeData> fetchRemainingAssignees(
            Long scopeId, String projectPath, String iid, @Nullable String afterCursor, String context) {
        if (afterCursor == null) {
            log.warn("Cannot fetch remaining assignees: endCursor is null, context={}", context);
            return null;
        }

        List<GitLabIssueProcessor.SyncAssigneeData> allRemaining = new ArrayList<>();
        String cursor = afterCursor;
        String previousAssigneeCursor = null;
        int followUpPages = 0;

        try {
            while (cursor != null && followUpPages < GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_ISSUE_ASSIGNEES_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("iid", iid)
                        .variable("first", GitLabSyncConstants.LARGE_PAGE_SIZE)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "remaining assignees for " + context, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    break;
                }

                graphQlClientProvider.recordSuccess();

                // Navigate: project.issues.nodes[0].assignees
                @SuppressWarnings("rawtypes")
                List assigneeIssueNodesRaw = Objects.requireNonNull(response)
                        .field("project.issues.nodes")
                        .toEntityList(Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> issueNodes = (List<Map<String, Object>>) assigneeIssueNodesRaw;

                if (issueNodes == null || issueNodes.isEmpty()) {
                    break;
                }

                Map<String, Object> assigneesMap =
                        (Map<String, Object>) issueNodes.get(0).get("assignees");
                if (assigneesMap == null) {
                    break;
                }

                List<Map<String, Object>> assigneeNodes = (List<Map<String, Object>>) assigneesMap.get("nodes");
                if (assigneeNodes == null || assigneeNodes.isEmpty()) {
                    break;
                }

                for (Map<String, Object> a : assigneeNodes) {
                    allRemaining.add(new GitLabIssueProcessor.SyncAssigneeData(
                            (String) a.get("id"),
                            (String) a.get("username"),
                            (String) a.get("name"),
                            (String) a.get("avatarUrl"),
                            (String) a.get("webUrl"),
                            GitLabUserLookup.botOf(a)));
                }

                // Check for more pages
                Map<String, Object> pageInfo = (Map<String, Object>) assigneesMap.get("pageInfo");
                if (pageInfo == null || !Boolean.TRUE.equals(pageInfo.get("hasNextPage"))) {
                    break;
                }
                cursor = (String) pageInfo.get("endCursor");
                if (responseHandler.isPaginationLoop(
                        cursor, previousAssigneeCursor, "remaining assignees for " + context, log)) {
                    break;
                }
                previousAssigneeCursor = cursor;
                followUpPages++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during assignee follow-up pagination: context={}", context);
        } catch (Exception e) {
            log.warn("Error during assignee follow-up pagination: context={}", context, e);
        }

        if (!allRemaining.isEmpty()) {
            log.info(
                    "Fetched {} additional assignees via follow-up pagination: context={}",
                    allRemaining.size(),
                    context);
        }

        return allRemaining;
    }
}
