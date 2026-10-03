package de.tum.cit.aet.hephaestus.agent.mentor.chat;

/** Why Heph declines a member before a conversation starts, worded once for every surface. */
public enum MentorRefusal {
    PERSON_ERASED(
            "Heph cannot process this identity. The personal data of this identity was erased. Contact the instance privacy contact."),
    NO_AI("Heph is off for you because you chose No AI. To use Heph, change Your AI choice in Hephaestus."),
    CHOICE_REQUIRED("Before you use Heph, choose which AI may handle your work. Go to Your AI choice in Hephaestus."),
    UNAVAILABLE(
            "Heph is not set up for your AI choice in this workspace yet. Ask a workspace owner to set it up. You can also change Your AI choice in Hephaestus.");

    private final String userMessage;

    MentorRefusal(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
