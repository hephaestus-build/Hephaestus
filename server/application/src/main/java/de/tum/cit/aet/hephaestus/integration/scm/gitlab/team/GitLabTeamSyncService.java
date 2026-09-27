package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.adaptPageSize;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractNumericId;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener.TeamsSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabAccessLevel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse.GitLabMemberUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronizes GitLab subgroups as teams via GraphQL API.
 * <p>
 * Intentionally NOT @Transactional at the orchestrator level — each phase
 * manages its own transactional boundaries via TransactionTemplate to avoid
 * holding a DB connection during network calls and throttle delays.
 * <p>
 * Uses TransactionTemplate instead of @Transactional because Spring's
 * proxy-based AOP doesn't intercept internal method calls within the same class.
 * <p>
 * Phases:
 * <ol>
 *   <li>A — Fetch the root group and its descendant groups and create Team entities</li>
 *   <li>B — Resolve parent references (two-pass)</li>
 *   <li>C — Set each team's members, in one transaction per team, from both of its sources: the subgroup's
 *       members and invited groups' members ({@link GitLabGroupMemberResponse#TEAM_RELATIONS}) and the WRITE/TRIAGE collaborators of
 *       projects directly in it on the same instance, such as students. A team whose listing is incomplete keeps
 *       its members.</li>
 *   <li>D — Sync team-repo permissions (repos whose org = subgroup fullPath)</li>
 *   <li>E — Cleanup stale teams (only if the whole graph was listed)</li>
 *   <li>G — Fire {@link TeamMembershipListener} with whether every team's members are current, so consumers
 *       (e.g. workspace) can reconcile against the team graph once it is, and only add members before that.
 *       Covers the gap where subgroup-only users (tutor maintainers) never appear in
 *       organization_membership and would otherwise miss workspace_membership.</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabTeamSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabTeamSyncService.class);
    private static final String GET_GROUP_DESCENDANTS_DOCUMENT = "GetGroupDescendants";
    private static final String GET_GROUP_DOCUMENT = "GetGroup";
    private static final String GET_GROUP_MEMBERS_DOCUMENT = "GetGroupMembers";
    private static final String GET_GROUPS_BY_IDS_DOCUMENT = "GetGroupsByIds";
    private static final String GET_GROUP_MEMBER_DOCUMENT = "GetGroupMember";
    private static final String GROUP_GLOBAL_ID_PREFIX = "gid://gitlab/Group/";
    private static final String USER_GLOBAL_ID_PREFIX = "gid://gitlab/User/";
    private static final int TEAM_PAGE_SIZE = 20;
    private static final int MEMBER_PAGE_SIZE = 100;

    /** Project collaborators who belong to the subgroup's team: developers and reporters, such as students. */
    private static final List<RepositoryCollaborator.Permission> TEAM_COLLABORATOR_PERMISSIONS =
            List.of(RepositoryCollaborator.Permission.WRITE, RepositoryCollaborator.Permission.TRIAGE);

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabTeamProcessor teamProcessor;
    private final GitLabUserService gitLabUserService;
    private final GitLabWorkspaceLinkService workspaceLinkService;
    private final GitLabProperties gitLabProperties;
    private final RepositoryCollaboratorRepository collaboratorRepository;
    private final TransactionTemplate transactionTemplate;
    private final @Nullable TeamMembershipListener teamMembershipListener;

    public GitLabTeamSyncService(
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            RepositoryRepository repositoryRepository,
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabTeamProcessor teamProcessor,
            GitLabUserService gitLabUserService,
            GitLabWorkspaceLinkService workspaceLinkService,
            GitLabProperties gitLabProperties,
            RepositoryCollaboratorRepository collaboratorRepository,
            TransactionTemplate transactionTemplate,
            @Nullable TeamMembershipListener teamMembershipListener) {
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.teamProcessor = teamProcessor;
        this.gitLabUserService = gitLabUserService;
        this.workspaceLinkService = workspaceLinkService;
        this.gitLabProperties = gitLabProperties;
        this.collaboratorRepository = collaboratorRepository;
        this.transactionTemplate = transactionTemplate;
        this.teamMembershipListener = teamMembershipListener;
    }

    /**
     * Synchronizes all descendant subgroups of a GitLab group as teams.
     * <p>
     * NOT @Transactional — orchestrator delegates to TransactionTemplate-wrapped helpers
     * so DB connections are not held during API calls or throttle delays.
     *
     * @param scopeId       the workspace/scope ID
     * @param groupFullPath the root group full path (e.g., "ase/introcourse")
     * @return the teams synced, and whether GitLab listed the whole team graph and every team's members
     */
    public Result syncTeamsForGroup(Long scopeId, String groupFullPath) {
        if (groupFullPath == null || groupFullPath.isBlank()) {
            log.warn("Skipped team sync: reason=missingGroupPath, scopeId={}", scopeId);
            return Result.INCOMPLETE;
        }

        IdentityProvider provider =
                workspaceLinkService.groupWriteProvider(scopeId, groupFullPath).orElse(null);
        if (provider == null) {
            log.warn("Skipped team sync: reason=groupNotThisScopes, scopeId={}, groupPath={}", scopeId, groupFullPath);
            return Result.INCOMPLETE;
        }
        Long providerId = Objects.requireNonNull(provider.getId());

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        // Phase A: Fetch the root group itself, then its descendants. The root
        // team anchors the parent chain so staff members (inherited from the
        // root group down to every subgroup in GitLab's permission model) live
        // on the root, not duplicated into every child.
        Map<Long, Team> syncedTeamsByNativeId = new HashMap<>();
        Map<Long, Long> parentNativeIdByChildNativeId = new HashMap<>();
        Map<Long, String> teamFullPathsByNativeId = new HashMap<>();
        Set<Long> syncedNativeIds = new HashSet<>();

        Team rootTeam = fetchAndProcessRootGroup(
                client,
                scopeId,
                groupFullPath,
                provider,
                syncedTeamsByNativeId,
                teamFullPathsByNativeId,
                syncedNativeIds);

        boolean descendantsComplete = fetchAndProcessDescendantGroups(
                client,
                scopeId,
                groupFullPath,
                provider,
                syncedTeamsByNativeId,
                parentNativeIdByChildNativeId,
                teamFullPathsByNativeId,
                syncedNativeIds);
        // Only a whole graph proves a team or a parent link is gone: an unreadable root or a partial
        // descendant listing must not delete teams, clear parents, or reconcile memberships.
        boolean graphComplete = rootTeam != null && descendantsComplete;

        int totalSynced = syncedTeamsByNativeId.size();
        log.info(
                "Phase A complete: groupPath={}, teamsFound={} (root={})",
                groupFullPath,
                totalSynced,
                rootTeam != null);

        if (totalSynced == 0) {
            log.info("No groups found for team sync: groupPath={}", groupFullPath);
            return Result.INCOMPLETE;
        }

        // Phase B: Resolve parent references
        resolveParentReferences(syncedTeamsByNativeId, parentNativeIdByChildNativeId, groupFullPath, graphComplete);

        // Phase C: Sync members per team
        int totalMembers = 0;
        boolean membersComplete = true;
        for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
            String fullPath = teamFullPathsByNativeId.get(entry.getKey());
            if (fullPath != null) {
                try {
                    MemberSync members = syncTeamMembers(
                            client, scopeId, groupFullPath, entry.getValue().getId(), fullPath, providerId);
                    totalMembers += members.synced();
                    membersComplete &= members.complete();
                } catch (Exception e) {
                    membersComplete = false;
                    log.warn(
                            "Failed to sync members for team: teamSlug={}, error={}",
                            entry.getValue().getSlug(),
                            e.getMessage());
                }
            } else {
                membersComplete = false;
            }
        }
        log.info("Phase C complete: groupPath={}, totalMembers={}", groupFullPath, totalMembers);

        // Phase D: Sync team-repo permissions
        int totalPermissions = 0;
        for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
            String fullPath = teamFullPathsByNativeId.get(entry.getKey());
            if (fullPath != null) {
                try {
                    int perms = syncTeamRepoPermissions(entry.getValue().getId(), fullPath, providerId);
                    totalPermissions += perms;
                } catch (Exception e) {
                    log.warn(
                            "Failed to sync repo permissions for team: teamSlug={}, error={}",
                            entry.getValue().getSlug(),
                            e.getMessage());
                }
            }
        }
        log.info("Phase D complete: groupPath={}, totalPermissions={}", groupFullPath, totalPermissions);

        // Phase E: Cleanup stale teams (only if the whole graph was listed)
        if (graphComplete) {
            removeDeletedTeams(groupFullPath, syncedNativeIds, providerId);
        }

        // Phase G: Tell downstream consumers (e.g. workspace memberships) about the team graph. Only fired when the
        // whole graph was listed, and it says whether every team's members are current: a team whose listing was
        // incomplete kept old rows, which justify keeping someone but not removing anyone else.
        boolean complete = graphComplete && membersComplete;
        if (graphComplete && teamMembershipListener != null) {
            try {
                teamMembershipListener.onTeamMembershipsSynced(
                        new TeamsSyncedEvent(scopeId, groupFullPath, providerId, complete));
            } catch (Exception e) {
                log.warn("Team membership listener failed for groupPath={}: error={}", groupFullPath, e.getMessage());
            }
        }

        log.info(
                "GitLab team sync complete: groupPath={}, teams={}, members={}, permissions={}, complete={}",
                groupFullPath,
                totalSynced,
                totalMembers,
                totalPermissions,
                complete);

        return new Result(totalSynced, complete);
    }

    /**
     * What one team sync did.
     *
     * @param teams    the teams GitLab listed and this sync recorded
     * @param complete whether GitLab listed the whole team graph and every team's members; only a complete sync
     *                 removes teams, parent links and team memberships everywhere
     */
    public record Result(int teams, boolean complete) {
        static final Result INCOMPLETE = new Result(0, false);
    }

    /** One team's member listing: the members recorded, and whether both of its sources were read in full. */
    record MemberSync(int synced, boolean complete) {}

    // Phase A.0: Fetch Root Group

    /**
     * Fetches the root group metadata and upserts it as a Team so subgroups have
     * a real parent row to point at. A failure here is non-fatal: the root team
     * is a parity-with-GitHub enhancement, not a prerequisite for descendant
     * sync. Returns the persisted root Team, or {@code null} if unavailable.
     */
    @Nullable
    private Team fetchAndProcessRootGroup(
            HttpGraphQlClient client,
            Long scopeId,
            String groupFullPath,
            IdentityProvider provider,
            Map<Long, Team> syncedTeamsByNativeId,
            Map<Long, String> teamFullPathsByNativeId,
            Set<Long> syncedNativeIds) {
        try {
            graphQlClientProvider.acquirePermission();
            graphQlClientProvider.waitIfRateLimitLow(scopeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted fetching root group: groupPath={}", groupFullPath);
            return null;
        }

        ClientGraphQlResponse response;
        try {
            response = client.documentName(GET_GROUP_DOCUMENT)
                    .variable("fullPath", groupFullPath)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());
        } catch (Exception e) {
            log.warn("Failed to fetch root group: groupPath={}, error={}", groupFullPath, e.getMessage());
            return null;
        }

        var handleResult = responseHandler.handle(response, "root group " + groupFullPath, log);
        if (handleResult.action() != GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE) {
            return null;
        }
        graphQlClientProvider.recordSuccess();
        if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
            log.warn("Skipped root group: reason=fieldErrors, groupPath={}", groupFullPath);
            return null;
        }

        GitLabGroupResponse rootPayload =
                Objects.requireNonNull(response).field("group").toEntity(GitLabGroupResponse.class);
        if (rootPayload == null) {
            return null;
        }

        Team rootTeam = teamProcessor.processRoot(rootPayload, groupFullPath, provider);
        if (rootTeam == null) {
            return null;
        }

        long nativeId = rootTeam.getNativeId();
        syncedTeamsByNativeId.put(nativeId, rootTeam);
        teamFullPathsByNativeId.put(nativeId, groupFullPath);
        syncedNativeIds.add(nativeId);
        return rootTeam;
    }

    // Phase A: Fetch Descendant Groups

    private boolean fetchAndProcessDescendantGroups(
            HttpGraphQlClient client,
            Long scopeId,
            String groupFullPath,
            IdentityProvider provider,
            Map<Long, Team> syncedTeamsByNativeId,
            Map<Long, Long> parentNativeIdByChildNativeId,
            Map<Long, String> teamFullPathsByNativeId,
            Set<Long> syncedNativeIds) {
        String cursor = null;
        String previousCursor = null;
        int pageCount = 0;

        while (true) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn("Reached maximum pagination limit for descendant groups: groupPath={}", groupFullPath);
                return false;
            }

            try {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during rate limit wait: groupPath={}", groupFullPath);
                return false;
            }

            int pageSize = adaptPageSize(TEAM_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId));

            ClientGraphQlResponse response = client.documentName(GET_GROUP_DESCENDANTS_DOCUMENT)
                    .variable("fullPath", groupFullPath)
                    .variable("first", pageSize)
                    .variable("after", cursor)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());

            var handleResult = responseHandler.handle(response, "descendant groups for " + groupFullPath, log);
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                continue;
            }
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                graphQlClientProvider.recordFailure(
                        new GitLabSyncException("Invalid GraphQL response for descendant groups"));
                return false;
            }
            graphQlClientProvider.recordSuccess();

            // A subgroup whose parent field errored would read as a top-level group and lose its parent link.
            if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
                log.warn("Stopped descendant group sync: reason=fieldErrors, groupPath={}", groupFullPath);
                return false;
            }
            if (!responseHandler.isWholePage(response, "group.descendantGroups")) {
                log.warn("Stopped descendant group sync: reason=pageNotWhole, groupPath={}", groupFullPath);
                return false;
            }

            // Parse nodes
            List<GitLabDescendantGroupResponse> groups =
                    response.field("group.descendantGroups.nodes").toEntityList(GitLabDescendantGroupResponse.class);
            for (GitLabDescendantGroupResponse group : groups) {
                // A listed subgroup this sync cannot read, or whose parent it cannot identify, is still on
                // GitLab: dropping it would read as a deleted team or a top-level one.
                Long parentNativeId = parentNativeId(group);
                Team team = parentNativeId == null ? null : teamProcessor.process(group, groupFullPath, provider);
                if (team == null) {
                    log.warn(
                            "Stopped descendant group sync: reason=unreadableSubgroup, groupPath={}, subgroup={}",
                            groupFullPath,
                            group == null ? null : group.fullPath());
                    return false;
                }
                // Track parent for resolution in Phase B. The root group is synced as a Team too,
                // so first-level subgroups legitimately reference it as their parent.
                long nativeId = team.getNativeId();
                syncedTeamsByNativeId.put(nativeId, team);
                syncedNativeIds.add(nativeId);
                teamFullPathsByNativeId.put(nativeId, group.fullPath());
                parentNativeIdByChildNativeId.put(nativeId, parentNativeId);
            }

            // Parse page info
            GitLabPageInfo pageInfo = Objects.requireNonNull(
                    response.field("group.descendantGroups.pageInfo").toEntity(GitLabPageInfo.class));
            if (!pageInfo.hasNextPage()) {
                return true;
            }

            cursor = pageInfo.endCursor();
            if (cursor == null) {
                log.warn(
                        "Pagination cursor is null despite hasNextPage=true: groupPath={}, page={}",
                        groupFullPath,
                        pageCount);
                return false;
            }
            if (responseHandler.isPaginationLoop(
                    cursor, previousCursor, "descendant groups for " + groupFullPath, log)) {
                return false;
            }
            previousCursor = cursor;

            throttle();
        }
    }

    // Phase B: Resolve Parent References

    /**
     * Points each synced team at its parent's row. A parent this sync did not record is cleared only when
     * {@code graphComplete}; after a partial listing it may simply be on a page that was not read, so the
     * stored link stays.
     */
    void resolveParentReferences(
            Map<Long, Team> syncedTeamsByNativeId,
            Map<Long, Long> parentNativeIdByChildNativeId,
            String groupFullPath,
            boolean graphComplete) {
        transactionTemplate.executeWithoutResult(status -> {
            Map<Long, Team> managedTeams = new HashMap<>();
            for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
                teamRepository.findById(entry.getValue().getId()).ifPresent(t -> managedTeams.put(entry.getKey(), t));
            }

            Set<Team> changed = new HashSet<>();

            for (Map.Entry<Long, Team> entry : managedTeams.entrySet()) {
                Team child = entry.getValue();
                Long parentNativeId = parentNativeIdByChildNativeId.get(entry.getKey());

                Long correctParentId = null;
                if (parentNativeId != null) {
                    Team parent = managedTeams.get(parentNativeId);
                    if (parent != null) {
                        correctParentId = parent.getId();
                    } else {
                        log.warn(
                                "Parent team not found in sync: child={}, parentNativeId={}, graphComplete={}",
                                child.getSlug(),
                                parentNativeId,
                                graphComplete);
                        if (!graphComplete) {
                            continue;
                        }
                    }
                }

                if (!Objects.equals(correctParentId, child.getParentId())) {
                    child.setParentId(correctParentId);
                    changed.add(child);
                }
            }

            if (!changed.isEmpty()) {
                teamRepository.saveAll(changed);
                log.info("Resolved parent references: groupPath={}, updated={}", groupFullPath, changed.size());
            }
        });
    }

    // Phase C: Sync Members

    MemberSync syncTeamMembers(
            HttpGraphQlClient client,
            Long scopeId,
            String rootGroupFullPath,
            Long teamId,
            String groupFullPath,
            Long providerId) {
        // Phase C.1: Fetch all members via GraphQL OUTSIDE a transaction
        // to avoid holding a DB connection during network I/O and throttle delays.
        List<GitLabGroupMemberResponse> allMembers = new ArrayList<>();
        boolean memberSyncComplete = fetchAllGroupMembers(client, scopeId, groupFullPath, allMembers);

        // Phase C.2: Read the whole listing before writing anything. A failed page, or an entry without a readable
        // user or access level, leaves every stored membership and role of this team as it was.
        if (!memberSyncComplete) {
            log.warn("Skipped team membership changes: reason=incompleteListing, groupPath={}", groupFullPath);
            return new MemberSync(0, false);
        }
        Map<Long, ListedMember> listedByNativeId = new HashMap<>();
        for (GitLabGroupMemberResponse member : allMembers) {
            ListedMember listed = ListedMember.of(member);
            if (listed == null) {
                log.warn("Skipped team membership changes: reason=unreadableMember, groupPath={}", groupFullPath);
                return new MemberSync(0, false);
            }
            if (listed.role() != null) {
                listedByNativeId.merge(listed.nativeId(), listed, ListedMember::higher);
            }
        }

        // Phase C.3: Apply the membership diff in a short transaction, only while this scope still owns the group
        // on the instance it was read from.
        MemberSync result = transactionTemplate.execute(status -> {
            if (workspaceLinkService
                    .groupWriteProvider(scopeId, rootGroupFullPath)
                    .map(IdentityProvider::getId)
                    .filter(providerId::equals)
                    .isEmpty()) {
                return new MemberSync(0, false);
            }
            Team team = teamRepository
                    .findById(teamId)
                    .orElseThrow(() -> new IllegalStateException("Team not found: teamId=" + teamId));

            Map<Long, TeamMembership> existingMemberships = team.getMemberships().stream()
                    .collect(Collectors.toMap(tm -> tm.getUser().getId(), tm -> tm));

            Set<Long> syncedMemberIds = new HashSet<>();
            for (ListedMember listed : listedByNativeId.values()) {
                var userRef = listed.user();
                User user = gitLabUserService.findOrCreateUser(
                        GitLabUserLookup.of(
                                userRef.id(),
                                userRef.username(),
                                userRef.name(),
                                userRef.avatarUrl(),
                                userRef.webUrl()),
                        providerId);
                if (user == null) {
                    status.setRollbackOnly();
                    log.warn("Skipped team membership changes: reason=userNotResolved, teamSlug={}", team.getSlug());
                    return new MemberSync(0, false);
                }
                syncedMemberIds.add(user.getId());
                applyMembership(team, user, Objects.requireNonNull(listed.role()), existingMemberships);
            }
            // A project collaborator the subgroup does not list directly, such as a student, is a member too. Read in
            // this transaction, so a failed read changes nothing rather than dropping them as stale. Collaborator rows
            // change only from the owning scope's complete project listing, so a failed collaborator sync leaves its
            // last complete answer in place rather than an empty one.
            for (RepositoryCollaborator collaborator : collaboratorRepository.findByOrgLoginAndPermissions(
                    groupFullPath, providerId, TEAM_COLLABORATOR_PERMISSIONS)) {
                if (syncedMemberIds.add(collaborator.getUser().getId())) {
                    applyMembership(team, collaborator.getUser(), TeamMembership.Role.MEMBER, existingMemberships);
                }
            }
            removeStaleTeamMemberships(team, syncedMemberIds);
            return new MemberSync(syncedMemberIds.size(), true);
        });

        return result != null ? result : new MemberSync(0, false);
    }

    /**
     * Fetches all group members via paginated GraphQL calls.
     * Runs OUTSIDE a transaction to avoid holding a DB connection during network I/O.
     *
     * @return true if pagination completed normally, false if interrupted or failed
     */
    private boolean fetchAllGroupMembers(
            HttpGraphQlClient client, Long scopeId, String groupFullPath, List<GitLabGroupMemberResponse> allMembers) {
        String cursor = null;
        String previousCursor = null;
        int pageCount = 0;

        while (true) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn("Reached max pagination for members: groupPath={}", groupFullPath);
                return false;
            }

            try {
                graphQlClientProvider.acquirePermission();
                graphQlClientProvider.waitIfRateLimitLow(scopeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            int pageSize = adaptPageSize(MEMBER_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId));

            ClientGraphQlResponse response = client.documentName(GET_GROUP_MEMBERS_DOCUMENT)
                    .variable("fullPath", groupFullPath)
                    .variable("relations", GitLabGroupMemberResponse.TEAM_RELATIONS)
                    .variable("first", pageSize)
                    .variable("after", cursor)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());

            var handleResult = responseHandler.handle(response, "team members for " + groupFullPath, log);
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                continue;
            }
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                graphQlClientProvider.recordFailure(
                        new GitLabSyncException("Invalid GraphQL response for group members"));
                return false;
            }
            graphQlClientProvider.recordSuccess();
            // A page with field errors, such as a member whose access level is missing, does not say who holds
            // which role; none of it is applied.
            if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
                log.warn("Stopped team member sync: reason=fieldErrors, groupPath={}", groupFullPath);
                return false;
            }
            if (!responseHandler.isWholePage(response, "group.groupMembers")) {
                log.warn("Stopped team member sync: reason=pageNotWhole, groupPath={}", groupFullPath);
                return false;
            }

            allMembers.addAll(response.field("group.groupMembers.nodes").toEntityList(GitLabGroupMemberResponse.class));

            GitLabPageInfo memberPageInfo = Objects.requireNonNull(
                    response.field("group.groupMembers.pageInfo").toEntity(GitLabPageInfo.class));
            if (!memberPageInfo.hasNextPage()) {
                return true;
            }

            cursor = memberPageInfo.endCursor();
            if (cursor == null) {
                log.warn(
                        "Member pagination cursor is null despite hasNextPage=true: groupPath={}, page={}",
                        groupFullPath,
                        pageCount);
                return false;
            }
            if (responseHandler.isPaginationLoop(cursor, previousCursor, "team members for " + groupFullPath, log)) {
                return false;
            }
            previousCursor = cursor;

            throttle();
        }
    }

    private void applyMembership(
            Team team, User user, TeamMembership.Role role, Map<Long, TeamMembership> existingMemberships) {
        TeamMembership existing = existingMemberships.get(user.getId());
        if (existing != null) {
            if (existing.getRole() != role) {
                existing.setRole(role);
                teamMembershipRepository.save(existing);
            }
        } else {
            teamMembershipRepository.save(new TeamMembership(team, user, role));
        }
    }

    /**
     * One member entry GitLab listed with a readable user id, username and access level.
     *
     * @param nativeId the user's numeric GitLab id
     * @param user     the listed user
     * @param role     the team role the access level grants; null for no or minimal access, which is no membership
     */
    private record ListedMember(long nativeId, GitLabMemberUser user, TeamMembership.@Nullable Role role) {

        /** The entry, or null when it does not say who the member is or what access they hold. */
        static @Nullable ListedMember of(@Nullable GitLabGroupMemberResponse member) {
            GitLabMemberUser user = member == null ? null : member.user();
            GitLabAccessLevel accessLevel = member == null ? null : member.accessLevel();
            String level = accessLevel == null ? null : accessLevel.stringValue();
            if (user == null || user.id() == null || user.username() == null || level == null) {
                return null;
            }
            try {
                return new ListedMember(extractNumericId(user.id()), user, mapAccessLevel(level));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** A user listed twice holds the higher role. */
        ListedMember higher(ListedMember other) {
            return other.role == TeamMembership.Role.MAINTAINER ? other : this;
        }
    }

    /** The parent group's numeric id of a listed subgroup, or null when it is missing or not a group GID. */
    private static @Nullable Long parentNativeId(@Nullable GitLabDescendantGroupResponse group) {
        GitLabDescendantGroupResponse.ParentRef parent = group == null ? null : group.parent();
        if (parent == null || parent.id() == null || parent.fullPath() == null) {
            return null;
        }
        try {
            return extractNumericId(parent.id());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Maps GitLab access level string to TeamMembership.Role.
     * <p>
     * Returns null for access levels that should be skipped.
     */
    public static TeamMembership.@Nullable Role mapAccessLevel(@Nullable String accessLevel) {
        if (accessLevel == null) {
            return TeamMembership.Role.MEMBER;
        }
        return switch (accessLevel.toUpperCase()) {
            case "NO_ACCESS", "MINIMAL_ACCESS" -> null;
            case "GUEST", "PLANNER", "REPORTER", "DEVELOPER" -> TeamMembership.Role.MEMBER;
            case "MAINTAINER", "OWNER", "ADMIN" -> TeamMembership.Role.MAINTAINER;
            default -> {
                log.warn("Unknown GitLab access level '{}', using MEMBER as default", accessLevel);
                yield TeamMembership.Role.MEMBER;
            }
        };
    }

    private void removeStaleTeamMemberships(Team team, Set<Long> syncedMemberIds) {
        int removed = 0;
        for (TeamMembership membership : new HashSet<>(team.getMemberships())) {
            if (!syncedMemberIds.contains(membership.getUser().getId())) {
                team.removeMembership(membership);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Removed stale memberships: teamSlug={}, count={}", team.getSlug(), removed);
        }
    }

    // Phase D: Sync Team-Repo Permissions

    int syncTeamRepoPermissions(Long teamId, String groupFullPath, Long providerId) {
        Integer result = transactionTemplate.execute(status -> {
            Team team = teamRepository
                    .findById(teamId)
                    .orElseThrow(() -> new IllegalStateException("Team not found: teamId=" + teamId));

            List<Repository> repos =
                    repositoryRepository.findAllByOrganization_LoginIgnoreCaseAndProviderId(groupFullPath, providerId);

            if (repos.isEmpty()) {
                return 0;
            }

            Set<Long> freshRepoIds = repos.stream().map(Repository::getId).collect(Collectors.toSet());

            // Remove stale permissions (repos no longer in this group)
            team.getRepoPermissions()
                    .removeIf(p -> !freshRepoIds.contains(p.getRepository().getId()));

            // Add or update permissions
            Set<Long> existingRepoIds = team.getRepoPermissions().stream()
                    .map(p -> p.getRepository().getId())
                    .collect(Collectors.toSet());

            for (Repository repo : repos) {
                if (!existingRepoIds.contains(repo.getId())) {
                    team.addRepoPermission(
                            new TeamRepositoryPermission(team, repo, TeamRepositoryPermission.PermissionLevel.WRITE));
                }
            }

            teamRepository.save(team);

            log.debug(
                    "Synced repo permissions: teamSlug={}, repos={}",
                    team.getSlug(),
                    team.getRepoPermissions().size());
            return team.getRepoPermissions().size();
        });

        return result != null ? result : 0;
    }

    // Phase E: Cleanup

    private void removeDeletedTeams(String groupFullPath, Set<Long> syncedNativeIds, Long providerId) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Team> existingTeams = teamRepository.findAllByOrganizationIgnoreCase(groupFullPath);
            int removed = 0;

            for (Team team : existingTeams) {
                // Only delete teams from the same provider
                if (team.getProvider() != null
                        && Objects.requireNonNull(team.getProvider().getId()).equals(providerId)
                        && !syncedNativeIds.contains(team.getNativeId())) {
                    teamProcessor.delete(team.getNativeId(), providerId);
                    removed++;
                }
            }

            if (removed > 0) {
                log.info("Removed stale teams: groupPath={}, count={}", groupFullPath, removed);
            }
        });
    }

    // Helpers

    private void throttle() {
        try {
            Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    /**
     * The group GitLab reports under {@code groupNativeId} to the workspace's own credential, whatever path it has now;
     * empty when GitLab reports none. Only reads; a response GitLab could not give throws, so a caller can retry.
     */
    public Optional<GitLabDescendantGroupResponse> fetchGroup(Long scopeId, long groupNativeId) {
        String globalId = GROUP_GLOBAL_ID_PREFIX + groupNativeId;
        ClientGraphQlResponse response =
                query(scopeId, GET_GROUPS_BY_IDS_DOCUMENT, Map.of("ids", List.of(globalId)), "group " + groupNativeId);
        if (response.field("groups.nodes").getValue() == null) {
            throw new GitLabSyncException("GitLab did not list group " + groupNativeId);
        }
        List<GitLabDescendantGroupResponse> groups =
                response.field("groups.nodes").toEntityList(GitLabDescendantGroupResponse.class);
        return groups.stream().filter(group -> globalId.equals(group.id())).findFirst();
    }

    /**
     * What GitLab reports about {@code userNativeId}'s membership of the group at {@code groupFullPath}, which must still
     * be the group {@code groupNativeId}: one entry per relation GitLab counts, read as the connected group's roster
     * ({@code effective}) or a team's, none when the user is not a member. Empty
     * when GitLab reports no such group, which proves nothing about the membership. Only reads; a response GitLab could
     * not give throws, so a caller can retry.
     */
    public Optional<List<GitLabGroupMemberResponse>> fetchMembership(
            Long scopeId, String groupFullPath, long groupNativeId, long userNativeId, boolean effective) {
        String userGlobalId = USER_GLOBAL_ID_PREFIX + userNativeId;
        ClientGraphQlResponse response = query(
                scopeId,
                GET_GROUP_MEMBER_DOCUMENT,
                Map.of(
                        "fullPath",
                        groupFullPath,
                        "userIds",
                        List.of(userGlobalId),
                        "relations",
                        effective
                                ? GitLabGroupMemberResponse.EFFECTIVE_RELATIONS
                                : GitLabGroupMemberResponse.TEAM_RELATIONS),
                "membership in group " + groupNativeId);
        String groupId = response.field("group.id").getValue();
        if (!(GROUP_GLOBAL_ID_PREFIX + groupNativeId).equals(groupId)) {
            return Optional.empty();
        }
        if (response.field("group.groupMembers.nodes").getValue() == null) {
            throw new GitLabSyncException("GitLab did not list members for " + groupFullPath);
        }
        List<GitLabGroupMemberResponse> members =
                response.field("group.groupMembers.nodes").toEntityList(GitLabGroupMemberResponse.class);
        // Only an empty list proves no membership; an entry that cannot be read may be this user's.
        if (!members.stream().allMatch(GitLabTeamSyncService::isReadable)) {
            throw new GitLabSyncException("GitLab listed an unreadable member for " + groupFullPath);
        }
        return Optional.of(members.stream()
                .filter(member -> userGlobalId.equals(
                        Objects.requireNonNull(member.user()).id()))
                .toList());
    }

    private static boolean isReadable(GitLabGroupMemberResponse member) {
        GitLabGroupMemberResponse.GitLabMemberUser user = member.user();
        GitLabGroupMemberResponse.GitLabAccessLevel level = member.accessLevel();
        return user != null
                && user.id() != null
                && level != null
                && level.integerValue() != null
                && level.stringValue() != null;
    }

    private ClientGraphQlResponse query(Long scopeId, String document, Map<String, Object> variables, String context) {
        graphQlClientProvider.acquirePermission();
        var request = graphQlClientProvider.forScope(scopeId).documentName(document);
        variables.forEach(request::variable);
        ClientGraphQlResponse response = request.execute().block(gitLabProperties.graphqlTimeout());
        // A partial answer is no answer: an errored field would otherwise read as an absent group or member.
        if (responseHandler.handle(response, context, log).action()
                        != GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE
                || !Objects.requireNonNull(response).getErrors().isEmpty()) {
            GitLabSyncException failure = new GitLabSyncException("GitLab did not answer for " + context);
            graphQlClientProvider.recordFailure(failure);
            throw failure;
        }
        graphQlClientProvider.recordSuccess();
        return Objects.requireNonNull(response);
    }
}
