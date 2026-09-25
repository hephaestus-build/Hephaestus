package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

/** Owns Slack DM chat threads; the Slack integration owns their provider mapping rows. */
@NamedInterface(name = "mentor-chat")
public interface MentorSlackThreadService {
    /**
     * Returns an existing thread in this workspace, or creates a Slack DM thread for the verified actor.
     * A null thread id allocates a new id; callers must authenticate the actor before invoking this method.
     */
    UUID ensureSlackThread(long workspaceId, @Nullable UUID chatThreadId, long developerId);

    /**
     * Deletes this workspace's Slack DM threads and their messages, leaving web threads untouched.
     * Safe to repeat on uninstall redelivery; returns the number of threads deleted.
     */
    int purgeSlackThreads(long workspaceId);
}
