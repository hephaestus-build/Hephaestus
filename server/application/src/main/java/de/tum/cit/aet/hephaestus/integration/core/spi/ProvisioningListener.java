package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Handles provisioning in response to GitHub App installation events.
 */
public interface ProvisioningListener {
    void onInstallationCreated(InstallationData installation);

    void onInstallationDeleted(Long installationId);

    void onRepositoriesAdded(Long installationId, List<RepositorySnapshot> repositories);

    void onRepositoriesRemoved(Long installationId, List<RepositoryRemoval> repositories);

    void onAccountRenamed(Long installationId, @Nullable String oldLogin, String newLogin, Instant observedAt);

    void onInstallationSuspended(Long installationId);

    void onInstallationActivated(Long installationId);

    void onRepositorySelectionChanged(Long installationId, String selection);

    /**
     * Provider-agnostic snapshot of repository metadata from webhook payloads.
     * Contains the minimal information needed to create a Repository entity.
     */
    record RepositorySnapshot(long id, String nameWithOwner, String name, boolean isPrivate, Instant observedAt) {}

    /** A removal keeps the native repository id carried by the payload; null only for a payload that omitted it. */
    record RepositoryRemoval(@Nullable Long nativeId, String nameWithOwner) {}

    record InstallationData(
            Long installationId,
            @Nullable Long accountId,
            @Nullable String accountLogin,
            AccountType accountType,
            @Nullable String avatarUrl,
            List<RepositorySnapshot> repositories) {}

    enum AccountType {
        ORGANIZATION,
        USER,
    }
}
