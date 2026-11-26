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

    /** Captures the monitor's generation and database time before the provider read. */
    ReadersRead beginReadersProof(Long scopeId, Long syncTargetId, long repositoryNativeId, long providerId);

    RepositoryReads beginRepositoryReads(Long scopeId, long providerId);

    void recordRepositoryLoss(ReadersRead read);
}
