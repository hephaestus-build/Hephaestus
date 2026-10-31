package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Optional;

/** Whether Hephaestus may process one member's work with AI in a workspace, by the rule that admits it. Calls no model. */
public interface MemberAiReadiness {
    /** @return empty when Hephaestus can answer the account in a conversation now */
    Optional<AiRefusalReason> conversationRefusal(long workspaceId, long accountId);

    /**
     * The member AI rule that review admission applies to a review whose only subject is the account. It does not
     * judge the reviewed work, its source or any other subject of the review.
     *
     * @return empty when a model may review the account's work now
     */
    Optional<AiRefusalReason> practiceReviewRefusal(long workspaceId, long accountId);
}
