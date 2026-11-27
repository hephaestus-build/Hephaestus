package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Optional;
import java.util.UUID;

/** Reads of lasting advice for the mentor and the conversation basis. A {@link AdviceAudience#TEAM} read finds nothing. */
public interface PracticeAdviceReads {
    Optional<AdvicePrior> prior(long workspaceId, long accountId, long practiceId, AdviceAudience audience);

    AdviceRevisionState state(long workspaceId, long accountId, UUID revisionId, AdviceAudience audience);
}
