package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

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
     * narrowed every workspace's monitor of the repository; this also ends a public answer from an overlapping read.
     * A listing of readers does not depend on this answer and stays.
     */
    void recordRepositoryNotPublic(ReadersRead read);

    /** What one listing of a repository's readers on the provider answered. */
    sealed interface ReaderListing {
        /** Every reader, by the provider's native user id; the listing reached its end and checked its count. */
        record Listed(Set<Long> readerNativeIds) implements ReaderListing {
            public Listed {
                readerNativeIds = Set.copyOf(readerNativeIds);
            }
        }

        /** The listing stopped before its end, with {@link ProofReason#RATE_LIMITED} or {@link ProofReason#LISTING_INCOMPLETE}. */
        record Stopped(ProofReason reason) implements ReaderListing {}

        /** The provider cannot list every reader, such as for a GitHub internal repository. */
        record NotListable() implements ReaderListing {}
    }

    /** Whether this monitor's readers need a listing now: they are not proven by a listing, or its proof is older than {@code renewAfter}. */
    boolean readersListingDue(Long syncTargetId, Instant now, Duration renewAfter);

    /**
     * Records what a listing that began as {@code read} answered. A complete listing replaces the monitor's readers and
     * proves them only if no other answer applied since the read began; a stopped or impossible listing always applies.
     */
    void recordRepositoryReaders(ReadersRead read, ReaderListing listing);

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

    /** This monitor's provider cannot list every reader, so its readers stay unproven. */
    void recordReadersNotListable(Long syncTargetId);

    /**
     * The provider reported that the repository turned private or was deleted. That narrows every workspace's monitor of
     * it on {@code providerId}, also when the mirror row already held the repository private or no longer exists.
     */
    void recordRepositoryPrivatized(long repositoryNativeId, long providerId);
}
