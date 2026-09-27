package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.adaptPageSize;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.extractNumericId;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
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
 *   <li>A — Fetch descendant groups and create Team entities</li>
 *   <li>B — Resolve parent references (two-pass)</li>
 *   <li>C — Sync direct + inherited members per team (inherited members cover TUM/LRZ course groups where students are enrolled at the parent group level)</li>
 *   <li>D — Sync team-repo permissions (repos whose org = subgroup fullPath)</li>
 *   <li>E — Cleanup stale teams (only if sync completed fully)</li>
 *   <li>F — Add project collaborators as team members (students in repos under subgroup)</li>
 *   <li>G — Fire {@link TeamMembershipListener} so consumers (e.g. workspace) can
 *       reconcile downstream state with the fully-synced team graph. Covers the
 *       gap where subgroup-only users (tutor maintainers) never appear in
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

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabTeamProcessor teamProcessor;
    private final GitLabUserService gitLabUserService;
    private final IdentityProviderRepository gitProviderRepository;
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
            IdentityProviderRepository gitProviderRepository,
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
        this.gitProviderRepository = gitProviderRepository;
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
     * @return number of teams synced
     */
    public int syncTeamsForGroup(Long scopeId, String groupFullPath) {
        if (groupFullPath == null || groupFullPath.isBlank()) {
            log.warn("Skipped team sync: reason=missingGroupPath, scopeId={}", scopeId);
            return 0;
        }

        IdentityProvider provider = resolveProvider();
        if (provider == null) {
            log.warn("Skipped team sync: reason=providerNotResolved, scopeId={}", scopeId);
            return 0;
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

        boolean syncCompletedNormally = fetchAndProcessDescendantGroups(
                client,
                scopeId,
                groupFullPath,
                provider,
                syncedTeamsByNativeId,
                parentNativeIdByChildNativeId,
                teamFullPathsByNativeId,
                syncedNativeIds);

        int totalSynced = syncedTeamsByNativeId.size();
        log.info(
                "Phase A complete: groupPath={}, teamsFound={} (root={})",
                groupFullPath,
                totalSynced,
                rootTeam != null);

        if (totalSynced == 0) {
            log.info("No groups found for team sync: groupPath={}", groupFullPath);
            return 0;
        }

        // Phase B: Resolve parent references
        resolveParentReferences(syncedTeamsByNativeId, parentNativeIdByChildNativeId, groupFullPath);

        // Phase C: Sync members per team
        int totalMembers = 0;
        for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
            String fullPath = teamFullPathsByNativeId.get(entry.getKey());
            if (fullPath != null) {
                try {
                    int members =
                            syncTeamMembers(client, scopeId, entry.getValue().getId(), fullPath, providerId);
                    totalMembers += members;
                } catch (Exception e) {
                    log.warn(
                            "Failed to sync members for team: teamSlug={}, error={}",
                            entry.getValue().getSlug(),
                            e.getMessage());
                }
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

        // Phase F: Add project collaborators as team members
        // Students who are direct project members (WRITE/TRIAGE) under a subgroup
        // should appear as team members of that subgroup.
        int totalCollaboratorMembers = 0;
        for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
            String fullPath = teamFullPathsByNativeId.get(entry.getKey());
            if (fullPath != null) {
                try {
                    int added = addProjectCollaboratorsAsTeamMembers(
                            entry.getValue().getId(), fullPath);
                    totalCollaboratorMembers += added;
                } catch (Exception e) {
                    log.warn(
                            "Failed to sync collaborator-to-team for: teamSlug={}, error={}",
                            entry.getValue().getSlug(),
                            e.getMessage());
                }
            }
        }
        log.info("Phase F complete: groupPath={}, collaboratorMembers={}", groupFullPath, totalCollaboratorMembers);

        // Phase E: Cleanup stale teams (only if sync completed normally)
        if (syncCompletedNormally) {
            removeDeletedTeams(groupFullPath, syncedNativeIds, providerId);
        }

        // Phase G: Reconcile downstream state (e.g., workspace memberships) from the
        // fully-synced team graph. Only fired when the sync completed end-to-end —
        // running reconciliation on partial data would miss legitimate members.
        if (syncCompletedNormally && teamMembershipListener != null) {
            try {
                teamMembershipListener.onTeamMembershipsSynced(new TeamsSyncedEvent(scopeId, groupFullPath));
            } catch (Exception e) {
                log.warn("Team membership listener failed for groupPath={}: error={}", groupFullPath, e.getMessage());
            }
        }

        log.info(
                "GitLab team sync complete: groupPath={}, teams={}, directMembers={}, collaboratorMembers={}, permissions={}",
                groupFullPath,
                totalSynced,
                totalMembers,
                totalCollaboratorMembers,
                totalPermissions);

        return totalSynced;
    }

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

            // Parse nodes
            List<GitLabDescendantGroupResponse> groups = Objects.requireNonNull(response)
                    .field("group.descendantGroups.nodes")
                    .toEntityList(GitLabDescendantGroupResponse.class);

            if (groups != null) {
                for (GitLabDescendantGroupResponse group : groups) {
                    Team team = teamProcessor.process(group, groupFullPath, provider);
                    if (team != null) {
                        long nativeId = team.getNativeId();
                        syncedTeamsByNativeId.put(nativeId, team);
                        syncedNativeIds.add(nativeId);
                        teamFullPathsByNativeId.put(nativeId, group.fullPath());

                        // Track parent for resolution in Phase B. The root group is
                        // synced as a Team too, so first-level subgroups legitimately
                        // reference it as their parent — no longer skipped.
                        if (group.parent() != null && group.parent().fullPath() != null) {
                            try {
                                long parentNativeId =
                                        extractNumericId(group.parent().id());
                                parentNativeIdByChildNativeId.put(nativeId, parentNativeId);
                            } catch (IllegalArgumentException e) {
                                log.warn(
                                        "Invalid parent GID: child={}, parentGid={}",
                                        group.fullPath(),
                                        group.parent().id());
                            }
                        }
                    }
                }
            }

            // Parse page info
            GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                    .field("group.descendantGroups.pageInfo")
                    .toEntity(GitLabPageInfo.class);

            if (pageInfo == null || !pageInfo.hasNextPage()) {
                break;
            }

            cursor = pageInfo.endCursor();
            if (cursor == null) {
                log.warn(
                        "Pagination cursor is null despite hasNextPage=true: groupPath={}, page={}",
                        groupFullPath,
                        pageCount);
                break;
            }
            if (responseHandler.isPaginationLoop(
                    cursor, previousCursor, "descendant groups for " + groupFullPath, log)) {
                return false;
            }
            previousCursor = cursor;

            throttle();
        }

        return true;
    }

    // Phase B: Resolve Parent References

    void resolveParentReferences(
            Map<Long, Team> syncedTeamsByNativeId,
            Map<Long, Long> parentNativeIdByChildNativeId,
            String groupFullPath) {
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
                                "Parent team not found in sync: child={}, parentNativeId={}",
                                child.getSlug(),
                                parentNativeId);
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

    int syncTeamMembers(HttpGraphQlClient client, Long scopeId, Long teamId, String groupFullPath, Long providerId) {
        // Phase C.1: Fetch all members via GraphQL OUTSIDE a transaction
        // to avoid holding a DB connection during network I/O and throttle delays.
        List<GitLabGroupMemberResponse> allMembers = new ArrayList<>();
        boolean memberSyncComplete = fetchAllGroupMembers(client, scopeId, groupFullPath, allMembers);

        // Phase C.2: Apply membership diff in a short transaction
        Integer result = transactionTemplate.execute(status -> {
            Team team = teamRepository
                    .findById(teamId)
                    .orElseThrow(() -> new IllegalStateException("Team not found: teamId=" + teamId));

            Map<Long, TeamMembership> existingMemberships = team.getMemberships().stream()
                    .collect(Collectors.toMap(tm -> tm.getUser().getId(), tm -> tm));

            Set<Long> syncedMemberIds = new HashSet<>();

            for (GitLabGroupMemberResponse member : allMembers) {
                processMember(member, team, providerId, existingMemberships, syncedMemberIds);
            }

            if (memberSyncComplete) {
                removeStaleTeamMemberships(team, syncedMemberIds);
            } else {
                log.warn("Skipped stale membership cleanup due to incomplete pagination: teamSlug={}", team.getSlug());
            }

            return syncedMemberIds.size();
        });

        return result != null ? result : 0;
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

            List<GitLabGroupMemberResponse> members = Objects.requireNonNull(response)
                    .field("group.groupMembers.nodes")
                    .toEntityList(GitLabGroupMemberResponse.class);

            if (members != null) {
                allMembers.addAll(members);
            }

            GitLabPageInfo memberPageInfo = Objects.requireNonNull(response)
                    .field("group.groupMembers.pageInfo")
                    .toEntity(GitLabPageInfo.class);

            if (memberPageInfo == null || !memberPageInfo.hasNextPage()) {
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

    private void processMember(
            GitLabGroupMemberResponse member,
            Team team,
            Long providerId,
            Map<Long, TeamMembership> existingMemberships,
            Set<Long> syncedMemberIds) {
        if (member == null || member.user() == null || member.user().id() == null) {
            return;
        }

        TeamMembership.Role role = mapAccessLevel(
                member.accessLevel() != null ? member.accessLevel().stringValue() : null);
        if (role == null) {
            // NO_ACCESS or MINIMAL_ACCESS → skip
            return;
        }

        var userRef = member.user();
        User user = gitLabUserService.findOrCreateUser(
                GitLabUserLookup.of(
                        userRef.id(), userRef.username(), userRef.name(), userRef.avatarUrl(), userRef.webUrl()),
                providerId);

        if (user == null) {
            return;
        }

        // Deduplicate: same user could appear on multiple pages in edge cases.
        if (!syncedMemberIds.add(user.getId())) {
            return;
        }

        TeamMembership existing = existingMemberships.get(user.getId());
        if (existing != null) {
            if (existing.getRole() != role) {
                existing.setRole(role);
                teamMembershipRepository.save(existing);
            }
        } else {
            TeamMembership membership = new TeamMembership(team, user, role);
            teamMembershipRepository.save(membership);
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

    // Phase F: Add project collaborators as team members

    /**
     * Adds project-level collaborators (WRITE/TRIAGE) as members of their parent subgroup team.
     * <p>
     * In GitLab iPraktikum, each tutor subgroup contains student repos. Students are direct
     * project members but not group members. This phase bridges the gap so they appear in the
     * team in workspace activity.
     */
    int addProjectCollaboratorsAsTeamMembers(Long teamId, String subgroupFullPath) {
        Integer result = transactionTemplate.execute(status -> {
            Team team = teamRepository
                    .findById(teamId)
                    .orElseThrow(() -> new IllegalStateException("Team not found: teamId=" + teamId));

            // Find collaborators with WRITE or TRIAGE permission on repos under this subgroup.
            // These are direct project members (students), not inherited ADMIN/MAINTAIN (professors/TAs).
            List<RepositoryCollaborator> collaborators = collaboratorRepository.findByOrgLoginAndPermissions(
                    subgroupFullPath,
                    List.of(RepositoryCollaborator.Permission.WRITE, RepositoryCollaborator.Permission.TRIAGE));

            log.info(
                    "Phase F: querying collaborators for subgroup: orgLogin={}, found={}",
                    subgroupFullPath,
                    collaborators.size());

            if (collaborators.isEmpty()) {
                return 0;
            }

            // Deduplicate by user (a student may be in multiple repos under the same subgroup)
            Set<Long> existingMemberIds =
                    team.getMemberships().stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());

            int added = 0;
            Set<Long> seenUserIds = new HashSet<>();
            for (RepositoryCollaborator collab : collaborators) {
                Long userId = collab.getUser().getId();
                if (seenUserIds.add(userId) && !existingMemberIds.contains(userId)) {
                    TeamMembership membership = new TeamMembership(team, collab.getUser(), TeamMembership.Role.MEMBER);
                    teamMembershipRepository.save(membership);
                    added++;
                }
            }

            if (added > 0) {
                log.debug("Added project collaborators to team: teamSlug={}, added={}", team.getSlug(), added);
            }

            return added;
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

    private @Nullable IdentityProvider resolveProvider() {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElse(null);
    }

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
     * be the group {@code groupNativeId}: one entry per relation GitLab counts, none when the user is not a member. Empty
     * when GitLab reports no such group, which proves nothing about the membership. Only reads; a response GitLab could
     * not give throws, so a caller can retry.
     */
    public Optional<List<GitLabGroupMemberResponse>> fetchMembership(
            Long scopeId, String groupFullPath, long groupNativeId, long userNativeId, boolean includeInherited) {
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
                        includeInherited ? List.of("DIRECT", "INHERITED") : List.of("DIRECT")),
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
        return Optional.of(members.stream()
                .filter(member -> member.user() instanceof GitLabGroupMemberResponse.GitLabMemberUser user
                        && userGlobalId.equals(user.id()))
                .toList());
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
