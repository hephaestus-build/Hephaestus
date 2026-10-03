package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronizes GitLab work item types as IssueType entities.
 * <p>
 * GitLab groups have work item types (Issue, Task, Epic, Incident, etc.)
 * available via the {@code group.workItemTypes} GraphQL field.
 * These are mapped to {@link IssueType} entities at the organization level.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueTypeSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueTypeSyncService.class);
    private static final String GET_WORK_ITEM_TYPES_DOCUMENT = "GetGroupWorkItemTypes";

    private final IssueTypeRepository issueTypeRepository;
    private final OrganizationRepository organizationRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabProperties gitLabProperties;

    public GitLabIssueTypeSyncService(
            IssueTypeRepository issueTypeRepository,
            OrganizationRepository organizationRepository,
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabProperties gitLabProperties) {
        this.issueTypeRepository = issueTypeRepository;
        this.organizationRepository = organizationRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.gitLabProperties = gitLabProperties;
    }

    /**
     * Syncs work item types for a group as IssueType entities.
     *
     * @param scopeId    the workspace scope ID
     * @param groupPath  the full path of the GitLab group
     * @return number of issue types synced
     */
    @Transactional
    public int syncIssueTypesForGroup(Long scopeId, String groupPath) {
        String safeGroupPath = sanitizeForLog(groupPath);

        Organization org = organizationRepository
                .findByLoginIgnoreCaseAndProvider_Type(groupPath, IdentityProviderType.GITLAB)
                .orElse(null);
        if (org == null) {
            log.debug("Organization not found, skipping issue type sync: groupPath={}", safeGroupPath);
            return 0;
        }

        try {
            graphQlClientProvider.acquirePermission();
            graphQlClientProvider.waitIfRateLimitLow(scopeId);

            HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
            ClientGraphQlResponse response = client.documentName(GET_WORK_ITEM_TYPES_DOCUMENT)
                    .variable("fullPath", groupPath)
                    .execute()
                    .block(gitLabProperties.graphqlTimeout());

            var handleResult = responseHandler.handle(response, "issue types for " + safeGroupPath, log);
            if (handleResult.action() != GitLabGraphQlResponseHandler.HandleResult.Action.CONTINUE) {
                graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid response for work item types"));
                return 0;
            }
            graphQlClientProvider.recordSuccess();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) (List<?>) Objects.requireNonNull(response)
                    .field("group.workItemTypes.nodes")
                    .toEntityList(Map.class);

            if (nodes == null || nodes.isEmpty()) {
                log.debug("No work item types found: groupPath={}", safeGroupPath);
                return 0;
            }

            Set<String> syncedIds = new HashSet<>();
            int synced = 0;

            for (Map<String, Object> node : nodes) {
                IssueType issueType = processWorkItemType(node, org);
                if (issueType != null) {
                    syncedIds.add(issueType.getId());
                    synced++;
                }
            }

            // Disable stale issue types
            disableStaleIssueTypes(org.getId(), syncedIds);

            log.info("Synced GitLab issue types: groupPath={}, count={}", safeGroupPath, synced);
            return synced;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Issue type sync interrupted: groupPath={}", safeGroupPath);
            return 0;
        } catch (Exception e) {
            log.warn("Issue type sync failed: groupPath={}", safeGroupPath, e);
            return 0;
        }
    }

    @Nullable
    private IssueType processWorkItemType(Map<String, Object> node, Organization org) {
        String globalId = (String) node.get("id");
        String name = (String) node.get("name");
        String iconName = (String) node.get("iconName");

        if (globalId == null || name == null) return null;

        IssueType issueType = issueTypeRepository.findById(globalId).orElse(null);

        if (issueType == null) {
            issueType = new IssueType();
            issueType.setId(globalId);
            issueType.setOrganization(org);
        }

        issueType.setName(name);
        issueType.setColor(mapIconToColor(name, iconName));
        issueType.setEnabled(true);
        issueType.setLastSyncAt(Instant.now());

        return issueTypeRepository.save(issueType);
    }

    private void disableStaleIssueTypes(Long organizationId, Set<String> syncedIds) {
        List<IssueType> existing = issueTypeRepository.findAllByOrganizationId(organizationId);
        int disabled = 0;
        for (IssueType it : existing) {
            if (!syncedIds.contains(it.getId()) && it.isEnabled()) {
                it.setEnabled(false);
                issueTypeRepository.save(it);
                disabled++;
            }
        }
        if (disabled > 0) {
            log.info("Disabled stale issue types: orgId={}, count={}", organizationId, disabled);
        }
    }

    /**
     * Maps GitLab work item type name/icon to a color.
     * Follows the convention used by GitHub issue type sync.
     */
    static IssueType.Color mapIconToColor(@Nullable String name, @Nullable String iconName) {
        if (name == null) return IssueType.Color.GRAY;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "issue" -> IssueType.Color.BLUE;
            case "task" -> IssueType.Color.GREEN;
            case "incident" -> IssueType.Color.RED;
            case "epic" -> IssueType.Color.PURPLE;
            case "objective" -> IssueType.Color.ORANGE;
            case "key result", "key_result" -> IssueType.Color.YELLOW;
            case "test case", "test_case" -> IssueType.Color.PINK;
            default -> IssueType.Color.GRAY;
        };
    }
}
