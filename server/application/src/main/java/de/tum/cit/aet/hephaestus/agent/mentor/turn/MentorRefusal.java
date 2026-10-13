package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.workspace.spi.MentorRefusalReason;
import org.springframework.modulith.NamedInterface;

/** Why Heph declines a member before a conversation starts, worded once for every surface. */
@NamedInterface(name = "mentor-turn")
public enum MentorRefusal {
    PERSON_ERASED(MentorRefusalReason.PERSON_ERASED, MentorRejectedException.Reason.PERSON_ERASED.userMessage()),
    NO_AI(
            MentorRefusalReason.NO_AI,
            "Heph is off for you because you chose No AI. To use Heph, change “Your AI choice” in Hephaestus."),
    CHOICE_REQUIRED(
            MentorRefusalReason.CHOICE_REQUIRED,
            "Choose which AI may handle your work before you use Heph. You can do that under “Your AI choice” in Hephaestus."),
    UNAVAILABLE(
            MentorRefusalReason.UNAVAILABLE,
            "Heph is not set up for your AI choice in this workspace yet. Ask a workspace owner to set it up, or change “Your AI choice” in Hephaestus.");

    private final MentorRefusalReason reason;
    private final String userMessage;

    MentorRefusal(MentorRefusalReason reason, String userMessage) {
        this.reason = reason;
        this.userMessage = userMessage;
    }

    /** The code the onboarding state reports for this refusal. */
    public MentorRefusalReason reason() {
        return reason;
    }

    public String userMessage() {
        return userMessage;
    }
}
