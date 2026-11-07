package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;

/** Finds the scope whose credential reads a repository on its own provider. */
public interface RepositoryScopeProvider {
    /** The active scopes that monitor this repository of this provider and hold a usable credential. */
    List<Long> getActiveScopesForRepository(long repositoryNativeId, long providerId);
}
