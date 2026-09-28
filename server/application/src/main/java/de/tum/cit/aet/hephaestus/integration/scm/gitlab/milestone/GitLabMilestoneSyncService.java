package de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneDTO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;

/**
 * Service for synchronizing GitLab milestones via GraphQL API.
 * <p>
 * Two entry points:
 * <ul>
 *     <li>{@link #syncMilestonesForRepository(Long, Repository)} — fetches project-level milestones
 *         using {@code project.milestones(includeAncestors: true)}. Group milestones that happen
 *         to surface through this query are stored with a deterministic negative
 *         {@code nativeId} to avoid {@code (provider_id, native_id)} collisions across projects.</li>
 *     <li>{@link #syncMilestonesForGroup(Long, String, List)} — fetches group-level milestones
 *         (including descendant subgroups) and attaches each discovered milestone to every
 *         repository in the group. Closes a gap where GitLab's {@code project.milestones}
 *         field does not reliably surface group milestones in all instance configurations.</li>
 * </ul>
 * After a complete sync, stale milestones (present in DB but not on GitLab) are removed.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMilestoneSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabMilestoneSyncService.class);
    private static final String GET_PROJECT_MILESTONES_DOCUMENT = "GetProjectMilestones";
    private static final String GET_GROUP_MILESTONES_DOCUMENT = "GetGroupMilestones";

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabMilestoneProcessor milestoneProcessor;
    private final MilestoneRepository milestoneRepository;
    private final GitLabProperties gitLabProperties;

    public GitLabMilestoneSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabMilestoneProcessor milestoneProcessor,
            MilestoneRepository milestoneRepository,
            GitLabProperties gitLabProperties) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.milestoneProcessor = milestoneProcessor;
        this.milestoneRepository = milestoneRepository;
        this.gitLabProperties = gitLabProperties;
    }

    /**
     * Synchronizes all milestones for a repository using GraphQL.
     *
     * @param scopeId    the workspace/scope ID for authentication
     * @param repository the repository to sync milestones for
     * @return sync result with status and count
     */
    public SyncResult syncMilestonesForRepository(Long scopeId, Repository repository) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");

        log.info("Starting milestone sync: scopeId={}, projectPath={}", scopeId, safeProjectPath);

        int totalSynced = 0;
        Set<Integer> syncedNumbers = new HashSet<>();
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        boolean rateLimitAborted = false;
        boolean errorAborted = false;

        try {
            do {
                if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                    log.warn("Reached max pagination pages: scopeId={}, projectPath={}", scopeId, safeProjectPath);
                    break;
                }

                graphQlClientProvider.acquirePermission();

                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Milestone sync interrupted: scopeId={}, projectPath={}", scopeId, safeProjectPath);
                    rateLimitAborted = true;
                    break;
                }

                int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = GitLabSyncConstants.adaptPageSize(GitLabSyncConstants.LARGE_PAGE_SIZE, remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_PROJECT_MILESTONES_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "milestones for " + safeProjectPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    errorAborted = true;
                    break;
                }

                graphQlClientProvider.recordSuccess();

                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Map<String, Object>> nodes = (List) Objects.requireNonNull(response)
                        .field("project.milestones.nodes")
                        .toEntityList(Map.class);

                if (nodes == null || nodes.isEmpty()) {
                    break;
                }

                ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
                for (Map<String, Object> node : nodes) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedNode = node;
                    GitLabMilestoneDTO dto = GitLabMilestoneDTO.fromGraphQlNode(typedNode);
                    if (dto != null) {
                        Milestone milestone = milestoneProcessor.process(dto, repository, context);
                        if (milestone != null) {
                            totalSynced++;
                            syncedNumbers.add(milestone.getNumber());
                        }
                    }
                }

                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("project.milestones.pageInfo")
                        .toEntity(GitLabPageInfo.class);
                cursor = pageInfo != null ? pageInfo.endCursor() : null;
                page++;

                if (pageInfo == null || !pageInfo.hasNextPage()) {
                    break;
                }
                if (responseHandler.isPaginationLoop(
                        cursor, previousCursor, "milestones for " + safeProjectPath, log)) {
                    errorAborted = true;
                    break;
                }
                previousCursor = cursor;
            } while (true);
        } catch (Exception e) {
            log.error("Error during milestone sync: scopeId={}, projectPath={}", scopeId, safeProjectPath, e);
            errorAborted = true;
        }

        boolean syncComplete = !rateLimitAborted && !errorAborted && page < GitLabSyncConstants.MAX_PAGINATION_PAGES;

        // Remove stale milestones only if sync completed fully
        if (syncComplete) {
            int removedCount = removeStaleMilestones(
                    repository.getId(), syncedNumbers, ProcessingContext.forSync(scopeId, repository));
            if (removedCount > 0) {
                log.info("Removed stale milestones: removedCount={}, projectPath={}", removedCount, safeProjectPath);
            }
        } else {
            log.warn(
                    "Skipped stale milestone removal: reason=incompleteSync, projectPath={}, pagesProcessed={}",
                    safeProjectPath,
                    page);
        }

        log.info(
                "Completed milestone sync: projectPath={}, milestoneCount={}, complete={}, scopeId={}",
                safeProjectPath,
                totalSynced,
                syncComplete,
                scopeId);

        if (rateLimitAborted) {
            return SyncResult.abortedRateLimit(totalSynced);
        }
        if (errorAborted) {
            return SyncResult.abortedError(totalSynced);
        }
        return SyncResult.completed(totalSynced);
    }

    /**
     * Synchronizes group milestones (including ancestors and descendant subgroups) and attaches
     * every discovered milestone to each repository in {@code repositoriesInGroup}.
     * <p>
     * <b>Scoping tradeoff (pattern b):</b> GitLab group milestones span all projects in a group,
     * but the {@code milestone} table is rooted at {@code repository_id} for per-repository
     * reads. We therefore fan out each group milestone to every repo in the group.
     * Each fan-out row uses a deterministic negative {@code nativeId} keyed on
     * {@code (repositoryId, iid)} (see {@link GitLabMilestoneProcessor#process}), which avoids
     * {@code (provider_id, native_id)} collisions without introducing a new schema column.
     * Pagination continues until {@code hasNextPage=false} or the response list is empty.
     *
     * @param scopeId              workspace/scope ID for authentication
     * @param groupFullPath        the top-level group path (e.g. {@code "ase/ipraktikum/ios2526"})
     * @param repositoriesInGroup  repositories that should receive the milestone fan-out
     * @return sync result with total number of milestone rows written (milestones * repos)
     */
    public SyncResult syncMilestonesForGroup(Long scopeId, String groupFullPath, List<Repository> repositoriesInGroup) {
        String safeGroupPath = sanitizeForLog(groupFullPath);

        if (groupFullPath == null || groupFullPath.isBlank()) {
            log.debug("Skipping group milestone sync: reason=blankGroupPath, scopeId={}", scopeId);
            return SyncResult.completed(0);
        }
        if (repositoriesInGroup == null || repositoriesInGroup.isEmpty()) {
            log.debug("Skipping group milestone sync: reason=noRepos, scopeId={}, group={}", scopeId, safeGroupPath);
            return SyncResult.completed(0);
        }

        log.info(
                "Starting group milestone sync: scopeId={}, group={}, repoCount={}",
                scopeId,
                safeGroupPath,
                repositoriesInGroup.size());

        List<GitLabMilestoneDTO> collected = new ArrayList<>();
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        boolean rateLimitAborted = false;
        boolean errorAborted = false;

        try {
            do {
                if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached max pagination pages on group milestones: scopeId={}, group={}",
                            scopeId,
                            safeGroupPath);
                    break;
                }

                graphQlClientProvider.acquirePermission();

                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Group milestone sync interrupted: scopeId={}, group={}", scopeId, safeGroupPath);
                    rateLimitAborted = true;
                    break;
                }

                int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = GitLabSyncConstants.adaptPageSize(GitLabSyncConstants.LARGE_PAGE_SIZE, remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_GROUP_MILESTONES_DOCUMENT)
                        .variable("fullPath", groupFullPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .variable("includeAncestors", true)
                        .variable("includeDescendants", true)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "group milestones for " + safeGroupPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(
                            new GitLabSyncException("Invalid GraphQL response on group milestones"));
                    errorAborted = true;
                    break;
                }

                graphQlClientProvider.recordSuccess();

                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Map<String, Object>> nodes = (List) Objects.requireNonNull(response)
                        .field("group.milestones.nodes")
                        .toEntityList(Map.class);

                if (nodes == null || nodes.isEmpty()) {
                    break;
                }

                for (Map<String, Object> node : nodes) {
                    // Force groupMilestone=true so the processor routes these rows onto the
                    // deterministic-negative nativeId path (avoids (provider_id, native_id)
                    // collisions when the same group milestone is fanned out to every repo).
                    node.put("groupMilestone", Boolean.TRUE);
                    GitLabMilestoneDTO dto = GitLabMilestoneDTO.fromGraphQlNode(node);
                    if (dto != null) {
                        collected.add(dto);
                    }
                }

                GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                        .field("group.milestones.pageInfo")
                        .toEntity(GitLabPageInfo.class);
                cursor = pageInfo != null ? pageInfo.endCursor() : null;
                page++;

                if (pageInfo == null || !pageInfo.hasNextPage()) {
                    break;
                }
                if (responseHandler.isPaginationLoop(
                        cursor, previousCursor, "group milestones for " + safeGroupPath, log)) {
                    errorAborted = true;
                    break;
                }
                previousCursor = cursor;
            } while (true);
        } catch (Exception e) {
            log.error("Error during group milestone sync: scopeId={}, group={}", scopeId, safeGroupPath, e);
            errorAborted = true;
        }

        int totalWritten = 0;
        if (!collected.isEmpty()) {
            for (Repository repo : repositoriesInGroup) {
                ProcessingContext context = ProcessingContext.forSync(scopeId, repo);
                for (GitLabMilestoneDTO dto : collected) {
                    try {
                        Milestone saved = milestoneProcessor.process(dto, repo, context);
                        if (saved != null) {
                            totalWritten++;
                        }
                    } catch (Exception e) {
                        log.warn(
                                "Failed to persist group milestone: iid={}, repo={}, error={}",
                                dto.iid(),
                                sanitizeForLog(repo.getNameWithOwner()),
                                e.getMessage());
                    }
                }
            }
        }

        log.info(
                "Completed group milestone sync: scopeId={}, group={}, milestones={}, repos={}, written={}",
                scopeId,
                safeGroupPath,
                collected.size(),
                repositoriesInGroup.size(),
                totalWritten);

        if (rateLimitAborted) {
            return SyncResult.abortedRateLimit(totalWritten);
        }
        if (errorAborted) {
            return SyncResult.abortedError(totalWritten);
        }
        return SyncResult.completed(totalWritten);
    }

    private int removeStaleMilestones(Long repositoryId, Set<Integer> syncedNumbers, ProcessingContext context) {
        List<Milestone> existing = milestoneRepository.findAllByRepository_Id(repositoryId);
        int removedCount = 0;
        for (Milestone milestone : existing) {
            if (!syncedNumbers.contains(milestone.getNumber())) {
                milestoneProcessor.delete(milestone.getId(), context);
                removedCount++;
            }
        }
        return removedCount;
    }
}
