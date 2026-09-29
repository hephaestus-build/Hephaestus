package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.util.Collection;
import java.util.Set;

/**
 * Which works the caller has standing to request a review of. Kinds with no request endpoint are never
 * returned.
 */
public interface ReviewRequestStandingLookup {

    Set<ReviewedWorkId> mayRequest(long workspaceId, Collection<ReviewedWorkId> works);

    record ReviewedWorkId(ArtifactKind kind, long id) {}
}
