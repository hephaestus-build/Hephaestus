package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;
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
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            TransactionTemplate transactionTemplate) {
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
        this.transactionTemplate = transactionTemplate;
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
    public int syncTeamsForOrganization(Long scopeId, String organizationLogin) {
        if (organizationLogin == null || organizationLogin.isBlank()) {
            log.warn("Skipped team sync: reason=missingOrgLogin, scopeId={}", scopeId);
            return 0;
        }
        String safeOrgLogin = Objects.requireNonNull(sanitizeForLog(organizationLogin));

        // Provider-type-scoped so a same-login GitLab org cannot collide (ADR-0012).
        Long organizationId = organizationRepository
                .findByLoginIgnoreCaseAndProvider_Type(organizationLogin, IdentityProviderType.GITHUB)
                .map(Organization::getId)
                .orElse(null);
        if (organizationId == null) {
            log.warn("Skipped team sync: reason=organizationNotFound, orgLogin={}", safeOrgLogin);
            return 0;
        }

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        List<FetchedTeam> fetched = new ArrayList<>();
        boolean complete;
        int pagesProcessed;
        try {
            int teamsReceived = 0;
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
                                        signal.failure().getClass().getSimpleName())))
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
                    fetched.add(new FetchedTeam(
                            graphQlTeam,
                            memberEdges(client, graphQlTeam, organizationLogin, scopeId),
                            repositoryEdges(client, graphQlTeam, organizationLogin, scopeId)));
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

            complete = !hasNextPage;
            pagesProcessed = pageCount;
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

        if (!complete) {
            log.warn(
                    "Skipped stale team removal: reason=incompleteSync, orgLogin={}, pagesProcessed={}",
                    safeOrgLogin,
                    pagesProcessed);
        }
        // GitHub has answered for every team. A database failure propagates, so the caller's transient-failure retry
        // gets a fresh transaction.
        TeamWrite written = Objects.requireNonNull(transactionTemplate.execute(
                status -> store(organizationId, organizationLogin, scopeId, fetched, complete, safeOrgLogin)));
        log.info(
                "Completed team sync: orgLogin={}, teamCount={}, permissionCount={}, complete={}, scopeId={}",
                safeOrgLogin,
                written.teams(),
                written.permissions(),
                complete,
                scopeId);
        return written.teams();
    }

    /** One team as GitHub listed it, with its member and repository edges; null where GitHub sent none. */
    private record FetchedTeam(
            GHTeam team,
            @Nullable Edges<GHTeamMemberEdge> members,
            @Nullable Edges<GHTeamRepositoryEdge> repositories) {}

    /**
     * The edges of one team connection, and whether the read reached its last page. Only a complete list removes
     * what it does not hold: a list cut short by a rate limit, an error or the page limit would remove members and
     * permissions that still exist.
     */
    private record Edges<T>(List<T> edges, boolean complete) {}

    private record TeamWrite(int teams, int permissions) {}

    /**
     * Stores the teams that GitHub listed with their memberships and repository permissions, resolves parent teams, and
     * removes deleted teams if the listing is complete.
     */
    private TeamWrite store(
            Long organizationId,
            String organizationLogin,
            Long scopeId,
            List<FetchedTeam> fetched,
            boolean complete,
            String safeOrgLogin) {
        Organization organization =
                organizationRepository.findById(organizationId).orElse(null);
        if (organization == null) {
            return new TeamWrite(0, 0);
        }
        // No repository for org-level teams.
        ProcessingContext context = ProcessingContext.forSync(scopeId, organization.getProvider());
        Set<Long> syncedTeamIds = new HashSet<>();
        // GitHub native ID -> persisted Team, for parent resolution after all teams are saved.
        Map<Long, Team> syncedTeamsByNativeId = new HashMap<>();
        // Child team's native ID -> parent team's native ID, from GraphQL.
        Map<Long, Long> parentNativeIdByChildNativeId = new HashMap<>();
        int teams = 0;
        int permissions = 0;
        for (FetchedTeam entry : fetched) {
            GHTeam graphQlTeam = entry.team();
            Team team = processTeam(graphQlTeam, organizationLogin, context);
            if (team == null) {
                continue;
            }
            syncedTeamIds.add(team.getNativeId());
            syncedTeamsByNativeId.put(team.getNativeId(), team);
            if (graphQlTeam.getParentTeam() != null
                    && graphQlTeam.getParentTeam().getDatabaseId() != null) {
                parentNativeIdByChildNativeId.put(
                        team.getNativeId(),
                        graphQlTeam.getParentTeam().getDatabaseId().longValue());
            }
            if (entry.members() != null) {
                syncTeamMemberships(team, entry.members());
            }
            if (entry.repositories() != null) {
                permissions += syncTeamRepoPermissions(team, entry.repositories());
            }
            teams++;
        }

        // Parents resolve after every team is persisted, so a child listed before its parent still finds it.
        resolveParentReferences(syncedTeamsByNativeId, parentNativeIdByChildNativeId, safeOrgLogin);

        // Only a complete listing removes stale teams: an aborted one (rate limit, error, pagination limit) would
        // delete teams that still exist.
        if (complete) {
            removeDeletedTeams(organizationLogin, syncedTeamIds, context);
        }
        return new TeamWrite(teams, permissions);
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
     * For each member edge, this method ensures the user exists and creates or updates a
     * TeamMembership record with the correct role. If the list is complete, it then removes members GitHub no longer
     * lists.
     *
     * @param team        the Team entity
     * @param memberEdges the member edges of the team, fetched before the write transaction
     */
    private void syncTeamMemberships(Team team, Edges<GHTeamMemberEdge> memberEdges) {
        // Build existing memberships map for efficient lookup and role update
        Map<Long, TeamMembership> existingMemberships = team.getMemberships().stream()
                .collect(Collectors.toMap(tm -> tm.getUser().getId(), tm -> tm));

        Set<Long> syncedMemberIds = new HashSet<>();

        for (var memberEdge : memberEdges.edges()) {
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
                                "Updated team membership role: userId={}, teamName={}, oldRole={}, newRole={}",
                                user.getId(),
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
                            "Created team membership: userId={}, teamName={}, role={}",
                            user.getId(),
                            sanitizeForLog(team.getName()),
                            role);
                }
            }
        }

        if (memberEdges.complete()) {
            removeStaleTeamMemberships(team, syncedMemberIds);
        } else {
            log.warn(
                    "Skipped stale team membership removal: reason=incompleteMemberList, teamName={}",
                    sanitizeForLog(team.getName()));
        }
    }

    /**
     * Synchronizes team repository permissions from the GraphQL response.
     * <p>
     * For each repository edge, this method creates or updates TeamRepositoryPermission
     * records with the correct permission level. If the list is complete, the team's permissions are replaced by it;
     * otherwise the permissions it does not hold stay.
     *
     * @param team      the Team entity
     * @param repoEdges the repository edges of the team, fetched before the write transaction
     * @return number of permissions synced for monitored repositories
     */
    private int syncTeamRepoPermissions(Team team, Edges<GHTeamRepositoryEdge> repoEdges) {
        Set<TeamRepositoryPermission> freshPermissions = new HashSet<>();
        Long providerId = Objects.requireNonNull(team.getProvider().getId());

        for (var repoEdge : repoEdges.edges()) {
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
        if (repoEdges.complete()) {
            team.clearAndAddRepoPermissions(freshPermissions);
        } else {
            log.warn(
                    "Skipped stale team permission removal: reason=incompleteRepositoryList, teamName={}",
                    sanitizeForLog(team.getName()));
            freshPermissions.forEach(team::addRepoPermission);
        }
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

    /** The member edges of a team, paginating past the first page; null if GitHub sent no members connection. */
    private @Nullable Edges<GHTeamMemberEdge> memberEdges(
            HttpGraphQlClient client, GHTeam graphQlTeam, String organizationLogin, Long scopeId) {
        var membersConnection = graphQlTeam.getMembers();
        if (membersConnection == null || membersConnection.getEdges() == null) {
            return null;
        }
        List<GHTeamMemberEdge> allMemberEdges = new ArrayList<>(membersConnection.getEdges());
        var membersPageInfo = membersConnection.getPageInfo();
        if (membersPageInfo == null || !Boolean.TRUE.equals(membersPageInfo.getHasNextPage())) {
            return new Edges<>(allMemberEdges, true);
        }
        log.debug(
                "Fetching additional team members: teamSlug={}, totalCount={}",
                sanitizeForLog(graphQlTeam.getSlug()),
                membersConnection.getTotalCount());
        Edges<GHTeamMemberEdge> rest = fetchAllTeamMemberEdges(
                client, organizationLogin, graphQlTeam.getSlug(), membersPageInfo.getEndCursor(), scopeId);
        allMemberEdges.addAll(rest.edges());
        return new Edges<>(allMemberEdges, rest.complete());
    }

    /** The repository edges of a team, paginating past the first page; null if GitHub sent no repositories connection. */
    private @Nullable Edges<GHTeamRepositoryEdge> repositoryEdges(
            HttpGraphQlClient client, GHTeam graphQlTeam, String organizationLogin, Long scopeId) {
        var reposConnection = graphQlTeam.getRepositories();
        if (reposConnection == null || reposConnection.getEdges() == null) {
            log.debug(
                    "No repositories connection for team: teamSlug={}, connection={}",
                    sanitizeForLog(graphQlTeam.getSlug()),
                    reposConnection == null ? "null" : "edges=null");
            return null;
        }
        List<GHTeamRepositoryEdge> allRepoEdges = new ArrayList<>(reposConnection.getEdges());
        var reposPageInfo = reposConnection.getPageInfo();
        if (reposPageInfo == null || !Boolean.TRUE.equals(reposPageInfo.getHasNextPage())) {
            return new Edges<>(allRepoEdges, true);
        }
        log.debug(
                "Fetching additional team repositories: teamSlug={}, totalCount={}",
                sanitizeForLog(graphQlTeam.getSlug()),
                reposConnection.getTotalCount());
        Edges<GHTeamRepositoryEdge> rest = fetchAllTeamRepositoryEdges(
                client, organizationLogin, graphQlTeam.getSlug(), reposPageInfo.getEndCursor(), scopeId);
        allRepoEdges.addAll(rest.edges());
        return new Edges<>(allRepoEdges, rest.complete());
    }

    /**
     * Fetches all remaining team member edges (with roles) using pagination.
     *
     * @param client            the GraphQL client
     * @param organizationLogin the organization login
     * @param teamSlug          the team slug
     * @param startCursor       the cursor to start from
     * @return the remaining member edges with roles, and whether the read reached the last page
     */
    private Edges<GHTeamMemberEdge> fetchAllTeamMemberEdges(
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
                                    signal.failure().getClass().getSimpleName())))
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

        return new Edges<>(allMemberEdges, !hasNextPage);
    }

    /**
     * Fetches all remaining team repository edges (with permissions) using pagination.
     *
     * @param client            the GraphQL client
     * @param organizationLogin the organization login
     * @param teamSlug          the team slug
     * @param startCursor       the cursor to start from
     * @return the remaining repository edges with permissions, and whether the read reached the last page
     */
    private Edges<GHTeamRepositoryEdge> fetchAllTeamRepositoryEdges(
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
                                    signal.failure().getClass().getSimpleName())))
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

        return new Edges<>(allEdges, !hasNextPage);
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
        // Only teams on this provider: a same-login GitLab group must not be swept by a GitHub sync.
        List<Team> existingTeams = teamRepository.findAllByOrganizationIgnoreCaseAndProviderId(
                organizationLogin, Objects.requireNonNull(context.providerId()));
        int removed = 0;

        for (Team team : existingTeams) {
            if (!syncedTeamIds.contains(team.getNativeId())) {
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
