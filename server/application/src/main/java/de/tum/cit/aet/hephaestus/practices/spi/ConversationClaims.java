package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The conversation copies of feedback that Hephaestus turns claim, show and settle. */
public interface ConversationClaims {
    /**
     * A claim that has no receipt yet, and the account of the person the feedback is for. The claim refused feedback of
     * anyone but the turn's account, and an account absorption moves the feedback with the turn, so this account owns
     * the answer that settles the claim.
     */
    record Unsettled(CopyWriteFence fence, long personAccountId) {}

    /**
     * The conversation copies a Hephaestus turn claimed from {@code since} to {@code until} that have no receipt yet, on the
     * database clock that stamps both a claim and the start of the attempt that made it.
     */
    List<Unsettled> unsettledConversationClaims(
            long workspaceId, UUID conversationTurnId, Instant since, Instant until);

    /**
     * Claims the conversation copy of READY conversation feedback for the running attempt of a Hephaestus turn of its
     * developer. A repeat by the same attempt returns the fence it already holds; an ended attempt claims nothing.
     * Feedback of anyone but {@code accountId}, the turn's account, claims nothing. The claim joins the caller's
     * transaction, which may already hold the turn, and binds the turn's account to it. It locks the account's write
     * fence, then the turn, then the feedback, then the feedback's copy: a caller that holds the turn holds the account
     * first.
     */
    Optional<CopyWriteFence> claimConversationCopy(
            long workspaceId, long accountId, UUID feedbackId, UUID conversationTurnId, int attempt);

    void recordConversationReceipt(long workspaceId, CopyWriteFence fence, ConversationCopyReceipt receipt);
}
