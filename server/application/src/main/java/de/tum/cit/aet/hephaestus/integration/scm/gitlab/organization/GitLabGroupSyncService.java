package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.GROUP_PROJECTS_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.adaptPageSize;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractEntityId;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * Service for syncing GitLab groups and their projects via GraphQL API.
 * <p>
 * This service:
 * <ul>
 *   <li>Fetches group metadata via {@code GetGroup} query → delegates to {@link GitLabGroupProcessor}</li>
 *   <li>Fetches all projects in a group via paginated {@code GetGroupProjects} query</li>
 *   <li>Delegates each project to {@link GitLabProjectProcessor} for entity mapping</li>
 * </ul>
 * <p>
 * Rate limit awareness is handled by checking the rate limit tracker between pagination
 * pages and adjusting page size when the budget is low.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabGroupSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabGroupSyncService.class);

    private static final String GET_GROUP_DOCUMENT = "GetGroup";
    private static final String GET_GROUP_PROJECTS_DOCUMENT = "GetGroupProjects";

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabGroupProcessor groupProcessor;
    private final GitLabProjectProcessor projectProcessor;
    private final GitLabProperties gitLabProperties;
    private final IdentityProviderRepository gitProviderRepository;

    public GitLabGroupSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabGroupProcessor groupProcessor,
            GitLabProjectProcessor projectProcessor,
            GitLabProperties gitLabProperties,
            IdentityProviderRepository gitProviderRepository) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.groupProcessor = groupProcessor;
        this.projectProcessor = projectProcessor;
        this.gitLabProperties = gitLabProperties;
        this.gitProviderRepository = gitProviderRepository;
    }

    /**
     * Resolves the GitLab provider entity from the database for {@code serverUrl}.
     * Falls back to the configured default ({@code hephaestus.integration.gitlab.default-server-url})
     * only when the caller has no per-workspace URL to pass — that fallback path stamps
     * gitlab.com on rows that may actually live elsewhere (silent cross-instance data
     * corruption observed live on 2026-05-25 against gitlab.lrz.de), so always prefer
     * the workspace-bound overload.
     *
     * @param serverUrl  the per-workspace GitLab base URL (e.g. {@code https://gitlab.lrz.de}),
     *                   or {@code null} to fall back to the global default
     * @throws IllegalStateException if no GitLab provider row exists for the URL
     */
    private IdentityProvider resolveProvider(@org.jspecify.annotations.Nullable String serverUrl) {
        String effective = (serverUrl == null || serverUrl.isBlank()) ? gitLabProperties.defaultServerUrl() : serverUrl;
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, effective)
                .orElseThrow(() -> new IllegalStateException(
                        "IdentityProvider not found for type=GITLAB, serverUrl=" + effective));
    }

    /**
     * Syncs a single GitLab group (without projects) from the GraphQL API.
     *
     * @param scopeId       the workspace/scope ID for authentication
     * @param groupFullPath the full path of the group (e.g., {@code org/team})
     * @param serverUrl     workspace's GitLab base URL — passed through to provider
     *                      resolution so self-hosted instances do not get stamped with
     *                      gitlab.com. May be null only for legacy callers.
     * @return the synced Organization entity, or empty if not found or on error
     */
    @Transactional
    public Optional<Organization> syncGroup(
            Long scopeId, @Nullable String groupFullPath, @org.jspecify.annotations.Nullable String serverUrl) {
        if (groupFullPath == null || groupFullPath.isBlank()) {
            log.warn("Skipped group sync: reason=nullOrBlankGroupPath, scopeId={}", scopeId);
            return Optional.empty();
        }
        String safeGroupPath = sanitizeForLog(groupFullPath);

        try {
            IdentityProvider provider = resolveProvider(serverUrl);
            Long providerId = Objects.requireNonNull(provider.getId());
            graphQlClientProvider.acquirePermission();
            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

            ClientGraphQlResponse response = client.documentName(GET_GROUP_DOCUMENT)
                    .variable("fullPath", groupFullPath)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());

            var handleResult = responseHandler.handle(response, "group " + safeGroupPath, log);
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                return Optional.empty();
            }
            // RETRY is not applicable for single requests, treat as success if CONTINUE
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                // For a single non-paginated request, a rate-limit retry is best handled
                // by returning empty and letting the caller reschedule.
                return Optional.empty();
            }

            graphQlClientProvider.recordSuccess();

            GitLabGroupResponse group =
                    Objects.requireNonNull(response).field("group").toEntity(GitLabGroupResponse.class);
            if (group == null) {
                log.warn(
                        "Skipped group sync: reason=notFoundOnGitLab, scopeId={}, groupPath={}",
                        scopeId,
                        safeGroupPath);
                return Optional.empty();
            }

            Organization organization = groupProcessor.process(group, Objects.requireNonNull(providerId));
            if (organization != null) {
                log.info(
                        "Synced group: scopeId={}, orgId={}, groupPath={}",
                        scopeId,
                        organization.getId(),
                        safeGroupPath);
            }
            return Optional.ofNullable(organization);
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error("Failed to sync group: scopeId={}, groupPath={}", scopeId, safeGroupPath, e);
            return Optional.empty();
        }
    }

    /**
     * Syncs all projects in a GitLab group (including subgroups) using cursor-based pagination.
     * <p>
     * For each project, the immediate parent group is resolved and persisted as an Organization.
     * Subgroup projects are included automatically — each project's {@code group} field provides
     * the immediate parent, which is upserted independently of the top-level group.
     * <p>
     * Rate limits are tracked between pages and page size adapts to remaining budget.
     * Individual project failures do not abort the sync — they are counted in the result.
     *
     * @param scopeId       the workspace/scope ID for authentication
     * @param groupFullPath the full path of the group
     * @param serverUrl     workspace's GitLab base URL; null = fall back to the global
     *                      default (preserves legacy behaviour but stamps gitlab.com)
     * @return structured result with synced repositories, page counts, and error counts
     */
    // Note: intentionally NOT @Transactional — this is an orchestrator that makes multiple
    // API calls with throttle delays between pages. Each project upsert and the group sync
    // have their own @Transactional boundaries, so we don't hold a DB connection during
    // network calls or Thread.sleep().
    public GitLabSyncResult syncGroupProjects(
            Long scopeId, @Nullable String groupFullPath, @org.jspecify.annotations.Nullable String serverUrl) {
        if (groupFullPath == null || groupFullPath.isBlank()) {
            log.warn("Skipped group projects sync: reason=nullOrBlankGroupPath, scopeId={}", scopeId);
            return GitLabSyncResult.aborted(GitLabSyncResult.Status.ABORTED_ERROR, Collections.emptyList(), 0, 0);
        }
        String safeGroupPath = sanitizeForLog(groupFullPath);

        // Top-level organization is extracted from the first page response (group fields are
        // inlined in GetGroupProjects query), eliminating a separate API call.
        Organization topLevelOrganization = null;

        List<Repository> syncedRepositories = new ArrayList<>();
        String cursor = null;
        String previousCursor = null;
        int pageCount = 0;
        int projectsSkipped = 0;
        int projectsRedacted = 0;
        boolean hadApiFailure = false;
        int reportedTotalCount = -1;

        try {
            IdentityProvider provider = resolveProvider(serverUrl);
            Long providerId = Objects.requireNonNull(provider.getId());
            graphQlClientProvider.acquirePermission();

            do {
                // Rate limit values are automatically updated by the WebClient exchange filter
                // that parses RateLimit-* response headers per-request.
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    try {
                        graphQlClientProvider.waitIfRateLimitLow(scopeId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while waiting for rate limit reset: scopeId={}", scopeId);
                        return GitLabSyncResult.aborted(
                                GitLabSyncResult.Status.ABORTED_RATE_LIMIT,
                                syncedRepositories,
                                pageCount,
                                projectsSkipped);
                    }
                }

                int pageSize =
                        adaptPageSize(GROUP_PROJECTS_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId));
                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_GROUP_PROJECTS_DOCUMENT)
                        .variable("fullPath", groupFullPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .variable("includeSubgroups", true)
                        .execute()
                        .block(gitLabProperties.extendedGraphqlTimeout());

                var handleResult = responseHandler.handle(response, "group projects for " + safeGroupPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    hadApiFailure = true;
                    break;
                }

                graphQlClientProvider.recordSuccess();

                // On the first page, extract and persist the top-level group from inlined fields
                if (pageCount == 0) {
                    // Extract reported total count for post-sync verification
                    try {
                        Object countField = Objects.requireNonNull(response)
                                .field("group.projects.count")
                                .getValue();
                        if (countField instanceof Number number) {
                            reportedTotalCount = number.intValue();
                            log.info(
                                    "Project connection reports count={}, groupPath={}",
                                    reportedTotalCount,
                                    safeGroupPath);
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract project count: groupPath={}", safeGroupPath);
                    }

                    GitLabGroupResponse groupData =
                            Objects.requireNonNull(response).field("group").toEntity(GitLabGroupResponse.class);
                    if (groupData != null) {
                        topLevelOrganization = groupProcessor.process(groupData, providerId);
                    }
                    if (topLevelOrganization == null) {
                        log.warn(
                                "Failed to resolve group on first page, aborting sync: scopeId={}, groupPath={}",
                                scopeId,
                                safeGroupPath);
                        hadApiFailure = true;
                        break;
                    }
                    log.info(
                            "Synced group: scopeId={}, orgId={}, groupPath={}",
                            scopeId,
                            topLevelOrganization.getId(),
                            safeGroupPath);
                }

                // Parse project nodes — each project may belong to a different subgroup
                List<GitLabProjectResponse> projects = Objects.requireNonNull(response)
                        .field("group.projects.nodes")
                        .toEntityList(GitLabProjectResponse.class);

                for (GitLabProjectResponse project : projects) {
                    if (project == null) {
                        projectsRedacted++;
                        continue;
                    }
                    try {
                        // Resolve the project's immediate parent group (may differ from top-level)
                        Organization projectOrg = resolveProjectOrganization(
                                project, Objects.requireNonNull(topLevelOrganization), providerId);
                        Repository repo = projectProcessor.processGraphQlResponse(project, projectOrg, provider);
                        if (repo != null) {
                            syncedRepositories.add(repo);
                        } else {
                            projectsSkipped++;
                        }
                    } catch (Exception e) {
                        projectsSkipped++;
                        log.warn(
                                "Failed to process project: fullPath={}, error={}",
                                sanitizeForLog(project.fullPath()),
                                e.getMessage());
                    }
                }

                // Check pagination
                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("group.projects.pageInfo")
                        .toEntity(GitLabPageInfo.class);

                // Only an explicit last page proves the listing complete.
                if (pageInfo == null) {
                    log.warn("Pagination info missing: groupPath={}, page={}", safeGroupPath, pageCount);
                    hadApiFailure = true;
                    break;
                }
                if (!pageInfo.hasNextPage()) {
                    break;
                }

                cursor = pageInfo.endCursor();
                if (cursor == null) {
                    log.warn(
                            "Pagination cursor is null despite hasNextPage=true: groupPath={}, page={}",
                            safeGroupPath,
                            pageCount);
                    hadApiFailure = true;
                    break;
                }
                if (responseHandler.isPaginationLoop(
                        cursor, previousCursor, "group projects for " + safeGroupPath, log)) {
                    hadApiFailure = true;
                    break;
                }
                previousCursor = cursor;
                pageCount++;

                // Throttle between pages to respect rate limits
                Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
            } while (pageCount < MAX_PAGINATION_PAGES);

            int totalPages = pageCount + 1;

            // Null nodes typically indicate GitLab server-side timeouts when resolving
            // complex nested fields (e.g., group{} sub-objects per project node).
            // This causes data loss — the affected projects are silently dropped.
            if (projectsRedacted > 0) {
                log.warn(
                        "GraphQL returned {} null project node(s) (likely server-side timeout): groupPath={}, "
                                + "synced={}, nullNodes={}, reportedCount={}. "
                                + "Consider reducing GROUP_PROJECTS_PAGE_SIZE.",
                        projectsRedacted,
                        safeGroupPath,
                        syncedRepositories.size(),
                        projectsRedacted,
                        reportedTotalCount);
            }

            // Warn if pagination was truncated (more pages exist but we hit the safety limit)
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Pagination truncated at {} pages for group: scopeId={}, groupPath={}, syncedSoFar={}",
                        MAX_PAGINATION_PAGES,
                        scopeId,
                        safeGroupPath,
                        syncedRepositories.size());
                return GitLabSyncResult.aborted(
                        GitLabSyncResult.Status.ABORTED_ERROR, syncedRepositories, totalPages, projectsSkipped);
            }

            // Reconciliation pass: re-query with includeSubgroups=false to recover
            // any direct projects silently dropped by the GitLab includeSubgroups bug.
            // See: https://gitlab.com/gitlab-org/gitlab/-/issues/33419
            int projectsReconciled = 0;
            boolean directProjectsListed = true;
            if (!hadApiFailure && topLevelOrganization != null) {
                Set<Long> seenNativeIds = new HashSet<>();
                for (Repository repo : syncedRepositories) {
                    seenNativeIds.add(repo.getNativeId());
                }

                List<Repository> reconciled = new ArrayList<>();
                directProjectsListed = reconcileDirectProjects(
                        scopeId, groupFullPath, topLevelOrganization, provider, providerId, seenNativeIds, reconciled);
                projectsReconciled = reconciled.size();
                syncedRepositories.addAll(reconciled);
            }

            // Fewer projects than GitLab counted means some were never listed: the result is incomplete.
            int totalProcessed = syncedRepositories.size() + projectsSkipped + projectsRedacted;
            boolean projectsMissing = reportedTotalCount >= 0 && totalProcessed < reportedTotalCount;
            if (projectsMissing) {
                log.warn(
                        "Project connection overflow detected: groupPath={}, synced={}, skipped={}, redacted={}, "
                                + "reportedCount={}. Some projects were not fetched.",
                        safeGroupPath,
                        syncedRepositories.size(),
                        projectsSkipped,
                        projectsRedacted,
                        reportedTotalCount);
            }

            log.info(
                    "Synced group projects: scopeId={}, groupPath={}, synced={}, failed={}, redacted={}, "
                            + "reconciled={}, pages={}",
                    scopeId,
                    safeGroupPath,
                    syncedRepositories.size(),
                    projectsSkipped,
                    projectsRedacted,
                    projectsReconciled,
                    totalPages);

            // API failure during pagination → report as aborted, not completed
            if (hadApiFailure) {
                return GitLabSyncResult.aborted(
                        GitLabSyncResult.Status.ABORTED_ERROR, syncedRepositories, totalPages, projectsSkipped);
            }
            // Processing failures, redacted (null) projects and unlisted projects all mean an incomplete sync
            if (projectsSkipped > 0 || projectsRedacted > 0 || projectsMissing || !directProjectsListed) {
                return GitLabSyncResult.withErrors(
                        syncedRepositories, totalPages, projectsSkipped, projectsRedacted, projectsReconciled);
            }
            return GitLabSyncResult.completed(syncedRepositories, totalPages, projectsRedacted, projectsReconciled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during group project sync: scopeId={}, groupPath={}", scopeId, safeGroupPath);
            return GitLabSyncResult.aborted(
                    GitLabSyncResult.Status.ABORTED_ERROR, syncedRepositories, pageCount, projectsSkipped);
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error(
                    "Failed to sync group projects: scopeId={}, groupPath={}, syncedSoFar={}",
                    scopeId,
                    safeGroupPath,
                    syncedRepositories.size(),
                    e);
            return GitLabSyncResult.aborted(
                    GitLabSyncResult.Status.ABORTED_ERROR, syncedRepositories, pageCount, projectsSkipped);
        }
    }

    /**
     * Reconciliation pass: fetches direct projects (excludeSubgroups) and adds any that were
     * missing from the primary sync to {@code reconciled}. This works around a GitLab bug where
     * {@code includeSubgroups: true} can silently drop direct projects on certain versions.
     *
     * @return whether every direct project was listed and processed; the projects recovered before
     *     an incomplete pass stay in {@code reconciled}
     * @see <a href="https://gitlab.com/gitlab-org/gitlab/-/issues/33419">GitLab #33419</a>
     */
    private boolean reconcileDirectProjects(
            Long scopeId,
            String groupFullPath,
            Organization topLevelOrganization,
            IdentityProvider provider,
            Long providerId,
            Set<Long> seenNativeIds,
            List<Repository> reconciled) {
        String safeGroupPath = sanitizeForLog(groupFullPath);
        boolean listedToEnd = false;
        boolean projectMissed = false;

        try {
            String reconCursor = null;
            String previousReconCursor = null;
            int reconPageCount = 0;

            do {
                // Canonical rate limit pattern: acquirePermission + waitIfRateLimitLow per page.
                // This waits up to 70s for rate limit reset (GitLab resets every 60s) instead
                // of skipping reconciliation — consistent with all other GitLab sync services.
                graphQlClientProvider.acquirePermission();
                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during reconciliation rate limit wait: groupPath={}", safeGroupPath);
                    break;
                }

                int pageSize =
                        adaptPageSize(GROUP_PROJECTS_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId));
                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_GROUP_PROJECTS_DOCUMENT)
                        .variable("fullPath", groupFullPath)
                        .variable("first", pageSize)
                        .variable("after", reconCursor)
                        .variable("includeSubgroups", false)
                        .execute()
                        .block(gitLabProperties.extendedGraphqlTimeout());

                var handleResult = responseHandler.handle(response, "reconciliation for " + safeGroupPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    break;
                }

                graphQlClientProvider.recordSuccess();

                List<GitLabProjectResponse> projects = Objects.requireNonNull(response)
                        .field("group.projects.nodes")
                        .toEntityList(GitLabProjectResponse.class);

                for (GitLabProjectResponse project : projects) {
                    if (project == null || project.id() == null) {
                        projectMissed = true;
                        continue;
                    }
                    try {
                        long nativeId = extractEntityId(project.id());
                        if (seenNativeIds.contains(nativeId)) {
                            continue;
                        }
                        // Direct projects belong to the top-level group
                        Repository repo =
                                projectProcessor.processGraphQlResponse(project, topLevelOrganization, provider);
                        if (repo != null) {
                            reconciled.add(repo);
                            seenNativeIds.add(nativeId);
                        } else {
                            projectMissed = true;
                        }
                    } catch (Exception e) {
                        projectMissed = true;
                        log.warn(
                                "Failed to reconcile project: fullPath={}, error={}",
                                sanitizeForLog(project.fullPath()),
                                e.getMessage());
                    }
                }

                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("group.projects.pageInfo")
                        .toEntity(GitLabPageInfo.class);

                // Only an explicit last page proves every direct project was listed.
                if (pageInfo == null) {
                    log.warn(
                            "Reconciliation pagination info missing: groupPath={}, page={}",
                            safeGroupPath,
                            reconPageCount);
                    break;
                }
                if (!pageInfo.hasNextPage()) {
                    listedToEnd = true;
                    break;
                }

                reconCursor = pageInfo.endCursor();
                if (reconCursor == null) {
                    log.warn(
                            "Reconciliation cursor null despite hasNextPage=true: groupPath={}, page={}",
                            safeGroupPath,
                            reconPageCount);
                    break;
                }
                if (responseHandler.isPaginationLoop(
                        reconCursor, previousReconCursor, "reconciliation for " + safeGroupPath, log)) {
                    break;
                }
                previousReconCursor = reconCursor;
                reconPageCount++;

                Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
            } while (reconPageCount < MAX_PAGINATION_PAGES);

            if (!reconciled.isEmpty()) {
                log.warn(
                        "Reconciliation recovered {} direct project(s) missing from includeSubgroups query: "
                                + "groupPath={} (GitLab bug https://gitlab.com/gitlab-org/gitlab/-/issues/33419)",
                        reconciled.size(),
                        safeGroupPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during reconciliation: groupPath={}", safeGroupPath);
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.warn("Reconciliation failed (primary sync results preserved): groupPath={}", safeGroupPath, e);
        }

        return listedToEnd && !projectMissed;
    }

    /**
     * Resolves the Organization for a project.
     * <p>
     * If the project has an inline {@code group} field (from GetGroupProjects with subgroups),
     * that group is upserted as the project's organization. Otherwise, the top-level group
     * is used as fallback. This correctly handles nested group hierarchies like
     * {@code org/team/subteam/project}.
     */
    private Organization resolveProjectOrganization(
            GitLabProjectResponse project, Organization topLevelOrganization, Long providerId) {
        if (project.group() != null) {
            Organization subOrg = groupProcessor.process(project.group(), providerId);
            if (subOrg != null) {
                return subOrg;
            }
        }
        return topLevelOrganization;
    }
}
