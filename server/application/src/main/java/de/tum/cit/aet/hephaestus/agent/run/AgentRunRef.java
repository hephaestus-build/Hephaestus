package de.tum.cit.aet.hephaestus.agent.run;

import java.util.UUID;

/** A run of a workspace. It authorizes nothing: only a {@link AgentRunFence} writes for the holder. */
public record AgentRunRef(UUID id, long workspaceId) {}
