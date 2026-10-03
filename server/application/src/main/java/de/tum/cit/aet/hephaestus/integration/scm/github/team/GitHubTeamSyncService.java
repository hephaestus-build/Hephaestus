package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeam;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamMemberConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamMemberEdge;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamPrivacy;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamRepositoryConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeamRepositoryEdge;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubTeamEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub teams via GraphQL API.
 * <p>
 * This service fetches teams for an organization via GraphQL and delegates to
 * {@link GitHubTeamProcessor} for persistence, ensuring a single source of truth
 * for team processing logic.
 * <p>
 * It also handles synchronization of team memberships by creating
 * {@link TeamMembership} records for each member returned by the GraphQL query.
 */
@Service
public class GitHubTeamSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubTeamSyncService.class);
    private static final String GET_ORGANIZATION_TEAMS_DOCUMENT = "GetOrganizationTeams";
    private static final String GET_TEAM_MEMBERS_DOCUMENT = "GetTeamMembers";
    private static final String GET_TEAM_REPOSITORIES_DOCUMENT = "GetTeamRepositories";

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final RepositoryRepository repositoryRepository;
    private final OrganizationRepository organizationRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubTeamProcessor teamProcessor;
    private final GitHubUserProcessor userProcessor;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubTeamSyncService(
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            RepositoryRepository repositoryRepository,
            OrganizationRepository organizationRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubTeamProcessor teamProcessor,
            GitHubUserProcessor userProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.repositoryRepository = repositoryRepository;
        this.organizationRepository = organizationRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.teamProcessor = teamProcessor;
        this.userProcessor = userProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all teams for an organization using GraphQL.
     * <p>
     * This method fetches all teams from the organization, processes them using
     * {@link GitHubTeamProcessor}, and also synchronizes team memberships.
     *
     * @param scopeId       the scope ID for authentication
     * @param organizationLogin the GitHub organization login to sync teams for
     * @return number of teams synced
     */
    @Transactional
    public int syncTeamsForOrganization(Long scopeId, String organizationLogin) {
        if (organizationLogin == null || organizationLogin.isBlank()) {
            log.warn("Skipped team sync: reason=missingOrgLogin, scopeId={}", scopeId);
            return 0;
        }
        String safeOrgLogin = Objects.requireNonNull(sanitizeForLog(organizationLogin));

        // Resolve the organization's provider for ProcessingContext (within @Transactional).
        // Provider-type-scoped so a same-login GitLab org cannot collide (ADR-0012).
        Organization organization = organizationRepository
                .findByLoginIgnoreCaseAndProvider_Type(organizationLogin, IdentityProviderType.GITHUB)
                .orElse(null);
        if (organization == null) {
            log.warn("Skipped team sync: reason=organizationNotFound, orgLogin={}", safeOrgLogin);
            return 0;
        }
        IdentityProvider provider = organization.getProvider();

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        // Create processing context for sync operations (no repository for org-level teams)
        ProcessingContext context = ProcessingContext.forSync(scopeId, provider);

        try {
            Set<Long> syncedTeamIds = new HashSet<>();
            // Maps GitHub native ID → persisted Team for parent resolution after all teams are saved
            Map<Long, Team> syncedTeamsByNativeId = new HashMap<>();
            // Maps child team's native ID → parent team's native ID (from GraphQL)
            Map<Long, Long> parentNativeIdByChildNativeId = new HashMap<>();
            int totalSynced = 0;
            int teamsReceived = 0;
            int totalPermissions = 0;
            String cursor = null;
            boolean hasNextPage = true;
            int pageCount = 0;
            int retryAttempt = 0;
            int reportedTotalCount = -1;

            while (hasNextPage) {
                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for teams: orgLogin={}, limit={}",
                            safeOrgLogin,
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(
                                        GET_ORGANIZATION_TEAMS_DOCUMENT)
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
                                        "Retrying team sync after transport error: orgLogin={}, page={}, attempt={}, error={}",
                                        safeOrgLogin,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.graphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "team sync",
                                "orgLogin",
                                safeOrgLogin,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response: orgLogin={}, errors={}",
                            safeOrgLogin,
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "team sync", "orgLogin", safeOrgLogin, log)) {
                        break;
                    }
                }

                GHTeamConnection response =
                        graphQlResponse.field("organization.teams").toEntity(GHTeamConnection.class);

                if (response == null || response.getNodes() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = response.getTotalCount();
                }
                teamsReceived += response.getNodes().size();

                for (var graphQlTeam : response.getNodes()) {
                    Team team = processTeam(graphQlTeam, organizationLogin, context);
                    if (team != null) {
                        syncedTeamIds.add(team.getNativeId());
                        syncedTeamsByNativeId.put(team.getNativeId(), team);

                        // Collect parent mapping for deferred resolution
                        if (graphQlTeam.getParentTeam() != null
                                && graphQlTeam.getParentTeam().getDatabaseId() != null) {
                            parentNativeIdByChildNativeId.put(
                                    team.getNativeId(),
                                    graphQlTeam.getParentTeam().getDatabaseId().longValue());
                        }

                        syncTeamMemberships(client, team, graphQlTeam, organizationLogin, scopeId);
                        totalPermissions +=
                                syncTeamRepoPermissions(client, team, graphQlTeam, organizationLogin, scopeId);
                        totalSynced++;
                    }
                }

                var pageInfo = response.getPageInfo();
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            }

            // Raw nodes received vs teams.totalCount (totalSynced is post-filter).
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "teams", teamsReceived, reportedTotalCount, hasNextPage, "orgLogin=" + safeOrgLogin);
            }

            // Mark sync as completed normally if we exhausted all pages
            boolean syncCompletedNormally = !hasNextPage;

            // Resolve parent references now that all teams are persisted.
            // This two-pass approach ensures correct parentId even when a child
            // team appears before its parent in the GraphQL response.
            resolveParentReferences(syncedTeamsByNativeId, parentNativeIdByChildNativeId, safeOrgLogin);

            // CRITICAL: Only remove stale teams if sync completed fully.
            // If sync was aborted (rate limit, error, pagination limit), we don't have
            // the complete list and would incorrectly delete valid teams.
            if (syncCompletedNormally) {
                removeDeletedTeams(organizationLogin, syncedTeamIds, context);
            } else {
                log.warn(
                        "Skipped stale team removal: reason=incompleteSync, orgLogin={}, pagesProcessed={}",
                        safeOrgLogin,
                        pageCount);
            }

            log.info(
                    "Completed team sync: orgLogin={}, teamCount={}, permissionCount={}, complete={}, scopeId={}",
                    safeOrgLogin,
                    totalSynced,
                    totalPermissions,
                    syncCompletedNormally,
                    scopeId);
            return totalSynced;
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification, 0, MAX_RETRY_ATTEMPTS, "team sync", "orgLogin", safeOrgLogin, log))) {
                return 0;
            }
            return 0;
        }
    }

    /**
     * Processes a single GraphQL team and persists it.
     *
     * @param graphQlTeam       the GraphQL team object
     * @param organizationLogin the organization login
     * @param context           the processing context
     * @return the persisted Team entity, or null if processing failed
     */
    private @Nullable Team processTeam(GHTeam graphQlTeam, String organizationLogin, ProcessingContext context) {
        GitHubTeamEventDTO.GitHubTeamDTO dto = convertToDTO(graphQlTeam);
        Team team = teamProcessor.process(dto, organizationLogin, context);

        if (team != null) {
            // NOTE: parentId resolution is deferred to resolveParentReferences()
            // which runs after all teams have been persisted. This ensures parent
            // teams are available for lookup regardless of processing order.

            // Update last synced timestamp
            team.setLastSyncAt(Instant.now());
            team = teamRepository.save(team);
        }

        return team;
    }

    /**
     * Resolves parent team references after all teams have been persisted.
     * <p>
     * The GitHub GraphQL API returns {@code parentTeam.databaseId} (the GitHub native ID),
     * but our {@code team.parent_id} column references the internal auto-increment PK.
     * This method translates native IDs to internal IDs using the in-memory map built
     * during this sync, and clears stale parent references for teams whose parent was
     * removed in GitHub.
     *
     * @param syncedTeamsByNativeId         map of GitHub native ID → persisted Team
     * @param parentNativeIdByChildNativeId map of child native ID → parent native ID from GraphQL
     * @param safeOrgLogin                  sanitized org login for logging
     */
    private void resolveParentReferences(
            Map<Long, Team> syncedTeamsByNativeId, Map<Long, Long> parentNativeIdByChildNativeId, String safeOrgLogin) {
        List<Team> changed = new ArrayList<>();

        for (Map.Entry<Long, Team> entry : syncedTeamsByNativeId.entrySet()) {
            Team child = entry.getValue();
            Long parentNativeId = parentNativeIdByChildNativeId.get(entry.getKey());

            // Determine the correct internal parent ID (null if no parent in GraphQL)
            Long correctParentId = null;
            if (parentNativeId != null) {
                Team parent = syncedTeamsByNativeId.get(parentNativeId);
                if (parent != null) {
                    correctParentId = parent.getId();
                } else {
                    log.warn(
                            "Parent team not found in current sync: child={}, parentNativeId={}, orgLogin={}",
                            child.getName(),
                            parentNativeId,
                            safeOrgLogin);
                }
            }

            if (!Objects.equals(correctParentId, child.getParentId())) {
                child.setParentId(correctParentId);
                changed.add(child);
            }
        }

        if (!changed.isEmpty()) {
            teamRepository.saveAll(changed);
            log.info("Resolved parent team references: orgLogin={}, updated={}", safeOrgLogin, changed.size());
        }
    }

    /**
     * Synchronizes team memberships from the GraphQL response.
     * <p>
     * For each member in the team's members connection, this method ensures the
     * user exists and creates or updates a TeamMembership record with the correct role.
     * If the team has more than 100 members, this method paginates through all members
     * using the GetTeamMembers query.
     *
     * @param client          the GraphQL client for additional queries
     * @param team            the Team entity
     * @param graphQlTeam     the GraphQL team object containing members
     * @param organizationLogin the organization login for pagination queries
     */
    private void syncTeamMemberships(
            HttpGraphQlClient client, Team team, GHTeam graphQlTeam, String organizationLogin, Long scopeId) {
        var membersConnection = graphQlTeam.getMembers();
        if (membersConnection == null || membersConnection.getEdges() == null) {
            return;
        }

        // Collect all member edges (with roles) - paginate if needed
        List<GHTeamMemberEdge> allMemberEdges = new ArrayList<>(membersConnection.getEdges());
        var membersPageInfo = membersConnection.getPageInfo();

        if (membersPageInfo != null && Boolean.TRUE.equals(membersPageInfo.getHasNextPage())) {
            log.debug(
                    "Fetching additional team members: teamSlug={}, totalCount={}",
                    sanitizeForLog(graphQlTeam.getSlug()),
                    membersConnection.getTotalCount());
            List<GHTeamMemberEdge> additionalMemberEdges = fetchAllTeamMemberEdges(
                    client, organizationLogin, graphQlTeam.getSlug(), membersPageInfo.getEndCursor(), scopeId);
            allMemberEdges.addAll(additionalMemberEdges);
        }

        // Build existing memberships map for efficient lookup and role update
        Map<Long, TeamMembership> existingMemberships = team.getMemberships().stream()
                .collect(Collectors.toMap(tm -> tm.getUser().getId(), tm -> tm));

        Set<Long> syncedMemberIds = new HashSet<>();

        for (var memberEdge : allMemberEdges) {
            if (memberEdge == null
                    || memberEdge.getNode() == null
                    || memberEdge.getNode().getDatabaseId() == null) {
                continue;
            }

            GHUser graphQlUser = memberEdge.getNode();
            GHTeamMemberRole graphQlRole = memberEdge.getRole();

            // Convert GraphQL role to TeamMembership.Role
            TeamMembership.Role role = (graphQlRole == GHTeamMemberRole.MAINTAINER)
                    ? TeamMembership.Role.MAINTAINER
                    : TeamMembership.Role.MEMBER;

            // Convert GraphQL User to GitHubUserDTO and ensure user exists
            GitHubUserDTO userDTO = convertUserToDTO(graphQlUser);
            User user = userProcessor.ensureExists(
                    userDTO, Objects.requireNonNull(team.getProvider().getId()));

            if (user != null) {
                syncedMemberIds.add(user.getId());

                TeamMembership existingMembership = existingMemberships.get(user.getId());
                if (existingMembership != null) {
                    // Update role if changed
                    if (existingMembership.getRole() != role) {
                        log.debug(
                                "Updated team membership role: userLogin={}, teamName={}, oldRole={}, newRole={}",
                                sanitizeForLog(user.getLogin()),
                                sanitizeForLog(team.getName()),
                                existingMembership.getRole(),
                                role);
                        existingMembership.setRole(role);
                        teamMembershipRepository.save(existingMembership);
                    }
                } else {
                    // Create new membership
                    TeamMembership membership = new TeamMembership(team, user, role);
                    teamMembershipRepository.save(membership);
                    log.debug(
                            "Created team membership: userLogin={}, teamName={}, role={}",
                            sanitizeForLog(user.getLogin()),
                            sanitizeForLog(team.getName()),
                            role);
                }
            }
        }

        // Remove memberships for users no longer in the team
        removeStaleTeamMemberships(team, syncedMemberIds);
    }

    /**
     * Synchronizes team repository permissions from the GraphQL response.
     * <p>
     * For each repository in the team's repositories connection, this method creates
     * or updates TeamRepositoryPermission records with the correct permission level.
     *
     * @param client          the GraphQL client for additional queries
     * @param team            the Team entity
     * @param graphQlTeam     the GraphQL team object containing repositories
     * @param organizationLogin the organization login for pagination queries
     * @return number of permissions synced for monitored repositories
     */
    private int syncTeamRepoPermissions(
            HttpGraphQlClient client, Team team, GHTeam graphQlTeam, String organizationLogin, Long scopeId) {
        var reposConnection = graphQlTeam.getRepositories();
        if (reposConnection == null || reposConnection.getEdges() == null) {
            log.debug(
                    "No repositories connection for team: teamName={}, connection={}",
                    sanitizeForLog(team.getName()),
                    reposConnection == null ? "null" : "edges=null");
            return 0;
        }

        log.debug(
                "Processing team repositories: teamName={}, totalCount={}, edgesCount={}",
                sanitizeForLog(team.getName()),
                reposConnection.getTotalCount(),
                reposConnection.getEdges().size());

        // Collect all repository edges - paginate if needed
        List<GHTeamRepositoryEdge> allRepoEdges = new ArrayList<>(reposConnection.getEdges());
        var reposPageInfo = reposConnection.getPageInfo();

        if (reposPageInfo != null && Boolean.TRUE.equals(reposPageInfo.getHasNextPage())) {
            log.debug(
                    "Fetching additional team repositories: teamSlug={}, totalCount={}",
                    sanitizeForLog(graphQlTeam.getSlug()),
                    reposConnection.getTotalCount());
            List<GHTeamRepositoryEdge> additionalRepoEdges = fetchAllTeamRepositoryEdges(
                    client, organizationLogin, graphQlTeam.getSlug(), reposPageInfo.getEndCursor(), scopeId);
            allRepoEdges.addAll(additionalRepoEdges);
        }

        Set<TeamRepositoryPermission> freshPermissions = new HashSet<>();
        Long providerId = Objects.requireNonNull(team.getProvider().getId());

        for (var repoEdge : allRepoEdges) {
            if (repoEdge == null
                    || repoEdge.getNode() == null
                    || repoEdge.getNode().getDatabaseId() == null) {
                continue;
            }

            Long repoNativeId = repoEdge.getNode().getDatabaseId().longValue();
            String repoName = repoEdge.getNode().getNameWithOwner();

            // Look up by native ID + provider (NOT by internal auto-increment ID).
            // GitHub's databaseId is the provider's native numeric ID, which is different
            // from our internal auto-increment primary key.
            var repoOpt = repositoryRepository.findByNativeIdAndProviderId(repoNativeId, providerId);
            if (repoOpt.isEmpty()) {
                log.trace(
                        "Skipping unmonitored repository: teamName={}, repoNativeId={}, repoName={}",
                        sanitizeForLog(team.getName()),
                        repoNativeId,
                        sanitizeForLog(repoName));
                continue;
            }

            Repository repo = repoOpt.get();
            log.trace(
                    "Found monitored repository for team: teamName={}, repoNativeId={}, internalId={}, repoName={}",
                    sanitizeForLog(team.getName()),
                    repoNativeId,
                    repo.getId(),
                    sanitizeForLog(repoName));

            TeamRepositoryPermission.PermissionLevel level = convertPermission(repoEdge.getPermission());

            // Find existing permission or create new (compare by internal repository ID)
            final Long internalRepoId = repo.getId();
            TeamRepositoryPermission permission = team.getRepoPermissions().stream()
                    .filter(existing -> Objects.equals(existing.getRepository().getId(), internalRepoId))
                    .findFirst()
                    .orElseGet(() -> new TeamRepositoryPermission(team, repo, level));

            permission.setPermission(level);
            freshPermissions.add(permission);
        }

        // Save permissions via cascade
        int existingCount = team.getRepoPermissions().size();
        team.clearAndAddRepoPermissions(freshPermissions);
        log.debug(
                "Synced team repository permissions: teamName={}, existingCount={}, newCount={}",
                sanitizeForLog(team.getName()),
                existingCount,
                freshPermissions.size());

        // Explicitly save team to persist cascaded permissions
        teamRepository.save(team);
        return freshPermissions.size();
    }

    /**
     * Converts GraphQL GHRepositoryPermission to TeamRepositoryPermission.PermissionLevel.
     */
    private TeamRepositoryPermission.PermissionLevel convertPermission(GHRepositoryPermission graphQlPermission) {
        if (graphQlPermission == null) {
            return TeamRepositoryPermission.PermissionLevel.READ;
        }
        return switch (graphQlPermission) {
            case ADMIN -> TeamRepositoryPermission.PermissionLevel.ADMIN;
            case MAINTAIN -> TeamRepositoryPermission.PermissionLevel.MAINTAIN;
            case WRITE -> TeamRepositoryPermission.PermissionLevel.WRITE;
            case TRIAGE -> TeamRepositoryPermission.PermissionLevel.TRIAGE;
            case READ -> TeamRepositoryPermission.PermissionLevel.READ;
        };
    }

    /**
     * Fetches all remaining team member edges (with roles) using pagination.
     *
     * @param client            the GraphQL client
     * @param organizationLogin the organization login
     * @param teamSlug          the team slug
     * @param startCursor       the cursor to start from
     * @return list of all remaining member edges with roles
     */
    private List<GHTeamMemberEdge> fetchAllTeamMemberEdges(
            HttpGraphQlClient client, String organizationLogin, String teamSlug, String startCursor, Long scopeId) {
        List<GHTeamMemberEdge> allMemberEdges = new ArrayList<>();
        String cursor = startCursor;
        boolean hasNextPage = true;
        int pageCount = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;

        while (hasNextPage) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for team members: teamSlug={}, limit={}",
                        teamSlug,
                        MAX_PAGINATION_PAGES);
                break;
            }

            final String currentCursor = cursor;
            final int currentPage = pageCount;

            ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_TEAM_MEMBERS_DOCUMENT)
                            .variable("orgLogin", organizationLogin)
                            .variable("teamSlug", teamSlug)
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
                                    "Retrying team members fetch after transport error: teamSlug={}, page={}, attempt={}, error={}",
                                    teamSlug,
                                    currentPage,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(syncProperties.graphqlTimeout());

            if (graphQlResponse == null || !graphQlResponse.isValid()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                if (classification != null) {
                    if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                            classification,
                            retryAttempt,
                            MAX_RETRY_ATTEMPTS,
                            "team members fetch",
                            "teamSlug",
                            teamSlug,
                            log))) {
                        retryAttempt++;
                        continue;
                    }
                    break;
                }
                log.warn(
                        "Received invalid GraphQL response for team members: teamSlug={}, errors={}",
                        teamSlug,
                        graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                break;
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

            // Check if we should pause due to rate limiting
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId, "team members fetch", "teamSlug", teamSlug, log)) {
                    break;
                }
            }

            GHTeamMemberConnection response =
                    graphQlResponse.field("organization.team.members").toEntity(GHTeamMemberConnection.class);

            if (response == null || response.getEdges() == null) {
                break;
            }

            if (reportedTotalCount < 0) {
                reportedTotalCount = response.getTotalCount();
            }

            allMemberEdges.addAll(response.getEdges());

            var pageInfo = response.getPageInfo();
            hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
            cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
            retryAttempt = 0;
        }

        // edges vs totalCount; GitHub over-reports team.members.totalCount for inherited members,
        // so a gap after full pagination (hasNextPage==false) is benign.
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "teamMembers", allMemberEdges.size(), reportedTotalCount, hasNextPage, "teamSlug=" + teamSlug);
        }

        return allMemberEdges;
    }

    /**
     * Fetches all remaining team repository edges (with permissions) using pagination.
     *
     * @param client            the GraphQL client
     * @param organizationLogin the organization login
     * @param teamSlug          the team slug
     * @param startCursor       the cursor to start from
     * @return list of all remaining repository edges with permissions
     */
    private List<GHTeamRepositoryEdge> fetchAllTeamRepositoryEdges(
            HttpGraphQlClient client, String organizationLogin, String teamSlug, String startCursor, Long scopeId) {
        List<GHTeamRepositoryEdge> allEdges = new ArrayList<>();
        String cursor = startCursor;
        boolean hasNextPage = true;
        int pageCount = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;

        while (hasNextPage) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for team repositories: teamSlug={}, limit={}",
                        teamSlug,
                        MAX_PAGINATION_PAGES);
                break;
            }

            final String currentCursor = cursor;
            final int currentPage = pageCount;

            ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_TEAM_REPOSITORIES_DOCUMENT)
                            .variable("orgLogin", organizationLogin)
                            .variable("teamSlug", teamSlug)
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
                                    "Retrying team repositories fetch after transport error: teamSlug={}, page={}, attempt={}, error={}",
                                    teamSlug,
                                    currentPage,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(syncProperties.graphqlTimeout());

            if (graphQlResponse == null || !graphQlResponse.isValid()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                if (classification != null) {
                    if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                            classification,
                            retryAttempt,
                            MAX_RETRY_ATTEMPTS,
                            "team repositories fetch",
                            "teamSlug",
                            teamSlug,
                            log))) {
                        retryAttempt++;
                        continue;
                    }
                    break;
                }
                log.warn(
                        "Received invalid GraphQL response for team repositories: teamSlug={}, errors={}",
                        teamSlug,
                        graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                break;
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

            // Check if we should pause due to rate limiting
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId, "team repositories fetch", "teamSlug", teamSlug, log)) {
                    break;
                }
            }

            GHTeamRepositoryConnection response =
                    graphQlResponse.field("organization.team.repositories").toEntity(GHTeamRepositoryConnection.class);

            if (response == null || response.getEdges() == null) {
                break;
            }

            if (reportedTotalCount < 0) {
                reportedTotalCount = response.getTotalCount();
            }

            allEdges.addAll(response.getEdges());

            var pageInfo = response.getPageInfo();
            hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
            cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
            retryAttempt = 0;
        }

        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "teamRepositories", allEdges.size(), reportedTotalCount, hasNextPage, "teamSlug=" + teamSlug);
        }

        return allEdges;
    }

    /**
     * Removes team memberships for users who are no longer members of the team.
     *
     * @param team            the Team entity
     * @param syncedMemberIds the set of user IDs that are still members
     */
    private void removeStaleTeamMemberships(Team team, Set<Long> syncedMemberIds) {
        Set<TeamMembership> currentMemberships = team.getMemberships();
        int removed = 0;

        for (TeamMembership membership : new HashSet<>(currentMemberships)) {
            if (!syncedMemberIds.contains(membership.getUser().getId())) {
                teamMembershipRepository.delete(membership);
                team.removeMembership(membership);
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Removed stale team memberships: teamName={}, membershipCount={}", team.getName(), removed);
        }
    }

    /**
     * Removes teams that no longer exist in the organization.
     *
     * @param organizationLogin the organization login
     * @param syncedTeamIds     the set of team IDs that were synced
     * @param context           the processing context
     */
    private void removeDeletedTeams(String organizationLogin, Set<Long> syncedTeamIds, ProcessingContext context) {
        List<Team> existingTeams = teamRepository.findAllByOrganizationIgnoreCase(organizationLogin);
        Long providerId = Objects.requireNonNull(context.providerId());
        int removed = 0;

        for (Team team : existingTeams) {
            // Only delete teams on this provider — a same-login GitLab group must not be swept by a GitHub sync.
            if (team.getProvider() != null
                    && Objects.requireNonNull(team.getProvider().getId()).equals(providerId)
                    && !syncedTeamIds.contains(team.getNativeId())) {
                teamProcessor.delete(team.getNativeId(), context);
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Removed stale teams: orgLogin={}, teamCount={}", sanitizeForLog(organizationLogin), removed);
        }
    }

    /**
     * Converts a GraphQL Team to a GitHubTeamDTO.
     *
     * @param graphQlTeam the GraphQL team object
     * @return the DTO for use with GitHubTeamProcessor
     */
    private GitHubTeamEventDTO.GitHubTeamDTO convertToDTO(GHTeam graphQlTeam) {
        Long databaseId = graphQlTeam.getDatabaseId() != null
                ? graphQlTeam.getDatabaseId().longValue()
                : null;

        String privacy = mapPrivacy(graphQlTeam.getPrivacy());
        String htmlUrl = graphQlTeam.getUrl() != null ? graphQlTeam.getUrl().toString() : null;
        Instant createdAt =
                graphQlTeam.getCreatedAt() != null ? graphQlTeam.getCreatedAt().toInstant() : null;
        Instant updatedAt =
                graphQlTeam.getUpdatedAt() != null ? graphQlTeam.getUpdatedAt().toInstant() : null;

        return new GitHubTeamEventDTO.GitHubTeamDTO(
                databaseId,
                graphQlTeam.getId(),
                graphQlTeam.getName(),
                graphQlTeam.getSlug(),
                graphQlTeam.getDescription(),
                privacy,
                null, // permission - not available in team query
                htmlUrl,
                createdAt,
                updatedAt);
    }

    /**
     * Converts a GraphQL User to a GitHubUserDTO.
     *
     * @param graphQlUser the GraphQL user object
     * @return the DTO for use with GitHubUserProcessor
     */
    private GitHubUserDTO convertUserToDTO(GHUser graphQlUser) {
        Long databaseId = graphQlUser.getDatabaseId() != null
                ? graphQlUser.getDatabaseId().longValue()
                : null;

        String avatarUrl =
                graphQlUser.getAvatarUrl() != null ? graphQlUser.getAvatarUrl().toString() : null;

        return new GitHubUserDTO(
                databaseId,
                databaseId,
                graphQlUser.getLogin(),
                avatarUrl,
                null, // htmlUrl - not fetched in the query
                graphQlUser.getName(),
                null // email - not fetched in the query
                );
    }

    /**
     * Maps GraphQL GHTeamPrivacy enum to string for DTO.
     *
     * @param privacy the GraphQL GHTeamPrivacy enum value
     * @return the privacy string, or null if privacy is null
     */
    private @Nullable String mapPrivacy(@Nullable GHTeamPrivacy privacy) {
        if (privacy == null) {
            return null;
        }
        return switch (privacy) {
            case SECRET -> "secret";
            case VISIBLE -> "visible";
        };
    }
}
