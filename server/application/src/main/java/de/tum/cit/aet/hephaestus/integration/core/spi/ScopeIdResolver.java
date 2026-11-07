package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Optional;

/**
 * Resolves a workspace without exposing workspace entities to integration processors.
 * A bound broker dispatch supplies the authoritative workspace, including the absence of a workspace
 * for installation deliveries. Only unbound calls use account-login or repository-monitor lookups.
 */
public interface ScopeIdResolver {
    /**
     * Resolve scope ID by organization login.
     * <p>
     * This is the primary resolution method for organization-owned repositories.
     *
     * @param organizationLogin the GitHub organization login (e.g., "ls1intum")
     * @return the scope ID if found, empty otherwise
     */
    Optional<Long> findScopeIdByOrgLogin(String organizationLogin);

    /**
     * Resolve scope ID by repository nameWithOwner.
     * <p>
     * This method supports personal (user-owned) repositories that have no organization.
     * It looks up the workspace that has this repository in its monitored repositories list.
     * <p>
     * This is also used as a fallback when organization-based lookup fails, ensuring
     * activity events are tracked for all repository types.
     *
     * @param repositoryNameWithOwner the full repository name (e.g., "octocat/hello-world")
     * @return the scope ID if found, empty otherwise
     */
    Optional<Long> findScopeIdByRepositoryName(String repositoryNameWithOwner);
}
