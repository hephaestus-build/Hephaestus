package de.tum.cit.aet.hephaestus.agent.gateway;

import java.security.Principal;

/** Release-only assignment authority. It cannot stand in for a workspace's attempt token. */
public record ConversationLoopPrincipal() implements Principal {
    @Override
    public String getName() {
        return "conversation-loop";
    }
}
