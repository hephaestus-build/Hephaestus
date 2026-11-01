package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Runs sandboxes on one container runtime. The attempt protocol (the gateway session, the environment, the deadlines,
 * cancellation, the result and its metrics) is the runner's, once for every runtime; a backend only starts, observes,
 * stops and removes what it runs.
 *
 * <p>A sandbox runs two processes of one image, in order. The initializer fills {@code /workspace} from the worker's
 * gateway and may write it. The runtime then runs with {@code /workspace} read-only. Each backend guarantees, for both:
 * the image's unprivileged user {@code 1000}, a read-only root file system, no capability, no privilege escalation,
 * the memory, CPU and process limits of the launch, no credential and no environment but the launch's, and no network
 * destination but the worker's gateway.
 *
 * <p>A sandbox is known by the name that its runtime records ({@link Handle}), not by the memory of the process that
 * launched it: {@link #list}, {@link #kill} and {@link #remove} reach it from any worker of the installation that
 * shares its runtime, also after a restart. Only {@link #observe}, {@link #running} and {@link #transcript} need the
 * process that launched it.
 *
 * <p>Every method may be called from any thread; {@link #kill} in particular while another thread observes.
 */
public interface SandboxBackend {

    /**
     * Whether the runtime starts a sandbox now. A runtime that proves its isolation first, as Kubernetes does, answers
     * false until a proof passed, so that callers claim no work rather than fail it.
     */
    default boolean ready() {
        return true;
    }

    /**
     * Creates the sandbox's isolation and storage and starts its initializer. It returns once the backend accepted the
     * sandbox, before the initializer ended.
     *
     * @throws SandboxException when the backend refuses the sandbox
     * @throws SandboxInfrastructureException when the runtime fails in a way that can heal
     */
    Handle launch(Launch launch);

    /**
     * Waits until the sandbox reached the stage, or until the deadline. The runtime starts only after the initializer
     * succeeded, and never after a {@link #kill} by the launching process; a backend may start it when it is first
     * observed. A kill by another process ends what runs: a backend whose runtime is a part of one workload, as a
     * Kubernetes Pod is, starts nothing after it either.
     *
     * <ul>
     *   <li>{@link Stage#INITIALIZED}: the initializer ended, with its exit code.
     *   <li>{@link Stage#STARTED}: the runtime runs; an exit code when it already ended.
     *   <li>{@link Stage#ENDED}: the runtime ended, with its exit code; none when it never ran.
     * </ul>
     *
     * When the deadline passes first, the backend stops the stage's process (as {@link #kill}) and reports it timed out,
     * with the exit code that the stop left, if any.
     */
    Observation observe(Handle handle, Stage stage, Instant deadline);

    /** Whether the sandbox's runtime runs now. */
    boolean running(Handle handle);

    /**
     * The container's output, read once it ended: whole, or past the backend's bound its beginning and its end with a
     * line between them that says how much is missing, never silently cut. Empty when it cannot be read. A failed
     * review's transcript is its only diagnostic, so the runner keeps it in the result.
     */
    String transcript(Handle handle, Container container);

    /**
     * Ends the sandbox's processes, with their grace period; the sandbox keeps its transcripts until it is removed. A
     * sandbox that already ended, or is gone, is no error.
     */
    void kill(Handle handle);

    /**
     * Removes the sandbox and everything it holds. A sandbox that is gone is no error.
     *
     * @throws SandboxException when a part of it stays; a later removal tries again
     */
    void remove(Handle handle);

    /**
     * The sandboxes of this installation that the runtime holds, whichever worker launched them, for the removal of
     * those that no attempt or worker uses any more.
     *
     * @throws SandboxException when the runtime cannot list them; nothing may then count as abandoned
     */
    List<Listed> list();

    enum Kind {
        /** A practice review attempt: it runs to its own end within its deadlines. */
        REVIEW
    }

    enum Stage {
        INITIALIZED,
        STARTED,
        ENDED
    }

    enum Container {
        INITIALIZER,
        RUNTIME
    }

    /**
     * @param id the review job the sandbox serves
     * @param attempt the claimed job's attempt; null for a caller that holds no claim
     * @param command the runtime's command; the initializer's is {@code SandboxLayout.INITIALIZER_COMMAND}
     * @param deadline when a review attempt's upload window closes. A backend whose runtime enforces a deadline itself
     *     ends the sandbox then, also when its worker is gone: a Kubernetes Job enforces that deadline
     * @param environment the environment of both processes, built for the gateway address the backend gives the sandbox
     */
    record Launch(
            Kind kind,
            UUID id,
            @Nullable Integer attempt,
            String image,
            List<String> command,
            ResourceLimits limits,
            @Nullable Instant deadline,
            Environment environment) {
        public Launch {
            command = List.copyOf(command);
        }
    }

    /** The sandbox's environment for its gateway, such as {@code http://10.0.0.2:8081}. */
    @FunctionalInterface
    interface Environment {
        Map<String, String> at(String gateway);
    }

    /**
     * A sandbox, by its Kubernetes Job or Pod name.
     */
    record Handle(String id) {}

    /**
     * A sandbox as {@link #list} finds it.
     *
     * @param id the review job it serves
     * @param attempt the claimed attempt it runs, when it recorded one
     * @param created when its newest part was created; null when the runtime does not say, which counts as now
     * @param creatorGone whether the worker that launched it is positively gone
     */
    record Listed(
            Handle handle,
            Kind kind,
            UUID id,
            @Nullable Integer attempt,
            @Nullable Instant created,
            boolean creatorGone) {}

    /**
     * @param timedOut whether the deadline passed before the stage
     * @param exitCode the exit code of the stage's process, when it ended
     */
    record Observation(boolean timedOut, @Nullable Integer exitCode) {}
}
