package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import org.springframework.modulith.NamedInterface;

/** Why Hephaestus declines a member before a conversation starts, worded once for every surface. */
@NamedInterface(name = "mentor-turn")
public enum MentorRefusal {
    PERSON_ERASED(AiRefusalReason.PERSON_ERASED, MentorRejectedException.Reason.PERSON_ERASED.userMessage()),
    NO_AI(
            AiRefusalReason.NO_AI,
            "Ask Hephaestus is off for you because you chose No AI. To use Hephaestus, change “Your AI choice” in Hephaestus."),
    CHOICE_REQUIRED(
            AiRefusalReason.CHOICE_REQUIRED,
            "Choose which AI may handle your work before you use Hephaestus. You can do that under “Your AI choice” in Hephaestus."),
    UNAVAILABLE(
            AiRefusalReason.UNAVAILABLE,
            "Hephaestus is not set up for your AI choice in this workspace yet. Ask a workspace owner to set it up, or change “Your AI choice” in Hephaestus.");

    private final AiRefusalReason reason;
    private final String userMessage;

    MentorRefusal(AiRefusalReason reason, String userMessage) {
        this.reason = reason;
        this.userMessage = userMessage;
    }

    /** The code the onboarding state reports for this refusal. */
    public AiRefusalReason reason() {
        return reason;
    }

    public String userMessage() {
        return userMessage;
    }
}
