package de.tum.cit.aet.hephaestus.agent.mentor.chat;

/** Why Heph declines a member before a conversation starts, worded once for every surface. */
public enum MentorRefusal {
    NO_AI("Heph is off for you because you chose No AI. To use Heph, change Your AI choice in Hephaestus."),
    CHOICE_REQUIRED("Choose which AI may handle your work under Your AI choice in Hephaestus before using Heph."),
    UNAVAILABLE(
            "Heph isn't set up for your AI choice in this workspace yet. Ask a workspace owner, or change Your AI choice in Hephaestus.");

    private final String userMessage;

    MentorRefusal(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
