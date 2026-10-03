package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganization;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganizationMemberConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganizationMemberEdge;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub organizations via GraphQL API.
 * <p>
 * This service fetches organization data via GraphQL and delegates to
 * {@link GitHubOrganizationProcessor} for persistence, ensuring a single source of truth
 * for organization processing logic.
 * <p>
 * It also handles synchronization of organization memberships by upserting
 * membership records for each member returned by the GraphQL query.
 */
@Service
public class GitHubOrganizationSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrganizationSyncService.class);
    private static final String GET_ORGANIZATION_DOCUMENT = "GetOrganization";
    private static final String GET_ORGANIZATION_MEMBERS_DOCUMENT = "GetOrganizationMembers";
    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubOrganizationProcessor organizationProcessor;
    private final GitHubUserProcessor userProcessor;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private final IdentityProviderRepository gitProviderRepository;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubOrganizationSyncService(
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubOrganizationProcessor organizationProcessor,
            GitHubUserProcessor userProcessor,
            OrganizationMembershipRepository organizationMembershipRepository,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            IdentityProviderRepository gitProviderRepository) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.organizationProcessor = organizationProcessor;
        this.userProcessor = userProcessor;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.gitProviderRepository = gitProviderRepository;
    }

    /**
     * Synchronizes an organization by login using GraphQL.
     * <p>
     * This method fetches the organization details from GitHub's GraphQL API,
     * processes it using {@link GitHubOrganizationProcessor}, and also synchronizes
     * organization memberships.
     *
     * @param scopeId        the scope ID for authentication
     * @param organizationLogin  the GitHub organization login to sync
     * @return the synchronized Organization entity, or null if sync failed
     */
    public @Nullable Organization syncOrganization(Long scopeId, @Nullable String organizationLogin) {
        if (organizationLogin == null || organizationLogin.isBlank()) {
            log.warn("Skipped organization sync: reason=missingLogin, scopeId={}", scopeId);
            return null;
        }
        String safeOrganizationLogin = Objects.requireNonNullElse(sanitizeForLog(organizationLogin), organizationLogin);

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        try {
            ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(GET_ORGANIZATION_DOCUMENT)
                            .variable("login", organizationLogin)
                            .execute())
                    .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                            .maxBackoff(TRANSPORT_MAX_BACKOFF)
                            .jitter(JITTER_FACTOR)
                            .filter(ScmTransportErrors::isTransportError)
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying after transport error: context=organizationSync, orgLogin={}, attempt={}, error={}",
                                    safeOrganizationLogin,
                                    signal.totalRetries() + 1,
                                    signal.failure().getMessage())))
                    .block(syncProperties.graphqlTimeout());

            if (graphQlResponse == null || !graphQlResponse.isValid()) {
                ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                if (classification != null) {
                    if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                            classification,
                            0,
                            MAX_RETRY_ATTEMPTS,
                            "organization sync",
                            "orgLogin",
                            safeOrganizationLogin,
                            log))) {
                        return syncOrganization(scopeId, organizationLogin);
                    }
                    return null;
                }
                log.warn(
                        "Received invalid GraphQL response: orgLogin={}, errors={}",
                        safeOrganizationLogin,
                        graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                return null;
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

            // Check if we should pause due to rate limiting
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId, "organization sync", "orgLogin", safeOrganizationLogin, log)) {
                    return null;
                }
            }

            GHOrganization graphQlOrg = graphQlResponse.field("organization").toEntity(GHOrganization.class);

            if (graphQlOrg == null) {
                log.warn("Skipped organization sync: reason=notFound, orgLogin={}", safeOrganizationLogin);
                return null;
            }

            // Resolve GitHub provider ID
            Long providerId = Objects.requireNonNull(gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                    .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"))
                    .getId());

            // Convert GraphQL response to DTO and process
            GitHubOrganizationEventDTO.GitHubOrganizationDTO dto = convertToDTO(graphQlOrg);
            Organization organization = organizationProcessor.process(dto, providerId);

            if (organization != null) {
                // Sync organization memberships with full pagination
                int membersSynced = syncOrganizationMemberships(client, organization, graphQlOrg, scopeId);

                // Mark sync timestamp - organization is managed entity, will be persisted at commit
                organization.setLastSyncAt(Instant.now());

                log.info(
                        "Synced organization: orgId={}, orgLogin={}, memberCount={}",
                        organization.getNativeId(),
                        Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                        membersSynced);
            }

            return organization;
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification,
                    0,
                    MAX_RETRY_ATTEMPTS,
                    "organization sync",
                    "orgLogin",
                    safeOrganizationLogin,
                    log))) {
                return null;
            }
            return null;
        }
    }

    /**
     * Synchronizes organization memberships from the GraphQL response with full pagination.
     * <p>
     * For each member in the organization's membersWithRole connection, this method
     * ensures the user exists and upserts an OrganizationMembership record.
     * If there are more than 100 members, it paginates through all pages.
     *
     * @param client       the GraphQL client for pagination requests
     * @param organization the Organization entity
     * @param graphQlOrg   the GraphQL organization object containing initial members
     * @return the number of members synced
     */
    private int syncOrganizationMemberships(
            HttpGraphQlClient client, Organization organization, GHOrganization graphQlOrg, Long scopeId) {
        var membersConnection = graphQlOrg.getMembersWithRole();
        if (membersConnection == null || membersConnection.getEdges() == null) {
            log.debug(
                    "No members found for organization: orgId={}, orgLogin={}",
                    organization.getNativeId(),
                    Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"));
            return 0;
        }

        // Collect all members with pagination, using a Set to track seen user IDs for deduplication
        List<GHOrganizationMemberEdge> allMembers = new ArrayList<>(membersConnection.getEdges());
        // Raw edges received across all pages; allMembers is post-dedup, so it would under-count and
        // could mask a real truncation — the completeness check must compare the raw count.
        int membersReceived = membersConnection.getEdges().size();
        Set<Long> seenUserDatabaseIds = new HashSet<>();
        for (GHOrganizationMemberEdge edge : membersConnection.getEdges()) {
            if (edge != null && edge.getNode() != null && edge.getNode().getDatabaseId() != null) {
                seenUserDatabaseIds.add(edge.getNode().getDatabaseId().longValue());
            }
        }

        GHPageInfo pageInfo = membersConnection.getPageInfo();
        String cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
        int pageCount = 0;
        int latestTotalCount = membersConnection.getTotalCount();
        int retryAttempt = 0;

        // Paginate through all remaining members if there are more pages
        while (pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage())) {
            // Safety check: if cursor is null but hasNextPage is true, we have a problem
            // This would cause re-fetching from the beginning, leading to duplicates
            if (cursor == null) {
                log.warn(
                        "Missing pagination cursor while hasNextPage=true: orgLogin={}, pageCount={}. "
                                + "This indicates a bug - check that GetOrganization.graphql includes endCursor in pageInfo.",
                        Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                        pageCount);
                break;
            }
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for organization members: orgLogin={}, limit={}",
                        Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                        MAX_PAGINATION_PAGES);
                break;
            }

            final String currentCursor = cursor;
            final int currentPage = pageCount;

            ClientGraphQlResponse graphQlResponse = Mono.defer(() -> client.documentName(
                                    GET_ORGANIZATION_MEMBERS_DOCUMENT)
                            .variable("login", organization.getLogin())
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
                                    "Retrying after transport error: context=orgMembersSync, orgLogin={}, page={}, attempt={}, error={}",
                                    Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
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
                            "organization members sync",
                            "orgLogin",
                            Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                            log))) {
                        retryAttempt++;
                        continue;
                    }
                    break;
                }
                log.warn(
                        "Received invalid GraphQL response for members: orgLogin={}, errors={}",
                        Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                        graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                break;
            }

            // Track rate limit from response
            graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

            // Check if we should pause due to rate limiting
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                        scopeId,
                        "organization members sync",
                        "orgLogin",
                        Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                        log)) {
                    break;
                }
            }

            GHOrganizationMemberConnection nextPage = graphQlResponse
                    .field("organization.membersWithRole")
                    .toEntity(GHOrganizationMemberConnection.class);

            if (nextPage == null || nextPage.getEdges() == null) {
                break;
            }

            membersReceived += nextPage.getEdges().size();

            // Add only members we haven't seen yet (deduplication safety)
            for (GHOrganizationMemberEdge edge : nextPage.getEdges()) {
                if (edge != null && edge.getNode() != null && edge.getNode().getDatabaseId() != null) {
                    Long databaseId = edge.getNode().getDatabaseId().longValue();
                    if (seenUserDatabaseIds.add(databaseId)) {
                        allMembers.add(edge);
                    }
                }
            }

            // Track latest totalCount from pagination (may change if members join/leave during sync)
            latestTotalCount = nextPage.getTotalCount();

            pageInfo = nextPage.getPageInfo();
            cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
            retryAttempt = 0;
        }

        // Mark sync as completed normally if we exhausted all pages (pageInfo.hasNextPage is false)
        boolean syncCompletedNormally = pageInfo == null || !Boolean.TRUE.equals(pageInfo.getHasNextPage());

        // Raw edges received vs members.totalCount (allMembers is post-dedup).
        if (latestTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "members",
                    membersReceived,
                    latestTotalCount,
                    !syncCompletedNormally,
                    "orgLogin=" + Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"));
        }

        log.debug(
                "Fetched organization members: orgId={}, orgLogin={}, fetchedCount={}, totalCount={}, pages={}, complete={}",
                organization.getNativeId(),
                Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                allMembers.size(),
                latestTotalCount,
                pageCount + 1,
                syncCompletedNormally);

        Set<Long> syncedUserIds = new HashSet<>();
        int memberCount = 0;

        for (GHOrganizationMemberEdge edge : allMembers) {
            if (edge == null || edge.getNode() == null) {
                continue;
            }

            var graphQlUser = edge.getNode();
            if (graphQlUser.getDatabaseId() == null) {
                continue;
            }

            // Convert GraphQL User to GitHubUserDTO and ensure user exists
            GitHubUserDTO userDTO = convertUserToDTO(graphQlUser);
            User user = userProcessor.ensureExists(
                    userDTO, Objects.requireNonNull(organization.getProvider().getId()));

            if (user != null) {
                syncedUserIds.add(user.getId());

                // Get role from edge
                OrganizationMemberRole role = mapRole(edge.getRole());

                // Upsert membership
                organizationMembershipRepository.upsertMembership(organization.getId(), user.getId(), role);
                memberCount++;
                log.debug(
                        "Synced organization membership: orgId={}, userId={}, userLogin={}, role={}",
                        organization.getNativeId(),
                        user.getId(),
                        sanitizeForLog(user.getLogin()),
                        role);
            }
        }

        // CRITICAL: Only remove stale memberships if sync completed fully.
        // If sync was aborted (rate limit, error, pagination limit), we don't have
        // the complete list and would incorrectly delete valid memberships.
        if (syncCompletedNormally) {
            removeStaleMemberships(organization, syncedUserIds);
        } else {
            log.warn(
                    "Skipped stale membership removal: reason=incompleteSync, orgLogin={}, pagesProcessed={}",
                    Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                    pageCount + 1);
        }

        return memberCount;
    }

    /**
     * Removes organization memberships for users who are no longer members.
     *
     * @param organization   the Organization entity
     * @param syncedUserIds  the set of user IDs that are still members
     */
    private void removeStaleMemberships(Organization organization, Set<Long> syncedUserIds) {
        List<Long> existingUserIds = organizationMembershipRepository.findUserIdsByOrganizationId(organization.getId());

        Set<Long> staleUserIds = new HashSet<>(existingUserIds);
        staleUserIds.removeAll(syncedUserIds);

        if (!staleUserIds.isEmpty()) {
            organizationMembershipRepository.deleteByOrganizationIdAndUserIdIn(organization.getId(), staleUserIds);
            log.debug(
                    "Removed stale organization memberships: orgId={}, orgLogin={}, removedCount={}",
                    organization.getNativeId(),
                    Objects.requireNonNullElse(sanitizeForLog(organization.getLogin()), "unknown"),
                    staleUserIds.size());
        }
    }

    /**
     * Converts a GraphQL Organization to a GitHubOrganizationDTO.
     *
     * @param graphQlOrg the GraphQL organization object
     * @return the DTO for use with GitHubOrganizationProcessor
     */
    private GitHubOrganizationEventDTO.GitHubOrganizationDTO convertToDTO(GHOrganization graphQlOrg) {
        Long databaseId =
                graphQlOrg.getDatabaseId() != null ? graphQlOrg.getDatabaseId().longValue() : null;

        String avatarUrl =
                graphQlOrg.getAvatarUrl() != null ? graphQlOrg.getAvatarUrl().toString() : null;

        String htmlUrl = graphQlOrg.getUrl() != null ? graphQlOrg.getUrl().toString() : null;

        Instant createdAt =
                graphQlOrg.getCreatedAt() != null ? graphQlOrg.getCreatedAt().toInstant() : null;

        Instant updatedAt =
                graphQlOrg.getUpdatedAt() != null ? graphQlOrg.getUpdatedAt().toInstant() : null;

        return new GitHubOrganizationEventDTO.GitHubOrganizationDTO(
                databaseId,
                graphQlOrg.getId(),
                graphQlOrg.getLogin(),
                graphQlOrg.getDescription(),
                avatarUrl,
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

        // Follow same pattern as GitHubUserDTO.fromUser(): null for 'id', databaseId for 'databaseId'
        // The 'id' field is only populated from REST webhook responses, not GraphQL
        return new GitHubUserDTO(
                null,
                databaseId,
                graphQlUser.getLogin(),
                avatarUrl,
                null, // htmlUrl - not fetched in the query
                graphQlUser.getName(),
                graphQlUser.getEmail());
    }

    /**
     * Maps GraphQL OrganizationMemberRole enum to our domain enum.
     * <p>
     * Uses if-else instead of switch expression to avoid anonymous class generation
     * that causes NoClassDefFoundError with Spring Boot DevTools hot reload.
     *
     * @param graphQlRole the GraphQL OrganizationMemberRole enum value
     * @return the domain OrganizationMemberRole, or MEMBER as default
     */
    private OrganizationMemberRole mapRole(GHOrganizationMemberRole graphQlRole) {
        if (graphQlRole == null) {
            return OrganizationMemberRole.MEMBER;
        }
        if (graphQlRole == GHOrganizationMemberRole.ADMIN) {
            return OrganizationMemberRole.ADMIN;
        }
        return OrganizationMemberRole.MEMBER;
    }
}
