package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Records what the provider proved about a monitored repository's availability and readers. A metadata confirmation
 * applies only if no answer applied since its read began and the monitor still names the same repository.
 */
public interface RepositoryReadersProvider {
    interface Observations {
        RepositoryReads capture(long workspace, long provider, @Nullable Long monitor);

        void recordLoss(ReadersRead read);
    }

    record Credential(long connectionId, long generation, long lifecycleGeneration) {}

    /** One provider read of a repository, begun for one monitor before it asked the provider. */
    record ReadersRead(
            Long scopeId,
            @Nullable Long syncTargetId,
            long repositoryNativeId,
            long providerId,
            long generation,
            Instant requestedAt,
            @Nullable Credential credential) {
        public ReadersRead(Long syncTargetId, long repositoryNativeId, long providerId,
                long generation, Instant requestedAt) {
            this(0L, syncTargetId, repositoryNativeId, providerId, generation, requestedAt, null);
        }

        public ReadersRead(
                Long scopeId,
                @Nullable Long syncTargetId,
                long repositoryNativeId,
                long providerId,
                long generation,
                Instant requestedAt) {
            this(scopeId, syncTargetId, repositoryNativeId, providerId, generation, requestedAt, null);
        }
    }

    /** The monitors and database time captured before a request that can return several repositories. */
    record RepositoryReads(
            Long scopeId,
            long providerId,
            Instant requestedAt,
            Map<Long, ReadersRead> monitors,
            @Nullable Credential credential) {
        public RepositoryReads(Long scopeId, long providerId, Instant requestedAt, Map<Long, ReadersRead> monitors) {
            this(scopeId, providerId, requestedAt, monitors, null);
        }

        public ReadersRead forRepository(long nativeId) {
            var monitored = monitors.get(nativeId);
            return monitored != null
                    ? monitored
                    : new ReadersRead(scopeId, null, nativeId, providerId, -1, requestedAt, credential);
        }
    }

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
