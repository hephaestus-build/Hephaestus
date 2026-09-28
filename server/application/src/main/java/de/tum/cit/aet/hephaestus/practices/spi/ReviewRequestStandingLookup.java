package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Which pieces of work the current caller could ask for a review of, right now.
 *
 * <p>A developer surface offers "Review this now" beside work the request endpoint would accept, and
 * nowhere else: a button that always answers 403 is worse than no button. The rule itself belongs to the
 * request front door, so this port asks the question rather than restating the answer — the practices
 * module must not import the agent module's authority to find out.
 *
 * <p>Batched, because a list of runs asks about a page of work at once. A kind with no front door — a
 * conversation thread, a document — is simply never in the answer.
 */
public interface ReviewRequestStandingLookup {

    /** The subset of {@code works} this caller has standing on; empty when they have none at all. */
    Set<ReviewedWorkId> mayRequest(long workspaceId, Collection<ReviewedWorkId> works);

    /** One piece of reviewed work, by the two things that address it. */
    record ReviewedWorkId(@NonNull ArtifactKind kind, long id) {}
}
