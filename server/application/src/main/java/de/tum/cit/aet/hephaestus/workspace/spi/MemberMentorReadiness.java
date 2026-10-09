package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Optional;

/** Whether Heph answers one member in a workspace, by the rule that admits each question. Calls no model. */
public interface MemberMentorReadiness {
    /** @return empty when Heph can answer the developer now */
    Optional<MentorRefusalReason> refusal(long workspaceId, long developerId);
}
