package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import org.springframework.modulith.NamedInterface;

/**
 * Whether Heph can answer in a workspace: the workspace is active and one of its enabled Heph model
 * bindings resolves to an available model. Admins turn Heph on and off through those bindings.
 * Implementations fail closed.
 */
@NamedInterface(name = "mentor-chat")
public interface MentorReadinessQuery {
    boolean isReady(long workspaceId);
}
