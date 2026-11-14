package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.io.Serial;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The agent-sandbox {@code SandboxClaim} objects of the sandbox namespace: the only agent-sandbox object that the server
 * writes. Each method is idempotent, so a reaper on every replica can repeat it. The server also reads the status of
 * each claim's Pod, which has the claim's name, to record how its sandbox ended.
 */
public interface SandboxClaims {

    /** Creates the claim; a claim of that name that exists already is no error. */
    void create(Claim claim);

    /** Deletes the claim and waits for its sandbox to be gone; a claim that is gone is no error. */
    void delete(String name);

    /** The names of the claims that this owner created, in the sandbox namespace. */
    List<String> names();

    /**
     * How the sandbox's Pod ended, as its status shows it; empty when the Pod is gone. The Pod's spec holds the
     * sandbox's token, so this reads nothing from the Pod but the fields of {@link PodEnd}.
     *
     * @throws PodReadException when the API refuses the read or does not answer it
     */
    Optional<PodEnd> podEnd(String name);

    /**
     * The Pod's status fields that tell how a sandbox ended, and nothing else of the Pod.
     *
     * @param phase the Pod's phase, such as {@code Running} or {@code Failed}
     * @param podReason the Pod's own reason, such as {@code Evicted} or {@code DeadlineExceeded}
     * @param terminatedReason the reason of the sandbox container's current termination, else of its last one
     * @param exitCode the exit code of that termination
     * @param restartCount how often the kubelet restarted the sandbox's container
     * @param terminating whether the Pod is being deleted. Its container then stops with the stop signal, which is
     *     not the sandbox's own end, so {@code terminatedReason} names only an earlier termination
     */
    record PodEnd(
            @Nullable String phase,
            @Nullable String podReason,
            @Nullable String terminatedReason,
            @Nullable Integer exitCode,
            int restartCount,
            boolean terminating) {}

    /** A Pod read that failed. It keeps the HTTP status and the error's type, never the API's message. */
    final class PodReadException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String errorType;

        /** @param status the HTTP status of the API's answer, or 0 when no answer came */
        public PodReadException(int status, String errorType) {
            super("The sandbox Pod's status could not be read");
            this.status = status;
            this.errorType = errorType;
        }

        public int status() {
            return status;
        }

        public String errorType() {
            return errorType;
        }
    }

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
