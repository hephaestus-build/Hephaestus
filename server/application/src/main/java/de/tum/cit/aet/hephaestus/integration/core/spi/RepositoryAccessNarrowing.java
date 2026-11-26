package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Collection;

/**
 * Records what a provider event removed from who can see a monitored repository. Each answer only narrows, so it applies
 * whatever the order of answers, and it moves every affected monitor's proof generation on: a listing that began before
 * the event cannot apply.
 */
public interface RepositoryAccessNarrowing {

    /**
     * The provider reported that it removed the person with {@code readerNativeId} from the repositories with these
     * native ids: as a collaborator, from a team or from the organization. Every monitor of them on {@code providerId}
     * drops the person and moves its generation on, also when the mirror held no row for the person, so a listing
     * that began before the removal cannot name the person again.
     */
    void recordReaderRemoved(long providerId, long readerNativeId, Collection<Long> repositoryNativeIds);

    /**
     * The provider removed access that some readers may have had to the repository with this native id, such as a
     * team's, and the mirror cannot show whom it reached. Every monitor of it on {@code providerId} stops trusting its
     * listing until the next one.
     */
    void recordRepositoryReadersStale(long providerId, long repositoryNativeId);

    /**
     * The provider reported that the repository turned private or was deleted. That narrows every workspace's monitor of
     * it on {@code providerId}, also when the mirror row already held the repository private or no longer exists.
     */
    void recordRepositoryPrivatized(long repositoryNativeId, long providerId);
}
