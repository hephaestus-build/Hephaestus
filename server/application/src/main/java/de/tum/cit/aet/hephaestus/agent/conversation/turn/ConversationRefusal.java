package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.core.web.LabelDTO;
import de.tum.cit.aet.hephaestus.core.web.MentoringCopy;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import org.springframework.modulith.NamedInterface;

/** Why Hephaestus declines a member before a conversation starts, worded once for every surface. */
@NamedInterface(name = "conversation-turn")
public enum ConversationRefusal {
    PERSON_ERASED(AiRefusalReason.PERSON_ERASED),
    NO_AI(AiRefusalReason.NO_AI),
    CHOICE_REQUIRED(AiRefusalReason.CHOICE_REQUIRED),
    UNAVAILABLE(AiRefusalReason.UNAVAILABLE);

    private final AiRefusalReason reason;

    ConversationRefusal(AiRefusalReason reason) {
        this.reason = reason;
    }

    /** The code the onboarding state reports for this refusal. */
    public AiRefusalReason reason() {
        return reason;
    }

    public LabelDTO label() {
        return this == PERSON_ERASED
                ? ConversationRejectedException.Reason.PERSON_ERASED.label()
                : MentoringCopy.label(MentoringCopy.code("conversation.refused.", this));
    }

    public String userMessage() {
        return label().text();
    }
}
