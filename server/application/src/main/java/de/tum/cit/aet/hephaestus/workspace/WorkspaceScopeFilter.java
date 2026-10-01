package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Filters which workspaces and repositories are actively monitored/synced.
 * <p>
 * <strong>Purpose:</strong> In development, you don't want to sync hundreds of repositories
 * from every GitHub App installation. This filter lets you focus on specific orgs/repos
 * while keeping all workspace metadata intact.
 * <p>
 * <strong>How it works:</strong>
 * <ul>
 *   <li>If both filter lists are empty → everything is allowed (production behavior)</li>
 *   <li>If org filter is set → only workspaces with matching accountLogin are monitored</li>
 *   <li>If repo filter is set → only matching repositories are synced</li>
 * </ul>
 *
 * @see SyncSchedulerProperties.FilterProperties
 */
@Component
public class WorkspaceScopeFilter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopeFilter.class);

    private final Set<String> allowedOrganizations;
    private final Set<String> allowedRepositories;

    public WorkspaceScopeFilter(SyncSchedulerProperties properties) {
        SyncSchedulerProperties.FilterProperties filters = properties.filters();
        log.debug(
                "Received SyncSchedulerProperties.filters: allowedOrganizations={}, allowedRepositories={}",
                filters.allowedOrganizations(),
                filters.allowedRepositories());
        this.allowedOrganizations = normalizeSet(filters.allowedOrganizations());
        this.allowedRepositories = normalizeSet(filters.allowedRepositories());
    }

    @PostConstruct
    void logConfiguration() {
        if (isActive()) {
            log.info(
                    "Initialized workspace scope filter: status=ACTIVE, allowedOrganizations={}, allowedRepositories={}",
                    allowedOrganizations,
                    allowedRepositories);
        } else {
            log.info("Initialized workspace scope filter: status=INACTIVE");
        }
    }

    public boolean isWorkspaceAllowed(Workspace workspace) {
        if (!isActive()) {
            return true;
        }
        if (workspace == null) {
            return false;
        }
        return allowedOrganizations.isEmpty() || allowedOrganizations.contains(normalize(workspace.getAccountLogin()));
    }

    /**
     * Check if a workspace is allowed by its ID.
     * When only workspace ID is available and no org filter is active, returns true.
     * This is used by integration.scm services that do not have direct access to Workspace entities.
     *
     * @param workspaceId the workspace ID
     * @return true if allowed (or if org filter is not active)
     */
    public boolean isWorkspaceAllowed(Long workspaceId) {
        // If no org filter is configured, all workspaces are allowed
        if (allowedOrganizations.isEmpty()) {
            return true;
        }
        // With only workspaceId, we can't check org - caller must ensure appropriate filtering
        // This is a fallback for when workspace entity is not available
        // In practice, the scheduler (which has workspace access) does org-level filtering
        return workspaceId != null;
    }

    public boolean isRepositoryAllowed(@Nullable RepositoryToMonitor repository) {
        return isRepositoryAllowed(repository != null ? repository.getNameWithOwner() : null);
    }

    public boolean isRepositoryAllowed(@Nullable String nameWithOwner) {
        if (nameWithOwner == null) {
            return false;
        }

        // Extract org from "org/repo" format and check against org filter
        if (!allowedOrganizations.isEmpty()) {
            String org = nameWithOwner.contains("/") ? nameWithOwner.split("/")[0] : nameWithOwner;
            if (!allowedOrganizations.contains(normalize(org))) {
                return false;
            }
        }

        // Check specific repository filter (if configured)
        if (!allowedRepositories.isEmpty() && !allowedRepositories.contains(normalize(nameWithOwner))) {
            return false;
        }

        return true;
    }

    /**
     * Whether {@code workspace} may handle {@code nameWithOwner}, a repository its caller has already placed inside the
     * workspace's own group. The organization filter applies to that group, so an allowed nested group is not judged by
     * its root segment; the repository filter still has to name the repository exactly.
     */
    public boolean isRepositoryAllowed(Workspace workspace, String nameWithOwner) {
        return isGroupRepositoryAllowed(workspace.getAccountLogin(), nameWithOwner);
    }

    /** {@link #isRepositoryAllowed(Workspace, String)} for a caller that holds the workspace's group, not the entity. */
    public boolean isGroupRepositoryAllowed(String group, String nameWithOwner) {
        return isOrganizationAllowed(group)
                && (allowedRepositories.isEmpty() || allowedRepositories.contains(normalize(nameWithOwner)));
    }

    public boolean isOrganizationAllowed(String login) {
        if (allowedOrganizations.isEmpty()) {
            return true;
        }
        return allowedOrganizations.contains(normalize(login));
    }

    public boolean isActive() {
        return !(allowedOrganizations.isEmpty() && allowedRepositories.isEmpty());
    }

    private Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return values.stream()
                .map(this::normalize)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private @Nullable String normalize(@Nullable String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ENGLISH);
    }
}
