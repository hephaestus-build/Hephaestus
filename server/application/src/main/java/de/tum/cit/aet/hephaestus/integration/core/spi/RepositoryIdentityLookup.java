package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Optional;

/** Reads a repository's provider-reported identity without creating or changing its mirror. */
public interface RepositoryIdentityLookup {
    IntegrationKind kind();

    /** External lookup; callers must recheck the connection before a subsequent local write. */
    Optional<RepositoryIdentity> resolve(long workspaceId, String fullPath);

    record RepositoryIdentity(long nativeId, String fullPath) {
        public RepositoryIdentity {
            if (nativeId <= 0) throw new IllegalArgumentException("A repository requires a positive provider ID");
        }
    }
}
