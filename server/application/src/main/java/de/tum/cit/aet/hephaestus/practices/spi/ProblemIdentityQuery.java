package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The identity that a comparison matches each problem by: {@code key:} and its key, or a hash of the source and quote
 * of its witness citation. A key is not unique across practices or items, so a reader matches an identity within one
 * practice and item.
 */
public interface ProblemIdentityQuery {
    /** The identity of each of {@code problemIds} in the workspace, keyed by problem id; one without an identity is absent. */
    Map<UUID, String> identities(long workspaceId, Collection<UUID> problemIds);
}
