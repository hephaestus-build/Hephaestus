package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import java.util.Optional;

/** Resolves configured repositories with the bootstrap credential before their workspace exists. */
public interface BootstrapRepositoryLookup {
    IntegrationKind kind();

    Optional<RepositoryIdentity> resolve(String token, String fullPath);
}
