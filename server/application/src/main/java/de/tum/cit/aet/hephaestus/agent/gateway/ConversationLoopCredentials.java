package de.tum.cit.aet.hephaestus.agent.gateway;

/** Checks the credential of the release's conversation loop, which may only pull conversation assignments. */
public interface ConversationLoopCredentials {

    /** The audience of the loop's projected service account token. */
    String AUDIENCE = "hephaestus-conversation-loop";

    /**
     * Whether {@code token} is a live token of the release's loop service account for {@link #AUDIENCE}. A check that
     * fails or times out answers {@code false}.
     */
    boolean authenticates(String token);
}
