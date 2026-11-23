package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/** @param defaultBranch the repository's default branch, once its provider has named it */
@SourceControlContract
public record MonitoredRepositoryRef(
        long repositoryItemId,
        long monitorId,
        String nameWithOwner,
        @Nullable String defaultBranch) {
    public MonitoredRepositoryRef {
        if (repositoryItemId <= 0 || monitorId <= 0 || nameWithOwner.isBlank())
            throw new IllegalArgumentException("Invalid monitored repository reference");
    }
}
