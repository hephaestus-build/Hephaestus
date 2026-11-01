package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The conversation copies of feedback that Heph turns claim, show and settle. */
public interface ConversationClaims {
    /**
     * The conversation copies a Heph turn claimed from {@code since} to {@code until} that have no receipt yet, on the
     * database clock that stamps both a claim and the start of the attempt that made it.
     */
    List<CopyWriteFence> unsettledConversationClaims(long workspaceId, UUID mentorTurnId, Instant since, Instant until);

    /**
     * Claims the conversation copy of READY conversation feedback for the running attempt of a Heph turn of its
     * developer. A repeat by the same attempt returns the fence it already holds; an ended attempt claims nothing.
     */
    Optional<CopyWriteFence> claimConversationCopy(long workspaceId, UUID feedbackId, UUID mentorTurnId, int attempt);

    void recordConversationReceipt(long workspaceId, CopyWriteFence fence, ConversationCopyReceipt receipt);
}
