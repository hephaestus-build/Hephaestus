package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Filters which repositories should be processed during sync and webhook handling.
 * <p>
 * This interface abstracts the repository filtering logic from the integration.scm module,
 * allowing the workspace module (or other host applications) to provide the actual
 * filtering implementation based on their configuration.
 * <p>
 * <b>Design rationale:</b> The integration.scm module should not depend on workspace-specific
 * concepts. By using this SPI, the filtering decision is delegated to the host application
 * which knows its own configuration (e.g., {@code hephaestus.sync.filters.allowed-repositories}).
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory
 */
public interface RepositoryScopeFilter {
    /**
     * Check if a repository is allowed for processing.
     * <p>
     * When filtering is active, only repositories explicitly allowed by the configuration
     * will pass this check. When filtering is inactive, all repositories are allowed.
     *
     * @param nameWithOwner the repository identifier in "owner/repo" format (e.g., "ls1intum/Hephaestus")
     * @return true if the repository should be processed, false to skip
     */
    boolean isRepositoryAllowed(String nameWithOwner);

    /** Whether this workspace monitors the exact source repository, including its provider. */
    boolean isRepositoryMonitored(Long scopeId, Long repositoryId);

    /**
     * Check if any filtering is currently active.
     * <p>
     * When inactive, all repositories are allowed and no filter checks are necessary.
     *
     * @return true if filtering is enabled, false if all repositories are allowed
     */
    boolean isActive();
}
