package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;

/**
 * Records what the provider proved about who can see a monitored repository. An answer that narrows who can see it
 * always applies. An answer that widens it applies only if no other answer applied since its read began, and only to a
 * monitor that still names the repository the read asked about.
 */
public interface RepositoryReadersProvider {
    /** One provider read of a repository, begun for one monitor before it asked the provider. */
    record ReadersRead(
            Long syncTargetId, long repositoryNativeId, long providerId, long generation, Instant requestedAt) {}

    /** Begins a read of the repository with {@code repositoryNativeId} on the provider, for this monitor. */
    ReadersRead beginReadersProof(Long syncTargetId, long repositoryNativeId, long providerId, Instant requestedAt);

    /** The provider showed the repository public to this workspace's credential in {@code read}. */
    void recordRepositoryPublic(ReadersRead read);

    /**
     * The provider showed the repository, in {@code read}, as not public. The mirror row that the read stored already
     * narrowed every workspace's monitor of the repository; this also lets this monitor wait for its readers again.
     */
    void recordRepositoryNotPublic(ReadersRead read);

    /**
     * The provider reported that the repository turned private or was deleted. That narrows every workspace's monitor of
     * it, also when the mirror row already held the repository private or no longer exists.
     */
    void recordRepositoryPrivatized(long repositoryNativeId);
}
