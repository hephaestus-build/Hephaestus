package de.tum.cit.aet.hephaestus.agent.run;

import java.util.UUID;

/** The scope and id of a run. Only a {@link AgentRunFence} writes for its holder. */
public record AgentRunRef(UUID id, long workspaceId) {
    /** The instance scope on the gateway and in native query parameters. Its stored workspace is NULL. */
    public static final long INSTANCE_SCOPE = 0;
}
