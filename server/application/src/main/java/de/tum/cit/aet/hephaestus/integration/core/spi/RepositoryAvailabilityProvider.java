package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Persisted, scope-local repository availability shared by recent sync and backfill. */
public interface RepositoryAvailabilityProvider {
    /** Atomically reserves a due metadata recheck. Healthy targets are not throttled. */
    boolean deferUnavailableRepository(Long scopeId, Long syncTargetId);

    /** Backfills must not fetch a repository until its metadata check recovers. */
    boolean isRepositoryUnavailable(Long scopeId, Long syncTargetId);

    void recordRepositoryUnavailable(Long scopeId, Long syncTargetId);

    void clearRepositoryUnavailable(Long scopeId, Long syncTargetId);

    /** A failed attempt without an absence response retains normal retry behavior. */
    void retryUnavailableRepository(Long scopeId, Long syncTargetId);

    /** An accepted manual connection sync permits an immediate metadata recheck. */
    void recheckUnavailableRepositories(Long scopeId);
}
