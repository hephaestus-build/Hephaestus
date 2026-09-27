package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.DEFAULT_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.adaptPageSize;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractNumericId;

import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.OrganizationSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabAccessLevel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabMemberUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service for synchronizing GitLab group memberships via GraphQL API.
 * <p>
 * Closes the security gap where users removed from a GitLab group retain
 * workspace access indefinitely. This is the GitLab counterpart of the
 * membership sync in {@code GitHubOrganizationSyncService}.
 * <p>
 * The sync populates {@code OrganizationMembership} records and fires
 * {@link OrganizationMembershipListener#onOrganizationMembershipsSynced}
 * so downstream modules (e.g., workspace) can reconcile their member lists.
 * <p>
 * <b>Concurrency:</b> User upserts follow the same pattern as
 * {@code GitHubUserProcessor}: advisory lock → free login conflicts → native SQL
 * upsert, all within an isolated REQUIRES_NEW transaction to prevent unique
 * constraint violations on login renames.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabGroupMemberSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabGroupMemberSyncService.class);
    private static final String GET_GROUP_MEMBERS_DOCUMENT = "GetGroupMembers";

    /**
     * GitLab access levels at or above this threshold map to {@link OrganizationMemberRole#ADMIN}.
     * MAINTAINER(40) and OWNER(50) are considered admins.
     */
    private static final int ADMIN_ACCESS_LEVEL_THRESHOLD = 40;

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final UserRepository userRepository;
    private final GitLabWorkspaceLinkService workspaceLinkService;
    private final GitLabProperties gitLabProperties;
    private final @Nullable OrganizationMembershipListener organizationMembershipListener;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransaction;

    public GitLabGroupMemberSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            OrganizationMembershipRepository organizationMembershipRepository,
            UserRepository userRepository,
            GitLabWorkspaceLinkService workspaceLinkService,
            GitLabProperties gitLabProperties,
            @Nullable OrganizationMembershipListener organizationMembershipListener,
            TransactionTemplate transactionTemplate) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.userRepository = userRepository;
        this.workspaceLinkService = workspaceLinkService;
        this.gitLabProperties = gitLabProperties;
        this.organizationMembershipListener = organizationMembershipListener;
        this.transactionTemplate = transactionTemplate;
        // Isolated transaction for each user upsert — matches GitHubUserProcessor pattern.
        this.requiresNewTransaction =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Syncs all memberships for a GitLab group.
     * <p>
     * Paginates through the {@code GetGroupMembers} GraphQL query for the group's effective members
     * ({@link GitLabGroupMemberResponse#EFFECTIVE_RELATIONS}),
     * reading every page before anything is written. Only a complete listing, whose pages are whole and
     * error-free and whose entries all name a user and an access level, is applied: its users are upserted, then
     * each user's highest grant and the removal of stale memberships land in one short transaction, followed by
     * the organization-synced event. An incomplete listing writes nothing; a user that cannot be resolved, or
     * ownership lost before the role transaction, can leave already upserted users but no membership change. Only
     * the scope whose reading of the group is authoritative, per {@link GitLabWorkspaceLinkService#mayWriteGroup},
     * writes.
     * <p>
     * Circuit breaker permission and rate limit checks are performed per page,
     * matching the canonical pattern in {@code GitLabGroupSyncService.reconcileDirectProjects}.
     *
     * @param scopeId       the workspace/scope ID for authentication
     * @param groupFullPath the full path of the group (e.g., "org/team")
     * @param organization  the Organization entity for this group
     * @return the number of unique members synced, or -1 when the roster was not listed completely or this scope
     *     may not write it
     */
    public int syncGroupMemberships(Long scopeId, @Nullable String groupFullPath, @Nullable Organization organization) {
        if (organization == null || groupFullPath == null || groupFullPath.isBlank()) {
            log.warn(
                    "Skipped group membership sync: reason=missingArgs, scopeId={}, groupPath={}",
                    scopeId,
                    groupFullPath != null ? sanitizeForLog(groupFullPath) : "null");
            return -1;
        }

        String safeGroupPath = sanitizeForLog(groupFullPath);
        if (!workspaceLinkService.mayWriteGroup(scopeId, organization)) {
            log.warn(
                    "Skipped group membership sync: reason=groupNotThisScopes, scopeId={}, groupPath={}",
                    scopeId,
                    safeGroupPath);
            return -1;
        }
        Long providerId = Objects.requireNonNull(organization.getProvider().getId());

        // The whole roster is read before any membership is written: a later page that fails or cannot be read must
        // leave every stored role as it was, including one an earlier page lists at a lower level.
        Map<Long, ListedMember> listedByNativeId = new HashMap<>();
        String cursor = null;
        String previousCursor = null;
        int pageCount = 0;
        boolean syncCompletedNormally = false;
        boolean everyMemberIdentified = true;

        try {
            do {
                // Per-page circuit breaker + rate limit wait
                // (matches reconcileDirectProjects canonical pattern)
                graphQlClientProvider.acquirePermission();
                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn(
                            "Interrupted during rate limit wait: context=groupMemberSync, groupPath={}", safeGroupPath);
                    break;
                }

                int pageSize = adaptPageSize(DEFAULT_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId));
                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_GROUP_MEMBERS_DOCUMENT)
                        .variable("fullPath", groupFullPath)
                        .variable("relations", GitLabGroupMemberResponse.EFFECTIVE_RELATIONS)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "group members for " + safeGroupPath, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    break;
                }

                graphQlClientProvider.recordSuccess();

                // GitLab answers a field it could not resolve with an error beside the data it could, such as a
                // member whose access level is missing: that page does not say who holds which role.
                if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
                    log.warn("Stopped group membership sync: reason=fieldErrors, groupPath={}", safeGroupPath);
                    break;
                }
                if (!responseHandler.isWholePage(response, "group.groupMembers")) {
                    log.warn("Stopped group membership sync: reason=pageNotWhole, groupPath={}", safeGroupPath);
                    break;
                }

                List<GitLabGroupMemberResponse> members =
                        response.field("group.groupMembers.nodes").toEntityList(GitLabGroupMemberResponse.class);

                for (GitLabGroupMemberResponse member : members) {
                    // A listed member this sync cannot identify, or whose access level is missing, may be one already
                    // stored, so the listing no longer proves who is gone or what role they hold.
                    ListedMember listed = ListedMember.of(member);
                    if (listed == null) {
                        everyMemberIdentified = false;
                        continue;
                    }
                    listedByNativeId.merge(listed.nativeId(), listed, ListedMember::higher);
                }

                // Check pagination
                GitLabPageInfo pageInfo = Objects.requireNonNull(
                        response.field("group.groupMembers.pageInfo").toEntity(GitLabPageInfo.class));
                if (!pageInfo.hasNextPage()) {
                    syncCompletedNormally = true;
                    break;
                }

                cursor = pageInfo.endCursor();
                if (cursor == null) {
                    log.warn(
                            "Pagination cursor null despite hasNextPage=true: context=groupMemberSync, groupPath={}, page={}",
                            safeGroupPath,
                            pageCount);
                    break;
                }
                if (responseHandler.isPaginationLoop(
                        cursor, previousCursor, "group members for " + safeGroupPath, log)) {
                    break;
                }
                previousCursor = cursor;

                pageCount++;
                Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
            } while (pageCount < MAX_PAGINATION_PAGES);

            Map<Long, OrganizationMemberRole> syncedRoles = new HashMap<>();
            boolean complete = syncCompletedNormally && everyMemberIdentified;
            if (complete) {
                for (ListedMember listed : listedByNativeId.values()) {
                    Long userId = upsertUser(listed.user(), providerId);
                    if (userId == null) {
                        complete = false;
                        break;
                    }
                    syncedRoles.put(userId, mapAccessLevel(listed.accessLevel()));
                }
            }
            if (complete) {
                // One short transaction: the roster's roles and removals land together or not at all, and only while
                // this scope still owns the group on the instance it was read from.
                complete = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    if (!workspaceLinkService.mayWriteGroup(scopeId, organization)) {
                        return false;
                    }
                    syncedRoles.forEach((userId, role) ->
                            organizationMembershipRepository.upsertMembership(organization.getId(), userId, role));
                    removeStaleMemberships(organization, syncedRoles.keySet());
                    return true;
                }));
            }
            if (!complete) {
                log.warn(
                        "Skipped group membership changes: reason=incompleteSync, groupPath={}, pagesProcessed={}, pagesComplete={}, membersIdentified={}",
                        safeGroupPath,
                        pageCount + 1,
                        syncCompletedNormally,
                        everyMemberIdentified);
            }

            // Fire sync event only on complete sync — downstream reconciliation
            // should not run on partial data to avoid incorrect member removal.
            if (complete && organizationMembershipListener != null) {
                organizationMembershipListener.onOrganizationMembershipsSynced(
                        new OrganizationSyncedEvent(organization.getId(), organization.getLogin(), true));
            }

            log.info(
                    "Synced group memberships: scopeId={}, groupPath={}, memberCount={}, pages={}, complete={}",
                    scopeId,
                    safeGroupPath,
                    syncedRoles.size(),
                    pageCount + 1,
                    complete);

            return complete ? syncedRoles.size() : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during group membership sync: groupPath={}", safeGroupPath);
            return -1;
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error("Failed to sync group memberships: scopeId={}, groupPath={}", scopeId, safeGroupPath, e);
            return -1;
        }
    }

    /**
     * Upserts a User entity for the given GitLab member using native SQL.
     * <p>
     * Runs in a REQUIRES_NEW transaction following the concurrency-safe pattern
     * from {@code GitHubUserProcessor}: advisory lock → free login conflicts → upsert.
     * This prevents unique constraint violations when a GitLab user renames their
     * username and another user previously held that login.
     *
     * @param memberUser the GitLab member user data
     * @param providerId the GitLab provider's database ID
     * @return the user's database ID, or null if the GID is invalid
     */
    @Nullable
    Long upsertUser(GitLabMemberUser memberUser, Long providerId) {
        long nativeId;
        try {
            nativeId = extractNumericId(memberUser.id());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid GitLab user GID: gid={}, error={}", memberUser.id(), e.getMessage());
            return null;
        }

        String login = memberUser.username();
        String name = memberUser.name();
        String avatarUrl = memberUser.avatarUrl() != null ? memberUser.avatarUrl() : "";
        String htmlUrl = memberUser.webUrl() != null ? memberUser.webUrl() : "";

        requiresNewTransaction.executeWithoutResult(status -> {
            boolean locked = userRepository.tryAcquireLoginLock(login, providerId);
            if (locked) {
                userRepository.freeLoginConflicts(login, nativeId, providerId);
            } else {
                log.debug("Could not acquire advisory lock for login={}, proceeding with upsert", login);
            }
            userRepository.upsertUser(
                    nativeId,
                    providerId,
                    login,
                    name,
                    avatarUrl,
                    htmlUrl,
                    GitLabUserClassifier.classify(login).name(),
                    null,
                    null,
                    null);
        });

        // Load the persisted entity to get the database-assigned ID.
        // The REQUIRES_NEW transaction has committed, so this query sees the upserted row.
        return userRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .map(User::getId)
                .orElse(null);
    }

    /**
     * Maps a GitLab access level to a provider-agnostic organization member role.
     * <p>
     * OWNER(50) and MAINTAINER(40) → ADMIN; DEVELOPER(30), REPORTER(20),
     * PLANNER(15), GUEST(10), MINIMAL_ACCESS(5) → MEMBER.
     */
    static OrganizationMemberRole mapAccessLevel(@Nullable GitLabAccessLevel accessLevel) {
        if (accessLevel == null || accessLevel.integerValue() == null) {
            return OrganizationMemberRole.MEMBER;
        }
        if (accessLevel.integerValue() >= ADMIN_ACCESS_LEVEL_THRESHOLD) {
            return OrganizationMemberRole.ADMIN;
        }
        return OrganizationMemberRole.MEMBER;
    }

    /**
     * One member entry GitLab listed with a readable user id, username and access level.
     *
     * @param nativeId    the user's numeric GitLab id
     * @param user        the listed user
     * @param accessLevel the listed access level
     */
    private record ListedMember(long nativeId, GitLabMemberUser user, GitLabAccessLevel accessLevel) {

        /** The entry, or null when it does not say who the member is or what access they hold. */
        static @Nullable ListedMember of(@Nullable GitLabGroupMemberResponse member) {
            GitLabMemberUser user = member == null ? null : member.user();
            GitLabAccessLevel accessLevel = member == null ? null : member.accessLevel();
            if (user == null
                    || user.id() == null
                    || user.username() == null
                    || accessLevel == null
                    || accessLevel.integerValue() == null) {
                return null;
            }
            try {
                return new ListedMember(extractNumericId(user.id()), user, accessLevel);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** A user listed through more than one relation holds the highest grant. */
        ListedMember higher(ListedMember other) {
            return Objects.requireNonNull(other.accessLevel.integerValue())
                            > Objects.requireNonNull(accessLevel.integerValue())
                    ? other
                    : this;
        }
    }

    private void removeStaleMemberships(Organization organization, Set<Long> syncedUserIds) {
        List<Long> existingUserIds = organizationMembershipRepository.findUserIdsByOrganizationId(organization.getId());

        Set<Long> staleUserIds = new HashSet<>(existingUserIds);
        staleUserIds.removeAll(syncedUserIds);

        if (!staleUserIds.isEmpty()) {
            organizationMembershipRepository.deleteByOrganizationIdAndUserIdIn(organization.getId(), staleUserIds);
            log.info(
                    "Removed stale group memberships: orgId={}, groupPath={}, removedCount={}",
                    organization.getId(),
                    sanitizeForLog(organization.getLogin()),
                    staleUserIds.size());
        }
    }
}
