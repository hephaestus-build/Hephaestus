package de.tum.cit.aet.hephaestus.agent.attempt;

import java.time.Instant;
import java.util.UUID;

/**
 * The credential of one sandbox. It names the sandbox, its workspace and its subject. The sandbox's row decides
 * whether it is still live, so a deleted sandbox's token stops at once, before it expires.
 *
 * @param subjectKey the concurrency key of the runs that the sandbox serves, such as {@code review:<id>}
 */
public record SandboxJwt(UUID sandboxId, long workspaceId, String subjectKey, String jti, Instant expiresAt) {}
