package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The agent-sandbox {@code SandboxClaim} objects of the sandbox namespace: the only agent-sandbox object that the server
 * writes. Each method is idempotent, so a reaper on every replica can repeat it.
 */
public interface SandboxClaims {

    /** Creates the claim; a claim of that name that exists already is no error. */
    void create(Claim claim);

    /** Deletes the claim, and with it its sandbox; a claim that is gone is no error. */
    void delete(String name);

    /** The names of the claims that this owner created, in the sandbox namespace. */
    List<String> names();

    /**
     * @param shutdownAt when the controller deletes the sandbox itself, if nothing deleted it before
     * @param env the claim's environment for the sandbox's one container
     */
    record Claim(String name, UUID sandboxId, AgentSandboxKind kind, Instant shutdownAt, Map<String, String> env) {
        public Claim {
            env = Map.copyOf(env);
        }
    }
}
