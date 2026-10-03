package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.PROJECT_FIELD_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.PROJECT_ITEM_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.STATUS_UPDATE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ExponentialBackoff;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Connection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2FieldConfiguration;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2FieldConfigurationConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2StatusUpdate;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2StatusUpdateConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectFieldDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectFieldValueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectStatusUpdateDTO;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub Projects V2 via GraphQL API.
 * <p>
 * Features: per-page transactions, cursor persistence for resumability, transport error retry,
 * decoupled phases (project list → items), client-side incremental sync, cost-optimized page sizes.
 * <p>
 * <h2>Architecture Note: Issue/PR Items vs Draft Issues</h2>
 * <p>
 * All project item types (Draft Issues, Issues, and Pull Requests) are synced from the
 * project side via {@link #syncProjectItems}. This ensures complete coverage regardless
 * of which repositories have been individually synced. The embedded issue/PR sync path
 * ({@link GitHubProjectItemSyncService}) supplements this with additional context such as
 * linking items to locally-synced issue entities.
 * <p>
 * <h2>Supported Owner Types</h2>
 * <p>
 * This sync service currently only supports <b>ORGANIZATION</b> owned projects.
 * <p>
 * <table border="1">
 *   <tr><th>Owner Type</th><th>Sync Support</th><th>Notes</th></tr>
 *   <tr>
 *     <td><b>ORGANIZATION</b></td>
 *     <td>Full</td>
 *     <td>Primary use case. Use {@link #syncProjectsForOrganization}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>REPOSITORY</b></td>
 *     <td>Not implemented</td>
 *     <td>Would require a new syncProjectsForRepository method with appropriate GraphQL query.</td>
 *   </tr>
 *   <tr>
 *     <td><b>USER</b></td>
 *     <td>Not implemented</td>
 *     <td>Would require a new syncProjectsForUser method. User projects aren't linked to workspaces.</td>
 *   </tr>
 * </table>
 * <p>
 * <b>To add REPOSITORY support:</b> Create a new GraphQL query {@code GetRepositoryProjects} and
 * implement {@code syncProjectsForRepository(Long scopeId, String repositoryFullName)} following
 * the same pattern as organization sync.
 * <p>
 * <h2>Phase Tracking</h2>
 * <p>
 * The {@link #syncProjectItems} method tracks three phases:
 * <ol>
 *   <li><b>Fields:</b> Project field definitions (custom fields like Status, Priority)</li>
 *   <li><b>Status Updates:</b> Project health status updates (ON_TRACK, AT_RISK, OFF_TRACK)</li>
 *   <li><b>Items:</b> All project items (Draft Issues, Issues, Pull Requests) with field values</li>
 * </ol>
 * <p>
 * Each phase has independent cursor persistence for resumability. If one phase fails (e.g., rate limit),
 * the returned {@link SyncResult} includes phase-level success/failure information for monitoring.
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.project.Project.OwnerType
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectItemSyncService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubProjectSyncService {

    private static final String GET_ORGANIZATION_PROJECTS_DOCUMENT = "GetOrganizationProjects";
    private static final String GET_PROJECT_WITH_FIELDS_DOCUMENT = "GetProjectWithFields";
    private static final String GET_PROJECT_ITEMS_DOCUMENT = "GetProjectItems";
    private static final String GET_PROJECT_ITEM_FIELD_VALUES_DOCUMENT = "GetProjectItemFieldValues";
    private static final String GET_PROJECT_STATUS_UPDATES_DOCUMENT = "GetProjectStatusUpdates";

    /**
     * Result of a sub-phase (field sync, status update sync).
     * Replaces boolean return to distinguish "project deleted on GitHub" from normal failure.
     */
    private enum PhaseResult {
        /** Phase completed (fully or partially). */
        SUCCESS,
        /** Phase failed due to error or rate limit. */
        FAILED,
        /** The project node was null in the GitHub response — project was deleted from GitHub. */
        PROJECT_NOT_FOUND,
    }

    /** Maximum number of retry attempts for transient failures. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubProjectProcessor projectProcessor;
    private final GitHubProjectItemProcessor itemProcessor;
    private final GitHubProjectStatusUpdateProcessor statusUpdateProcessor;
    private final GitHubProjectItemFieldValueSyncService fieldValueSyncService;
    private final GitHubProjectBackfillStateProvider backfillStateProvider;
    private final TransactionTemplate transactionTemplate;
    private final GitHubSyncProperties syncProperties;
    private final SyncSchedulerProperties syncSchedulerProperties;
    private final GitHubExceptionClassifier exceptionClassifier;

    /**
     * Relinks orphaned project items whose issue_id is NULL but content_database_id
     * points to an issue that now exists locally.
     * <p>
     * Should be called AFTER repository/issue sync completes so that newly synced
     * issues can be matched to their project items.
     *
     * @return number of items relinked
     */
    public int relinkOrphanedProjectItems() {
        return itemProcessor.relinkOrphanedItems();
    }

    /**
     * Synchronizes all projects for an organization using GraphQL.
     * <p>
     * This method syncs only the project list (metadata). Draft Issue sync should be
     * called separately via {@link #syncProjectItems} for each project.
     * <p>
     * Note: This method intentionally does NOT use @Transactional to avoid long-running
     * transactions. Each page of projects is processed in its own transaction.
     *
     * @param scopeId           the scope ID for authentication
     * @param organizationLogin the GitHub organization login to sync projects for
     * @return sync result containing status and count of projects synced
     */
    public SyncResult syncProjectsForOrganization(Long scopeId, @Nullable String organizationLogin) {
        if (organizationLogin == null || organizationLogin.isBlank()) {
            log.warn("Skipped project sync: reason=missingOrgLogin, scopeId={}", scopeId);
            return SyncResult.completed(0);
        }
        String safeOrgLogin = Objects.requireNonNullElse(sanitizeForLog(organizationLogin), "null");

        // Resolve organization outside transaction to avoid holding locks
        Optional<Organization> organizationResult = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Organization org = organizationRepository
                    .findByLoginIgnoreCaseAndProvider_Type(organizationLogin, IdentityProviderType.GITHUB)
                    .orElse(null);
            if (org != null) {
                org.getProvider().getId();
            }
            return Optional.ofNullable(org);
        }));
        Organization organization = organizationResult.orElse(null);

        if (organization == null) {
            log.warn("Skipped project sync: reason=organizationNotFound, orgLogin={}", safeOrgLogin);
            return SyncResult.completed(0);
        }

        Long organizationId = organization.getId();
        IdentityProvider provider = organization.getProvider();
        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        Duration timeout = syncProperties.graphqlTimeout();

        Set<Long> syncedProjectIds = new HashSet<>();
        int totalSynced = 0;
        int projectsReceived = 0;
        int totalSkipped = 0;
        String cursor = null;
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;
        SyncResult.Status abortReason = null;

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for projects: orgLogin={}, limit={}",
                        safeOrgLogin,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                // Use Mono.defer() to wrap the entire execute() call for retry support
                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(
                                        GET_ORGANIZATION_PROJECTS_DOCUMENT)
                                .variable("login", organizationLogin)
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                LARGE_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying project sync after transport error: orgLogin={}, page={}, attempt={}, error={}",
                                        safeOrgLogin,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(timeout);

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(graphQlResponse);
                    if (classification != null && classification.category() == Category.NOT_FOUND) {
                        log.info(
                                "Organization not found via GraphQL (may have been renamed/deleted): orgLogin={}",
                                safeOrgLogin);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    if (classification != null && classification.category() == Category.RATE_LIMITED) {
                        log.warn("Rate limited during project list sync: orgLogin={}", safeOrgLogin);
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response: orgLogin={}, errors={}",
                            safeOrgLogin,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    abortReason = SyncResult.Status.ABORTED_ERROR;
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "project list", "orgLogin", safeOrgLogin)) {
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                }

                GHProjectV2Connection response =
                        graphQlResponse.field("organization.projectsV2").toEntity(GHProjectV2Connection.class);

                if (response == null
                        || response.getNodes() == null
                        || response.getNodes().isEmpty()) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = response.getTotalCount();
                }
                projectsReceived += response.getNodes().size();

                // Process this page of projects in its own transaction
                // Respect cooldown: only UPDATE projects that are new or past cooldown
                // Respect filter: only sync projects in allowed-projects list (empty = all)
                final Long orgId = organizationId;
                final String orgLogin = organizationLogin;
                final Instant cooldownThreshold =
                        Instant.now().minusSeconds(syncSchedulerProperties.cooldownMinutes() * 60L);
                final var filters = syncSchedulerProperties.filters();
                PageResult pageResult = transactionTemplate.execute(status -> {
                    ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
                    int projectsProcessed = 0;
                    int projectsSkipped = 0;
                    int projectsFiltered = 0;

                    for (var graphQlProject : response.getNodes()) {
                        GitHubProjectDTO dto = GitHubProjectDTO.fromProjectV2(graphQlProject);
                        if (dto == null) {
                            continue;
                        }

                        // Check filter: skip projects not in allowed-projects list
                        if (!filters.isProjectAllowed(orgLogin, dto.number())) {
                            projectsFiltered++;
                            continue;
                        }

                        // Check if project exists and was recently synced
                        Long databaseId = dto.getDatabaseId();
                        if (databaseId != null) {
                            Project existing =
                                    projectRepository.findById(databaseId).orElse(null);
                            if (existing != null) {
                                // Track for stale removal regardless of cooldown
                                syncedProjectIds.add(existing.getId());

                                // Skip if within cooldown
                                if (existing.getLastSyncAt() != null
                                        && existing.getLastSyncAt().isAfter(cooldownThreshold)) {
                                    projectsSkipped++;
                                    continue;
                                }
                            }
                        }

                        // Process project (new or past cooldown)
                        Project project = projectProcessor.process(dto, Project.OwnerType.ORGANIZATION, orgId, context);

                        if (project != null) {
                            project.setLastSyncAt(Instant.now());
                            project = projectRepository.save(project);
                            syncedProjectIds.add(project.getId());
                            projectsProcessed++;
                        }
                    }

                    if (projectsSkipped > 0 || projectsFiltered > 0) {
                        log.debug(
                                "Project sync page: orgId={}, processed={}, skippedCooldown={}, filteredOut={}",
                                orgId,
                                projectsProcessed,
                                projectsSkipped,
                                projectsFiltered);
                    }

                    return new PageResult(projectsProcessed, projectsSkipped);
                });

                if (pageResult != null) {
                    totalSynced += pageResult.count;
                    totalSkipped += pageResult.skipped;
                }

                var pageInfo = response.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // Reset retry counter after successful page
                retryAttempt = 0;

                // Apply pagination throttle to prevent 502/504 errors under load
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Project sync interrupted during throttle: orgLogin={}", safeOrgLogin);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                }
            } catch (InstallationNotFoundException e) {
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                Category category = classification.category();

                switch (category) {
                    case RETRYABLE -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                            retryAttempt++;
                            log.warn(
                                    "Retrying project sync after transient error: orgLogin={}, attempt={}, error={}",
                                    safeOrgLogin,
                                    retryAttempt,
                                    classification.message());
                            try {
                                ExponentialBackoff.sleep(retryAttempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("Project sync interrupted during backoff: orgLogin={}", safeOrgLogin);
                                abortReason = SyncResult.Status.ABORTED_ERROR;
                                break;
                            }
                            continue;
                        }
                        log.error(
                                "Failed to sync projects after {} retries: orgLogin={}, error={}",
                                MAX_RETRY_ATTEMPTS,
                                safeOrgLogin,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case RATE_LIMITED -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                            retryAttempt++;
                            long waitMs = Math.min(
                                    classification.suggestedWait().toMillis(), 300_000 // Cap at 5 minutes
                                    );
                            log.warn(
                                    "Rate limited during project sync, waiting: orgLogin={}, waitMs={}, attempt={}",
                                    safeOrgLogin,
                                    waitMs,
                                    retryAttempt);
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("Project sync interrupted during rate limit wait: orgLogin={}", safeOrgLogin);
                                abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                                break;
                            }
                            continue;
                        }
                        log.error(
                                "Aborting project sync due to rate limiting: orgLogin={}, error={}",
                                safeOrgLogin,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                    case NOT_FOUND -> {
                        log.warn(
                                "Resource not found during project sync, skipping: orgLogin={}, error={}",
                                safeOrgLogin,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case AUTH_ERROR -> {
                        log.error(
                                "Authentication error during project sync: orgLogin={}, error={}",
                                safeOrgLogin,
                                classification.message());
                        throw e;
                    }
                    case CLIENT_ERROR -> {
                        log.error(
                                "Aborting project sync due to client error: orgLogin={}, error={}",
                                safeOrgLogin,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    default -> {
                        log.error(
                                "Aborting project sync due to error: orgLogin={}, category={}, error={}",
                                safeOrgLogin,
                                category,
                                classification.message(),
                                e);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                }
                break;
            }
        }

        // Raw nodes received vs projectsV2.totalCount (totalSynced is post-process).
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "projects",
                    projectsReceived,
                    reportedTotalCount,
                    hasMore || abortReason != null,
                    "orgLogin=" + safeOrgLogin);
        }

        // Only remove stale projects if sync completed without abort
        boolean syncCompletedNormally = !hasMore && abortReason == null;
        if (syncCompletedNormally && !syncedProjectIds.isEmpty()) {
            removeDeletedProjects(organizationId, syncedProjectIds, scopeId, provider);
        } else if (!syncCompletedNormally && abortReason != null) {
            log.warn(
                    "Skipped stale project removal: reason=incompleteSync, orgLogin={}, pagesProcessed={}",
                    safeOrgLogin,
                    pageCount);
        }

        SyncResult.Status finalStatus = abortReason != null ? abortReason : SyncResult.Status.COMPLETED;

        log.info(
                "Completed project list sync: orgLogin={}, synced={}, skippedCooldown={}, status={}, scopeId={}",
                safeOrgLogin,
                totalSynced,
                totalSkipped,
                finalStatus,
                scopeId);

        return new SyncResult(finalStatus, totalSynced);
    }

    /**
     * Synchronizes Draft Issues for a single project with cursor persistence for resumability.
     * <p>
     * <b>Important:</b> This method syncs <b>all item types</b> (Draft Issues, Issues, and Pull Requests)
     * from the project side. The project-side sync is the authoritative source for project items,
     * ensuring complete coverage even for items referencing issues/PRs in repositories that
     * haven't been individually synced yet. The embedded issue/PR sync path supplements this
     * with additional context (e.g., linking items to locally-synced issue entities).
     * <p>
     * Uses the project's {@code itemSyncCursor} field for resumable pagination.
     * Each page is processed in its own transaction to avoid long-running locks.
     * On successful completion, clears the cursor and updates {@code itemsSyncedAt}.
     * <p>
     * <b>Incremental Sync (Two-Tier):</b>
     * <ol>
     *   <li><b>Server-side filtering:</b> When a previous sync timestamp exists, the GraphQL query
     *   includes a {@code query: "updated:>YYYY-MM-DD"} filter parameter that tells GitHub to only
     *   return items updated after the given date. This eliminates old items from the API response,
     *   saving both response size and API cost. The date uses day-level granularity with a 2-day
     *   safety buffer (subtracted from lastSyncedAt) to account for edge cases.</li>
     *   <li><b>Client-side filtering:</b> Items where updatedAt &lt; lastSyncTimestamp are additionally
     *   skipped during processing. This provides finer-grained filtering than the coarse day-level
     *   server-side filter, saving database writes for items that fall within the buffer window.</li>
     * </ol>
     * <p>
     * <b>Stale removal:</b> When server-side filtering is active, stale item removal is skipped
     * because the filtered response does not include all project items. Stale removal only runs
     * on full (non-filtered, non-resumed) syncs.
     *
     * @param scopeId the scope ID for authentication
     * @param project the project to sync items for
     * @return sync result containing status and count of items synced
     * @see GitHubProjectItemSyncService for supplementary Issue/PR item sync from the issue/PR side
     */
    public SyncResult syncProjectItems(Long scopeId, @Nullable Project project) {
        if (project == null || project.getNodeId() == null) {
            log.debug("Skipped project item sync: reason=invalidProject");
            return SyncResult.completed(0);
        }

        String projectNodeId = project.getNodeId();
        Long projectId = project.getId();

        IdentityProvider provider = transactionTemplate.execute(status -> {
            IdentityProvider result =
                    projectRepository.findById(projectId).orElseThrow().getProvider();
            Hibernate.initialize(result);
            return result;
        });

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        Duration timeout = syncProperties.graphqlTimeout();

        // Fields and status updates rarely change, so their syncs are skipped within the cooldown.
        Instant cooldownThreshold = Instant.now().minusSeconds(syncSchedulerProperties.cooldownMinutes() * 60L);

        // Phase 1: Sync fields (with cooldown — field definitions rarely change)
        boolean fieldsCooldownActive = project.getFieldsSyncedAt() != null
                && project.getFieldsSyncedAt().isAfter(cooldownThreshold);
        PhaseResult fieldsResult;
        if (fieldsCooldownActive) {
            log.debug(
                    "Skipped project fields sync: reason=cooldownActive, projectId={}, lastSyncedAt={}",
                    projectId,
                    project.getFieldsSyncedAt());
            fieldsResult = PhaseResult.SUCCESS;
        } else {
            fieldsResult = syncProjectFields(client, project, scopeId);
        }

        // If the project was deleted from GitHub, clean it up and return immediately
        if (fieldsResult == PhaseResult.PROJECT_NOT_FOUND) {
            log.warn(
                    "Deleting phantom project: projectId={}, nodeId={} — project no longer exists on GitHub",
                    projectId,
                    projectNodeId);
            transactionTemplate.executeWithoutResult(status -> {
                ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
                projectProcessor.delete(projectId, context);
            });
            return SyncResult.completed(0);
        }
        boolean fieldsSynced = fieldsResult == PhaseResult.SUCCESS;

        // Phase 2: Sync status updates (with cooldown — status updates change infrequently)
        boolean statusUpdatesCooldownActive = project.getStatusUpdatesSyncedAt() != null
                && project.getStatusUpdatesSyncedAt().isAfter(cooldownThreshold);
        boolean statusUpdatesSynced;
        if (statusUpdatesCooldownActive) {
            log.debug(
                    "Skipped project status updates sync: reason=cooldownActive, projectId={}, lastSyncedAt={}",
                    projectId,
                    project.getStatusUpdatesSyncedAt());
            statusUpdatesSynced = true;
        } else {
            statusUpdatesSynced = syncProjectStatusUpdates(client, project, scopeId, provider);
        }

        // Resume from cursor if present (via SPI for consistency)
        String cursor =
                backfillStateProvider.getProjectItemSyncCursor(projectId).orElse(null);
        boolean resuming = cursor != null;

        // Determine incremental sync threshold (client-side filtering)
        // Items with updatedAt >= incrementalSyncThreshold will be processed
        // Items with updatedAt < incrementalSyncThreshold will be skipped (but still tracked for stale removal)
        Instant incrementalSyncThreshold = null;
        boolean isIncrementalSync = false;
        String serverFilterQuery = null;
        if (syncProperties.incrementalSyncEnabled() && !resuming) {
            // Only use incremental sync if:
            // 1. Incremental sync is enabled in config
            // 2. We're not resuming from a cursor (resuming means we're mid-sync, need full processing)
            // 3. We have a previous sync timestamp
            Instant lastSyncedAt =
                    backfillStateProvider.getProjectItemsSyncedAt(projectId).orElse(null);
            if (lastSyncedAt != null) {
                // Apply safety buffer to handle clock skew
                incrementalSyncThreshold = lastSyncedAt.minus(syncProperties.incrementalSyncBuffer());
                isIncrementalSync = true;

                // Server-side filtering: use GitHub Projects search syntax to exclude old items
                // The "updated:>YYYY-MM-DD" filter uses day-level granularity, so we subtract
                // an additional 2 days from lastSyncedAt as a safety buffer beyond the client-side buffer
                LocalDate filterDate = lastSyncedAt
                        .minus(syncProperties.incrementalSyncBuffer())
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .minusDays(2);
                serverFilterQuery = "updated:>" + filterDate;

                log.info(
                        "Starting incremental project item sync: projectId={}, threshold={}, buffer={}, filterQuery={}",
                        projectId,
                        incrementalSyncThreshold,
                        syncProperties.incrementalSyncBuffer(),
                        serverFilterQuery);
            } else {
                log.info("Starting full project item sync (first sync): projectId={}", projectId);
            }
        }

        if (resuming) {
            log.info(
                    "Resuming project item sync from checkpoint: projectId={}, cursor={}",
                    projectId,
                    Objects.requireNonNull(cursor)
                                    .substring(
                                            0,
                                            Math.min(
                                                    20,
                                                    Objects.requireNonNull(cursor)
                                                            .length())) + "...");
        }

        List<String> syncedItemNodeIds = new ArrayList<>();
        // Track Issue/PR item node IDs seen during full sync for stale removal (Bug 5)
        List<String> syncedIssuePrNodeIds = new ArrayList<>();
        // Track items needing follow-up field value pagination (items with >20 field values)
        List<ItemWithFieldValueCursor> itemsNeedingFieldValuePagination = new ArrayList<>();
        int totalSynced = 0;
        int itemsReceived = 0;
        int totalSkipped = 0; // Items skipped due to incremental sync
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;
        SyncResult.Status abortReason = null;
        final Instant syncThreshold = incrementalSyncThreshold; // Final for lambda capture
        final boolean incrementalSync = isIncrementalSync;
        final String filterQuery = serverFilterQuery; // Final for lambda capture
        final boolean serverSideFiltered = serverFilterQuery != null;

        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for project items: projectId={}, limit={}",
                        projectId,
                        MAX_PAGINATION_PAGES);
                abortReason = SyncResult.Status.ABORTED_ERROR;
                break;
            }

            try {
                final String currentCursor = cursor;
                final int currentPage = pageCount;

                // Uses PROJECT_ITEM_PAGE_SIZE (50) instead of LARGE_PAGE_SIZE (100)
                // due to nested field values that multiply API cost.
                // See GitHubSyncConstants for detailed cost calculation.
                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_PROJECT_ITEMS_DOCUMENT)
                                .variable("nodeId", projectNodeId)
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                PROJECT_ITEM_PAGE_SIZE,
                                                graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .variable("filterQuery", filterQuery)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying project item sync after transport error: projectId={}, page={}, attempt={}, error={}",
                                        projectId,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(timeout);

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(graphQlResponse);
                    if (classification != null && classification.category() == Category.NOT_FOUND) {
                        log.info("Project not found via GraphQL (may have been deleted): projectId={}", projectId);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    if (classification != null && classification.category() == Category.RATE_LIMITED) {
                        log.warn("Rate limited during project items sync: projectId={}", projectId);
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response for project items: projectId={}, errors={}",
                            projectId,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    abortReason = SyncResult.Status.ABORTED_ERROR;
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "project items", "projectId", projectId)) {
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                }

                GHProjectV2ItemConnection itemsConnection =
                        graphQlResponse.field("node.items").toEntity(GHProjectV2ItemConnection.class);

                if (itemsConnection == null
                        || itemsConnection.getNodes() == null
                        || itemsConnection.getNodes().isEmpty()) {
                    // Safety net: detect deleted project in items phase too.
                    // Normally caught by syncProjectFields, but handles edge cases
                    // (e.g., project deleted between phases).
                    if (pageCount == 1 && graphQlResponse.field("node").getValue() == null) {
                        log.warn(
                                "Deleting phantom project detected in items phase: projectId={}, nodeId={}",
                                projectId,
                                projectNodeId);
                        transactionTemplate.executeWithoutResult(status -> {
                            ProcessingContext ctx = ProcessingContext.forSync(scopeId, provider);
                            projectProcessor.delete(projectId, ctx);
                        });
                        return SyncResult.completed(0);
                    }
                    hasMore = false;
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = itemsConnection.getTotalCount();
                }
                itemsReceived += itemsConnection.getNodes().size();

                // Process this page of items in its own transaction
                // All item types (Draft Issue, Issue, PR) are created/updated from the project side
                // For incremental sync, items unchanged since last sync are tracked but not processed
                final Long projId = projectId;
                ItemPageResult pageResult = transactionTemplate.execute(status -> {
                    Project proj = projectRepository.findById(projId).orElse(null);
                    if (proj == null) {
                        return new ItemPageResult(0, 0);
                    }

                    ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
                    int itemsProcessed = 0;
                    int itemsSkipped = 0;

                    for (GHProjectV2Item graphQlItem : itemsConnection.getNodes()) {
                        GitHubProjectItemDTO itemDto = GitHubProjectItemDTO.fromProjectV2Item(graphQlItem);
                        if (itemDto == null || itemDto.nodeId() == null) {
                            continue;
                        }

                        // Determine content type and track node IDs for stale removal
                        ProjectItem.ContentType contentType = itemDto.getContentTypeEnum();
                        if (contentType == ProjectItem.ContentType.DRAFT_ISSUE) {
                            syncedItemNodeIds.add(itemDto.nodeId());
                        } else {
                            syncedIssuePrNodeIds.add(itemDto.nodeId());
                        }

                        // Client-side incremental sync: skip items that haven't changed
                        // This saves database writes while still tracking all items for deletion detection
                        if (incrementalSync && syncThreshold != null && itemDto.updatedAt() != null) {
                            if (itemDto.updatedAt().isBefore(syncThreshold)) {
                                itemsSkipped++;
                                continue; // Skip processing - item unchanged since last sync
                            }
                        }

                        // Sync uses null actorId - attribution only happens via webhooks
                        ProjectItem processedItem = itemProcessor.process(itemDto, proj, context, null);
                        if (processedItem != null) {
                            itemsProcessed++;

                            // Process inline field values for this item
                            boolean fieldValuesTruncated =
                                    itemDto.fieldValuesTruncated() && itemDto.fieldValuesEndCursor() != null;
                            List<String> initialFieldIds = processFieldValues(
                                    processedItem.getId(), itemDto.fieldValues(), fieldValuesTruncated);

                            // Track items needing follow-up pagination for field values
                            // This follows the IssueWithCommentCursor pattern from GitHubIssueSyncService
                            if (fieldValuesTruncated) {
                                itemsNeedingFieldValuePagination.add(new ItemWithFieldValueCursor(
                                        processedItem.getId(),
                                        itemDto.nodeId(),
                                        Objects.requireNonNull(itemDto.fieldValuesEndCursor()),
                                        initialFieldIds));
                                log.debug(
                                        "Item queued for field value pagination: itemNodeId={}, projectId={}, type={}, fetched={}, total={}",
                                        itemDto.nodeId(),
                                        projId,
                                        contentType,
                                        itemDto.fieldValues() != null
                                                ? itemDto.fieldValues().size()
                                                : 0,
                                        itemDto.fieldValuesTotalCount());
                            }
                        }
                    }

                    return new ItemPageResult(itemsProcessed, itemsSkipped);
                });

                if (pageResult != null) {
                    totalSynced += pageResult.processedCount;
                    totalSkipped += pageResult.skippedCount;
                }

                var pageInfo = itemsConnection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // Persist cursor checkpoint after each successful page (REQUIRES_NEW transaction)
                if (cursor != null && hasMore) {
                    persistItemSyncCursor(projectId, cursor);
                }

                retryAttempt = 0;

                // Apply pagination throttle to prevent 502/504 errors under load
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Project item sync interrupted during throttle: projectId={}", projectId);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                }
            } catch (InstallationNotFoundException e) {
                throw e;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                Category category = classification.category();

                switch (category) {
                    case RETRYABLE -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                            retryAttempt++;
                            log.warn(
                                    "Retrying project item sync after transient error: projectId={}, attempt={}, error={}",
                                    projectId,
                                    retryAttempt,
                                    classification.message());
                            try {
                                ExponentialBackoff.sleep(retryAttempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                abortReason = SyncResult.Status.ABORTED_ERROR;
                                break;
                            }
                            continue;
                        }
                        log.error(
                                "Failed to sync project items after {} retries: projectId={}, error={}",
                                MAX_RETRY_ATTEMPTS,
                                projectId,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case RATE_LIMITED -> {
                        if (retryAttempt < MAX_RETRY_ATTEMPTS && classification.suggestedWait() != null) {
                            retryAttempt++;
                            long waitMs =
                                    Math.min(classification.suggestedWait().toMillis(), 300_000);
                            log.warn(
                                    "Rate limited during project item sync, waiting: projectId={}, waitMs={}",
                                    projectId,
                                    waitMs);
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                                break;
                            }
                            continue;
                        }
                        log.error("Aborting project item sync due to rate limiting: projectId={}", projectId);
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                    case NOT_FOUND -> {
                        log.warn(
                                "Resource not found during project item sync, skipping: projectId={}, error={}",
                                projectId,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    case AUTH_ERROR -> throw e;
                    case CLIENT_ERROR -> {
                        log.error(
                                "Aborting project item sync due to client error: projectId={}, error={}",
                                projectId,
                                classification.message());
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                    default -> {
                        log.error(
                                "Aborting project item sync due to error: projectId={}, category={}, error={}",
                                projectId,
                                category,
                                classification.message(),
                                e);
                        abortReason = SyncResult.Status.ABORTED_ERROR;
                        break;
                    }
                }
                break;
            }
        }

        // Raw nodes received vs items.totalCount (totalSynced is post-process).
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "projectItems",
                    itemsReceived,
                    reportedTotalCount,
                    hasMore || abortReason != null,
                    "projectId=" + projectId);
        }

        // Process remaining field values for Draft Issues that had truncated inline data
        // This follows the nested pagination pattern from GitHubIssueSyncService (comments)
        // and GitHubPullRequestSyncService (review threads)
        if (!itemsNeedingFieldValuePagination.isEmpty()) {
            log.info(
                    "Processing remaining field values for {} Draft Issues: projectId={}",
                    itemsNeedingFieldValuePagination.size(),
                    projectId);
            for (ItemWithFieldValueCursor itemWithCursor : itemsNeedingFieldValuePagination) {
                // Abort if we hit rate limit during follow-up pagination
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "field value pagination", "projectId", projectId)) {
                        abortReason = SyncResult.Status.ABORTED_RATE_LIMIT;
                        break;
                    }
                }
                syncRemainingFieldValues(scopeId, itemWithCursor);
            }
        }

        // Handle completion
        boolean syncCompletedNormally = !hasMore && abortReason == null;
        if (syncCompletedNormally) {
            // Clear cursor and update sync timestamp
            updateItemsSyncCompleted(projectId, Instant.now());

            // Remove stale items only on complete, non-resumed, non-filtered sync.
            // Resumed syncs and server-side filtered syncs only cover a subset of items,
            // so removal would incorrectly delete items that simply weren't re-fetched.
            if (!resuming && !serverSideFiltered) {
                ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
                int removedDrafts = itemProcessor.removeStaleDraftIssues(projectId, syncedItemNodeIds, context);
                if (removedDrafts > 0) {
                    log.debug("Removed stale Draft Issues: projectId={}, count={}", projectId, removedDrafts);
                }

                // Remove stale Issue/PR items that were removed from the project on GitHub (Bug 5).
                int removedIssuePr = itemProcessor.removeStaleIssuePrItems(projectId, syncedIssuePrNodeIds, context);
                if (removedIssuePr > 0) {
                    log.debug("Removed stale Issue/PR items: projectId={}, count={}", projectId, removedIssuePr);
                }
            } else {
                log.debug(
                        "Skipped stale item removal: reason={}, projectId={}, trackedDraftNodeIds={}, trackedIssuePrNodeIds={}",
                        resuming ? "resumedSync" : "serverSideFiltered",
                        projectId,
                        syncedItemNodeIds.size(),
                        syncedIssuePrNodeIds.size());
            }
        } else {
            log.warn(
                    "Skipped stale Draft Issue removal: reason=incompleteSync, projectId={}, pagesProcessed={}",
                    projectId,
                    pageCount);
        }

        // Determine final status based on item sync success and phase results
        boolean itemsSynced = abortReason == null && !hasMore;
        SyncResult.Status finalStatus;

        if (abortReason != null) {
            // Item sync was aborted
            finalStatus = abortReason;
        } else if (!itemsSynced || !fieldsSynced || !statusUpdatesSynced) {
            // Item sync incomplete or secondary phases failed
            finalStatus = SyncResult.Status.COMPLETED_WITH_WARNINGS;
        } else {
            // All phases completed successfully
            finalStatus = SyncResult.Status.COMPLETED;
        }

        log.info(
                "Completed Draft Issue sync: projectId={}, draftIssuesProcessed={}, skipped={}, fieldValuePaginations={}, "
                        + "resumed={}, incremental={}, serverFiltered={}, filterQuery={}, status={}, phases=[fields={}, statusUpdates={}, draftIssues={}]",
                projectId,
                totalSynced,
                totalSkipped,
                itemsNeedingFieldValuePagination.size(),
                resuming,
                incrementalSync,
                serverSideFiltered,
                filterQuery,
                finalStatus,
                fieldsSynced,
                statusUpdatesSynced,
                itemsSynced);

        // The per-phase booleans drive finalStatus (and the log line above); the SyncResult itself
        // only carries the resolved status + count.
        return switch (finalStatus) {
            case ABORTED_RATE_LIMIT -> SyncResult.abortedRateLimit(totalSynced);
            case ABORTED_ERROR -> SyncResult.abortedError(totalSynced);
            case COMPLETED_WITH_WARNINGS -> SyncResult.completedWithWarnings(totalSynced);
            case COMPLETED -> SyncResult.completed(totalSynced);
        };
    }

    /**
     * Synchronizes project field definitions with pagination support.
     * <p>
     * Handles projects with many fields (>100) by paginating through all pages.
     * Uses Mono.defer() with retry for transport error resilience.
     *
     * @return true if field sync completed successfully, false if aborted or failed
     */
    private PhaseResult syncProjectFields(HttpGraphQlClient client, Project project, Long scopeId) {
        String projectNodeId = project.getNodeId();
        if (projectNodeId == null) {
            return PhaseResult.SUCCESS; // Nothing to sync, consider it success
        }

        Long projectId = project.getId();
        List<String> allSyncedFieldIds = new ArrayList<>();
        int fieldsReceived = 0;
        boolean completedNormally = false;

        try {
            String cursor = null;
            boolean hasMore = true;
            int pageCount = 0;
            int reportedTotalCount = -1;

            while (hasMore) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for project fields: projectId={}, limit={}",
                            projectId,
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;

                // Use Mono.defer() for transport error retry
                // Uses PROJECT_FIELD_PAGE_SIZE (50) - fields have minimal nesting,
                // but 50 is sufficient for most projects and reduces response size.
                ClientGraphQlResponse graphQlResponse = Mono.defer(
                                () -> client.documentName(GET_PROJECT_WITH_FIELDS_DOCUMENT)
                                        .variable("nodeId", projectNodeId)
                                        .variable("fieldsFirst", PROJECT_FIELD_PAGE_SIZE)
                                        .variable("fieldsAfter", currentCursor)
                                        .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying project fields sync after transport error: projectId={}, attempt={}, error={}",
                                        projectId,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.graphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(graphQlResponse);
                    if (classification != null && classification.category() == Category.NOT_FOUND) {
                        log.info(
                                "Project not found via GraphQL for fields sync (may have been deleted): projectId={}",
                                projectId);
                        return PhaseResult.FAILED;
                    }
                    if (classification != null && classification.category() == Category.RATE_LIMITED) {
                        log.warn("Rate limited during project fields sync: projectId={}", projectId);
                        return PhaseResult.FAILED;
                    }
                    log.warn(
                            "Received invalid GraphQL response for project fields: projectId={}, errors={}",
                            projectId,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    return PhaseResult.FAILED;
                }

                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "project fields", "projectId", projectId)) {
                        return PhaseResult.FAILED;
                    }
                }

                GHProjectV2FieldConfigurationConnection fieldsConnection =
                        graphQlResponse.field("node.fields").toEntity(GHProjectV2FieldConfigurationConnection.class);

                if (fieldsConnection == null
                        || fieldsConnection.getNodes() == null
                        || fieldsConnection.getNodes().isEmpty()) {
                    // On the first page, distinguish "empty project" from "deleted project":
                    // If `node` itself is null, the project was deleted from GitHub.
                    if (pageCount == 1 && graphQlResponse.field("node").getValue() == null) {
                        log.warn(
                                "Project not found on GitHub (node is null), marking for deletion: projectId={}, nodeId={}",
                                projectId,
                                projectNodeId);
                        return PhaseResult.PROJECT_NOT_FOUND;
                    }
                    completedNormally = true;
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = fieldsConnection.getTotalCount();
                }
                fieldsReceived += fieldsConnection.getNodes().size();

                // Process this page of fields in a transaction
                transactionTemplate.executeWithoutResult(status -> {
                    Project managedProject =
                            projectRepository.findById(projectId).orElse(null);
                    if (managedProject == null) {
                        return;
                    }

                    for (GHProjectV2FieldConfiguration fieldConfig : fieldsConnection.getNodes()) {
                        GitHubProjectFieldDTO fieldDto = GitHubProjectFieldDTO.fromFieldConfiguration(fieldConfig);
                        if (fieldDto == null || fieldDto.id() == null) {
                            continue;
                        }

                        allSyncedFieldIds.add(fieldDto.id());

                        fieldValueSyncService.upsertFieldDefinition(fieldDto, projectId);
                    }
                });

                var pageInfo = fieldsConnection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // Apply pagination throttle
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Project fields sync interrupted during throttle: projectId={}", projectId);
                        return PhaseResult.FAILED;
                    }
                }
            }

            // Set completedNormally only if not already set (e.g., by empty response branch)
            // This preserves true if already set via early break, otherwise uses hasMore state
            completedNormally = completedNormally || !hasMore;

            // Raw nodes received vs fields.totalCount (allSyncedFieldIds is post-filter).
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "projectFields",
                        fieldsReceived,
                        reportedTotalCount,
                        !completedNormally,
                        "projectId=" + project.getId());
            }

            // On successful completion, handle cleanup and update timestamp
            if (completedNormally) {
                // Remove stale fields only if we have synced IDs to compare against
                if (!allSyncedFieldIds.isEmpty()) {
                    transactionTemplate.executeWithoutResult(status -> {
                        int removed = fieldValueSyncService.removeStaleFieldDefinitions(projectId, allSyncedFieldIds);
                        if (removed > 0) {
                            log.debug("Removed stale project fields: projectId={}, count={}", projectId, removed);
                        }
                    });
                }

                // Update fields synced timestamp (even for projects with zero fields)
                updateFieldsSyncCompleted(projectId, Instant.now());
            }

            log.debug(
                    "Synced project fields: projectId={}, fieldCount={}, success={}",
                    projectId,
                    allSyncedFieldIds.size(),
                    completedNormally);
            return completedNormally ? PhaseResult.SUCCESS : PhaseResult.FAILED;
        } catch (Exception e) {
            log.warn("Failed to sync project fields: projectId={}, error={}", projectId, e.getMessage(), e);
            return PhaseResult.FAILED;
        }
    }

    /**
     * Updates field sync completion state in a new transaction.
     * <p>
     * Clears the cursor and sets the fieldsSyncedAt timestamp.
     */
    private void updateFieldsSyncCompleted(Long projectId, Instant syncedAt) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            projectRepository.updateFieldsSyncedAt(projectId, syncedAt);
            log.debug("Updated project fields sync completion: projectId={}", projectId);
        });
    }

    /**
     * Synchronizes project status updates.
     * <p>
     * Status updates track project health with ON_TRACK, AT_RISK, OFF_TRACK statuses.
     * Uses Mono.defer() with retry for transport error resilience.
     *
     * @param client   the GraphQL client
     * @param project  the project to sync status updates for
     * @param scopeId  the scope ID for rate limit tracking
     * @param provider the git provider instance for processing context
     * @return true if status update sync completed successfully, false if aborted or failed
     */
    private boolean syncProjectStatusUpdates(
            HttpGraphQlClient client, Project project, Long scopeId, IdentityProvider provider) {
        String projectNodeId = project.getNodeId();
        if (projectNodeId == null) {
            return true; // Nothing to sync, consider it success
        }

        Long projectId = project.getId();
        boolean completedNormally = false;

        try {
            List<String> syncedStatusUpdateNodeIds = new ArrayList<>();
            int statusUpdatesReceived = 0;
            String cursor = null;
            boolean hasMore = true;
            int pageCount = 0;
            int reportedTotalCount = -1;

            while (hasMore) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for status updates: projectId={}, limit={}",
                            projectId,
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;

                // Uses STATUS_UPDATE_PAGE_SIZE (100) - status updates have minimal nesting
                // (only creator field), so full page size is cost-efficient.
                ClientGraphQlResponse graphQlResponse = Mono.defer(
                                () -> client.documentName(GET_PROJECT_STATUS_UPDATES_DOCUMENT)
                                        .variable("nodeId", projectNodeId)
                                        .variable(
                                                "first",
                                                adaptPageSize(
                                                        STATUS_UPDATE_PAGE_SIZE,
                                                        graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                        .variable("after", currentCursor)
                                        .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying status updates sync after transport error: projectId={}, attempt={}, error={}",
                                        projectId,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.graphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = exceptionClassifier.classifyGraphQlResponse(graphQlResponse);
                    if (classification != null && classification.category() == Category.NOT_FOUND) {
                        log.info(
                                "Project not found via GraphQL for status updates (may have been deleted): projectId={}",
                                projectId);
                        return false;
                    }
                    if (classification != null && classification.category() == Category.RATE_LIMITED) {
                        log.warn("Rate limited during status updates sync: projectId={}", projectId);
                        return false;
                    }
                    log.warn(
                            "Received invalid GraphQL response for status updates: projectId={}, errors={}",
                            projectId,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    return false;
                }

                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!waitForRateLimitIfNeeded(scopeId, "status updates", "projectId", projectId)) {
                        return false;
                    }
                }

                GHProjectV2StatusUpdateConnection statusUpdatesConnection =
                        graphQlResponse.field("node.statusUpdates").toEntity(GHProjectV2StatusUpdateConnection.class);

                if (statusUpdatesConnection == null
                        || statusUpdatesConnection.getNodes() == null
                        || statusUpdatesConnection.getNodes().isEmpty()) {
                    completedNormally = true;
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = statusUpdatesConnection.getTotalCount();
                }
                statusUpdatesReceived += statusUpdatesConnection.getNodes().size();

                // Process this page of status updates in a transaction
                transactionTemplate.executeWithoutResult(status -> {
                    Project managedProject =
                            projectRepository.findById(projectId).orElse(null);
                    if (managedProject == null) {
                        return;
                    }

                    ProcessingContext context = ProcessingContext.forSync(scopeId, provider);

                    for (GHProjectV2StatusUpdate graphQlStatusUpdate : statusUpdatesConnection.getNodes()) {
                        GitHubProjectStatusUpdateDTO dto =
                                GitHubProjectStatusUpdateDTO.fromStatusUpdate(graphQlStatusUpdate);
                        if (dto == null || dto.nodeId() == null) {
                            continue;
                        }

                        syncedStatusUpdateNodeIds.add(dto.nodeId());
                        statusUpdateProcessor.process(dto, managedProject, context);
                    }
                });

                var pageInfo = statusUpdatesConnection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;

                // Apply pagination throttle
                if (hasMore && !syncProperties.paginationThrottle().isZero()) {
                    try {
                        Thread.sleep(syncProperties.paginationThrottle().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Status updates sync interrupted during throttle: projectId={}", projectId);
                        return false;
                    }
                }
            }

            // Set completedNormally only if not already set (e.g., by empty response branch)
            // This preserves true if already set via early break, otherwise uses hasMore state
            completedNormally = completedNormally || !hasMore;

            // Raw nodes received vs statusUpdates.totalCount (syncedStatusUpdateNodeIds is post-filter).
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "statusUpdates",
                        statusUpdatesReceived,
                        reportedTotalCount,
                        !completedNormally,
                        "projectId=" + project.getId());
            }

            // On successful completion, handle cleanup and update timestamp
            if (completedNormally) {
                // Remove stale status updates only if we have synced IDs to compare against
                if (!syncedStatusUpdateNodeIds.isEmpty()) {
                    transactionTemplate.executeWithoutResult(status -> {
                        ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
                        int removed = statusUpdateProcessor.removeStaleStatusUpdates(
                                projectId, syncedStatusUpdateNodeIds, context);
                        if (removed > 0) {
                            log.debug("Removed stale status updates: projectId={}, count={}", projectId, removed);
                        }
                    });
                }

                // Update status updates synced timestamp (even for projects with zero updates)
                updateStatusUpdatesSyncCompleted(projectId, Instant.now());
            }

            log.debug(
                    "Synced project status updates: projectId={}, count={}, success={}",
                    projectId,
                    syncedStatusUpdateNodeIds.size(),
                    completedNormally);
            return completedNormally;
        } catch (Exception e) {
            log.warn("Failed to sync project status updates: projectId={}, error={}", projectId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Updates status update sync completion state in a new transaction.
     * <p>
     * Clears the cursor and sets the statusUpdatesSyncedAt timestamp.
     */
    private void updateStatusUpdatesSyncCompleted(Long projectId, Instant syncedAt) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            projectRepository.updateStatusUpdatesSyncedAt(projectId, syncedAt);
            log.debug("Updated project status updates sync completion: projectId={}", projectId);
        });
    }

    /**
     * Removes projects that no longer exist in the organization.
     */
    private void removeDeletedProjects(
            Long organizationId, Set<Long> syncedProjectIds, Long scopeId, IdentityProvider provider) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Project> existingProjects =
                    projectRepository.findAllByOwnerTypeAndOwnerId(Project.OwnerType.ORGANIZATION, organizationId);

            ProcessingContext context = ProcessingContext.forSync(scopeId, provider);
            int removed = 0;

            for (Project project : existingProjects) {
                if (!syncedProjectIds.contains(project.getId())) {
                    projectProcessor.delete(project.getId(), context);
                    removed++;
                }
            }

            if (removed > 0) {
                log.info("Removed stale projects: orgId={}, projectCount={}", organizationId, removed);
            }
        });
    }

    /**
     * Persists the item sync cursor checkpoint in a new transaction.
     * <p>
     * Uses REQUIRES_NEW propagation to ensure cursor is saved even if caller
     * transaction rolls back. Delegates to {@link BackfillStateProvider} SPI
     * for consistent cursor persistence across all sync services.
     */
    private void persistItemSyncCursor(Long projectId, String cursor) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            backfillStateProvider.updateProjectItemSyncCursor(projectId, cursor);
            log.debug("Persisted project item sync cursor: projectId={}", projectId);
        });
    }

    /**
     * Updates sync completion state in a new transaction.
     * <p>
     * Clears the cursor and sets the itemsSyncedAt timestamp. Delegates to
     * {@link BackfillStateProvider} SPI for consistent state persistence
     * across all sync services.
     */
    private void updateItemsSyncCompleted(Long projectId, Instant syncedAt) {
        TransactionTemplate requiresNewTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTemplate.executeWithoutResult(status -> {
            backfillStateProvider.updateProjectItemsSyncedAt(projectId, syncedAt);
            log.debug("Updated project items sync completion: projectId={}", projectId);
        });
    }

    /**
     * Gets projects for an organization that need item sync, respecting cooldown.
     * <p>
     * Returns all projects ordered by last sync time (oldest first) that haven't
     * been synced within the cooldown period (from {@code SyncSchedulerProperties}).
     * <p>
     * This mirrors the repository sync behavior in {@code GitHubDataSyncService.shouldSync()}.
     *
     * @param organizationId the organization ID
     * @return list of projects needing item sync
     */
    public List<Project> getProjectsNeedingItemSync(Long organizationId) {
        Instant cooldownThreshold = Instant.now().minusSeconds(syncSchedulerProperties.cooldownMinutes() * 60L);
        return projectRepository.findProjectsNeedingItemSync(
                Project.OwnerType.ORGANIZATION, organizationId, cooldownThreshold);
    }

    /**
     * Processes field values for a project item.
     * <p>
     * Delegates to {@link GitHubProjectItemFieldValueSyncService} for actual persistence.
     * When field values are truncated (more than inline page size), stale removal
     * is deferred to {@link #syncRemainingFieldValues} which tracks all processed
     * field IDs across both inline and paginated phases.
     *
     * @param itemId      the item's database ID
     * @param fieldValues the field values from the GraphQL response
     * @param truncated   whether the inline field values were truncated (more pages exist)
     * @return list of processed field IDs (for tracking across pagination phases)
     */
    private List<String> processFieldValues(
            Long itemId, List<GitHubProjectFieldValueDTO> fieldValues, boolean truncated) {
        return fieldValueSyncService.processFieldValues(itemId, fieldValues, truncated, null);
    }

    /**
     * Syncs remaining field values for an item that had truncated inline data.
     * <p>
     * This method paginates through the remaining field values using the
     * {@code GetProjectItemFieldValues} GraphQL query, starting from the cursor
     * where the inline data was truncated.
     * <p>
     * Tracks all processed field IDs (from both the initial inline phase and
     * paginated phases) and removes stale field values when pagination completes
     * normally. This matches the behavior in
     * {@link GitHubProjectItemFieldValueSyncService#syncRemainingFieldValues}.
     * <p>
     * This follows the same nested pagination pattern used in:
     * <ul>
     *   <li>{@code GitHubIssueSyncService.syncRemainingComments()} for issue comments</li>
     *   <li>{@code GitHubPullRequestSyncService.syncRemainingReviews()} for PR reviews</li>
     * </ul>
     *
     * @param scopeId        the scope ID for authentication
     * @param itemWithCursor the item with cursor information and initial field IDs
     */
    private void syncRemainingFieldValues(Long scopeId, ItemWithFieldValueCursor itemWithCursor) {
        fieldValueSyncService.syncRemainingFieldValues(
                scopeId,
                itemWithCursor.itemNodeId(),
                itemWithCursor.itemId(),
                itemWithCursor.fieldValueCursor(),
                itemWithCursor.initialFieldIds());
    }

    private boolean waitForRateLimitIfNeeded(Long scopeId, String phase, String scopeLabel, Object scopeValue) {
        try {
            boolean waited = graphQlClientProvider.waitIfRateLimitLow(scopeId);
            if (waited) {
                log.info("Paused due to critical rate limit: phase={}, {}={}", phase, scopeLabel, scopeValue);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for rate limit reset: phase={}, {}={}", phase, scopeLabel, scopeValue);
            return false;
        }
    }

    /**
     * Result container for project list page processing.
     */
    private record PageResult(int count, int skipped) {}

    /**
     * Result container for project item page processing with incremental sync support.
     * Tracks both processed items (updated) and skipped items (unchanged since last sync).
     */
    private record ItemPageResult(int processedCount, int skippedCount) {}

    /**
     * Record for tracking items that need follow-up field value pagination.
     * <p>
     * When an item has more than 20 field values (the inline page size), we need to
     * fetch the remaining field values using a separate query. This follows the same
     * pattern as {@code IssueWithCommentCursor} in GitHubIssueSyncService.
     *
     * @param itemId           the database ID of the item
     * @param itemNodeId       the GraphQL node ID of the item (used in follow-up query)
     * @param fieldValueCursor the cursor to resume field value pagination from
     * @param initialFieldIds  field IDs already processed from the inline data (for stale removal)
     */
    private record ItemWithFieldValueCursor(
            Long itemId, String itemNodeId, String fieldValueCursor, List<String> initialFieldIds) {}
}
