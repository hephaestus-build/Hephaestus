package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronizes GitLab project members as repository collaborators.
 * <p>
 * Uses the GitLab GraphQL {@code project.projectMembers} connection to
 * fetch all members with their access levels, then maps them to
 * {@link RepositoryCollaborator} entities.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabCollaboratorSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabCollaboratorSyncService.class);
    private static final String GET_PROJECT_MEMBERS_DOCUMENT = "GetProjectMembers";
    private static final int LARGE_PAGE_SIZE = 100;

    private final RepositoryRepository repositoryRepository;
    private final RepositoryCollaboratorRepository collaboratorRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabUserService userService;
    private final GitLabProperties gitLabProperties;
    private final TransactionTemplate transactionTemplate;
    private final GitLabWorkspaceLinkService workspaceLinkService;

    public GitLabCollaboratorSyncService(
            RepositoryRepository repositoryRepository,
            RepositoryCollaboratorRepository collaboratorRepository,
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabUserService userService,
            GitLabProperties gitLabProperties,
            TransactionTemplate transactionTemplate,
            GitLabWorkspaceLinkService workspaceLinkService) {
        this.repositoryRepository = repositoryRepository;
        this.collaboratorRepository = collaboratorRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.userService = userService;
        this.gitLabProperties = gitLabProperties;
        this.transactionTemplate = transactionTemplate;
        this.workspaceLinkService = workspaceLinkService;
    }

    /**
     * Syncs all collaborators for a repository from GitLab.
     * <p>
     * Only the scope that owns the project per {@link GitLabWorkspaceLinkService#mayWriteRepository} syncs it.
     * Every page is read before anything is written, outside a transaction. Only a complete listing, whose pages
     * are whole and error-free and whose entries all name a user and an access level, is applied, in one short
     * transaction that also removes the collaborators it no longer lists. Anything less changes nothing: a
     * collaborator is a team member through {@code GitLabTeamSyncService}, so a false removal here would revoke a
     * student's workspace access.
     *
     * @param scopeId      the workspace scope ID
     * @param repository   the repository to sync collaborators for
     * @return sync result
     */
    public SyncResult syncCollaboratorsForRepository(Long scopeId, Repository repository) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        // The scope's client reads its own instance, so its answer belongs only on a repository row of that instance.
        Long providerId = repository.getProvider() == null
                ? null
                : repository.getProvider().getId();
        // Collaborator rows are shared by every workspace monitoring the project and decide project-only team
        // membership, so only the scope whose reading of the project is authoritative replaces them.
        if (providerId == null || !workspaceLinkService.mayWriteRepository(scopeId, repository)) {
            log.warn("Skipped collaborator sync: reason=repositoryNotThisScopes, projectPath={}", safeProjectPath);
            return SyncResult.abortedError(0);
        }

        Map<String, ListedCollaborator> listedByGlobalId = new LinkedHashMap<>();
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        boolean complete = false;

        try {
            while (page < MAX_PAGINATION_PAGES) {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);

                int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = adaptPageSize(LARGE_PAGE_SIZE, remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
                ClientGraphQlResponse response = client.documentName(GET_PROJECT_MEMBERS_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "collaborators for " + safeProjectPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(
                            new GitLabSyncException("Invalid response for project members"));
                    break;
                }
                graphQlClientProvider.recordSuccess();
                if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
                    log.warn("Stopped collaborator sync: reason=fieldErrors, projectPath={}", safeProjectPath);
                    break;
                }
                if (!responseHandler.isWholePage(response, "project.projectMembers")) {
                    log.warn("Stopped collaborator sync: reason=pageNotWhole, projectPath={}", safeProjectPath);
                    break;
                }

                boolean everyEntryReadable = true;
                for (Object node :
                        response.field("project.projectMembers.nodes").toEntityList(Map.class)) {
                    ListedCollaborator listed = ListedCollaborator.of(node);
                    if (listed == null) {
                        everyEntryReadable = false;
                        break;
                    }
                    listedByGlobalId.merge(listed.globalId(), listed, ListedCollaborator::higher);
                }
                if (!everyEntryReadable) {
                    log.warn("Stopped collaborator sync: reason=unreadableMember, projectPath={}", safeProjectPath);
                    break;
                }

                GitLabPageInfo pageInfo = Objects.requireNonNull(
                        response.field("project.projectMembers.pageInfo").toEntity(GitLabPageInfo.class));
                if (!pageInfo.hasNextPage()) {
                    complete = true;
                    break;
                }
                cursor = pageInfo.endCursor();
                if (cursor == null
                        || responseHandler.isPaginationLoop(
                                cursor, previousCursor, "collaborators for " + safeProjectPath, log)) {
                    break;
                }
                previousCursor = cursor;
                page++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Collaborator sync interrupted: projectPath={}", safeProjectPath);
            return SyncResult.abortedRateLimit(0);
        } catch (Exception e) {
            log.warn("Collaborator sync failed: projectPath={}", safeProjectPath, e);
        }

        if (!complete) {
            log.warn("Skipped collaborator changes: reason=incompleteListing, projectPath={}", safeProjectPath);
            return SyncResult.abortedError(0);
        }
        Integer synced = transactionTemplate.execute(status -> {
            // The monitor, the connection or the group link may have changed while GitLab was being read.
            if (!workspaceLinkService.mayWriteRepository(scopeId, repository)) {
                return null;
            }
            Set<Long> syncedUserIds = new HashSet<>();
            for (ListedCollaborator listed : listedByGlobalId.values()) {
                User user = userService.findOrCreateUser(listed.lookup(), providerId);
                if (user == null) {
                    status.setRollbackOnly();
                    return null;
                }
                syncedUserIds.add(user.getId());
                RepositoryCollaborator collaborator = collaboratorRepository
                        .findByRepositoryIdAndUserId(repository.getId(), user.getId())
                        .orElse(null);
                if (collaborator == null) {
                    collaborator = new RepositoryCollaborator(repository, user, listed.permission());
                } else {
                    collaborator.updatePermission(listed.permission());
                }
                collaboratorRepository.save(collaborator);
            }
            removeStaleCollaborators(repository.getId(), syncedUserIds);
            return syncedUserIds.size();
        });
        if (synced == null) {
            log.warn(
                    "Skipped collaborator changes: reason=userNotResolvedOrRepositoryNotThisScopes, projectPath={}",
                    safeProjectPath);
            return SyncResult.abortedError(0);
        }
        return SyncResult.completed(synced);
    }

    /**
     * One project member GitLab listed with a readable user and access level; a user listed through a direct and an
     * inherited grant holds the higher one.
     */
    private record ListedCollaborator(String globalId, GitLabUserLookup lookup, int accessLevel) {

        /** The entry, or null when it does not say who the member is or what access they hold. */
        static @Nullable ListedCollaborator of(@Nullable Object node) {
            if (!(node instanceof Map<?, ?> member)
                    || !(member.get("user") instanceof Map<?, ?> user)
                    || !(user.get("id") instanceof String globalId)
                    || !(user.get("username") instanceof String username)
                    || !(member.get("accessLevel") instanceof Map<?, ?> access)
                    || !(access.get("integerValue") instanceof Number level)) {
                return null;
            }
            return new ListedCollaborator(
                    globalId,
                    GitLabUserLookup.of(
                            globalId,
                            username,
                            user.get("name") instanceof String name ? name : null,
                            user.get("avatarUrl") instanceof String avatarUrl ? avatarUrl : null,
                            user.get("webUrl") instanceof String webUrl ? webUrl : null),
                    level.intValue());
        }

        RepositoryCollaborator.Permission permission() {
            return mapGitLabAccessLevel(accessLevel);
        }

        ListedCollaborator higher(ListedCollaborator other) {
            return other.accessLevel > accessLevel ? other : this;
        }
    }

    private void removeStaleCollaborators(Long repositoryId, Set<Long> syncedUserIds) {
        List<RepositoryCollaborator> existing = collaboratorRepository.findByRepository_Id(repositoryId);
        int removed = 0;
        for (RepositoryCollaborator collab : existing) {
            if (!syncedUserIds.contains(collab.getUser().getId())) {
                collaboratorRepository.delete(collab);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed stale collaborators: repoId={}, count={}", repositoryId, removed);
        }
    }

    /**
     * Maps GitLab integer access levels to RepositoryCollaborator.Permission.
     * <ul>
     *   <li>10 (Guest) → READ</li>
     *   <li>20 (Reporter) → TRIAGE</li>
     *   <li>30 (Developer) → WRITE</li>
     *   <li>40 (Maintainer) → MAINTAIN</li>
     *   <li>50 (Owner) → ADMIN</li>
     * </ul>
     */
    static RepositoryCollaborator.Permission mapGitLabAccessLevel(int level) {
        return switch (level) {
            case 10 -> RepositoryCollaborator.Permission.READ;
            case 20 -> RepositoryCollaborator.Permission.TRIAGE;
            case 30 -> RepositoryCollaborator.Permission.WRITE;
            case 40 -> RepositoryCollaborator.Permission.MAINTAIN;
            case 50 -> RepositoryCollaborator.Permission.ADMIN;
            default -> RepositoryCollaborator.Permission.UNKNOWN;
        };
    }
}
