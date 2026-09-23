package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import org.jspecify.annotations.Nullable;

/** Shared stale-decision guard for the instance and workspace release paths. */
public final class PracticeReleasePrecondition {

    private PracticeReleasePrecondition() {}

    public static void requireCurrent(
            @Nullable EntityTagPrecondition precondition, PracticeReleaseProposalDTO proposal) {
        if (precondition == null) {
            throw new PracticeReleasePreconditionRequiredException();
        }
        if (!precondition.matchesExact(proposal.etag())) {
            throw new StalePracticeReleaseException();
        }
    }
}
