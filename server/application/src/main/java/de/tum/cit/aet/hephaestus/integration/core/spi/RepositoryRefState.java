package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Optional;
import java.util.UUID;

/** Last complete provider-ref observation, scoped to the monitored repository. */
public interface RepositoryRefState {
    UUID begin(long workspaceId, long repositoryId);

    void record(long workspaceId, long repositoryId, UUID readId, String fingerprint);

    Optional<String> current(long workspaceId, long repositoryId);

    void invalidate(long nativeId, long providerId);
}
