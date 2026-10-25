package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Sandboxes that stay up and run one command at a time on request, as Heph's turn sandboxes do. A sandbox has the
 * hardened configuration of a practice review, no network, no credential and no environment from this worker. Its
 * {@code /workspace} and {@code /tmp} are memory file systems of the sandbox itself, so removing the sandbox removes
 * every file in it.
 *
 * <p>Two kinds of work reach a sandbox. The worker stages its own archives into {@code /workspace} ({@link #stage}),
 * and {@link #SANDBOX_USER} cannot write what was staged. Every command ({@link #exec}) is untrusted and runs as
 * {@link #SANDBOX_USER}; {@link #cancelCommands} ends what such commands leave running.
 */
public interface ExecSandboxes {

    /** The unprivileged user of the image, which runs the model's commands. */
    String SANDBOX_USER = "1000";

    /**
     * Creates and starts an empty sandbox.
     *
     * @param sandboxId the caller's id of the sandbox, recorded on it so that a cleanup can find it later
     * @return the runtime's id of the sandbox
     * @throws SandboxException when the sandbox cannot start
     */
    String create(String sandboxId, ResourceLimits limits);

    /**
     * Runs one command in {@code /workspace} as {@link #SANDBOX_USER}, with no input, and waits for it, at most for
     * {@code timeout}. A command that runs longer is left running: the caller cancels it ({@link #cancelCommands}) or
     * removes the sandbox.
     *
     * @throws SandboxException when the sandbox is gone or the runtime fails
     */
    Execution exec(String sandbox, Command command);

    /**
     * Unpacks one of the worker's archives into {@code /workspace}. After it, {@link #SANDBOX_USER} can write neither
     * {@code /workspace} nor anything that the archive wrote.
     *
     * @throws SandboxException when the sandbox is gone, the archive does not unpack, or the staging does not end within
     *     its timeout. The folder can then hold part of the archive, so the caller removes the sandbox.
     */
    void stage(String sandbox, Staging staging);

    /**
     * Ends every process that commands of {@link #SANDBOX_USER} started, also a process that left the command's process
     * group. The sandbox keeps running and takes the next staging and command.
     *
     * @throws SandboxException when the sandbox is gone or the runtime fails
     */
    void cancelCommands(String sandbox);

    /** Removes the sandbox and every file in it. Best effort: a sandbox that is already gone is no error. */
    void remove(String sandbox);

    /** The sandboxes of this installation, of every worker. */
    List<Listed> list();

    /**
     * The id this worker records on the sandboxes it creates. It stays the same when the worker restarts, so its next
     * start removes what a crash left. Null when the worker cannot name itself: it then starts no sandbox.
     */
    @Nullable
    String workerInstance();

    /**
     * Whether the runtime starts a sandbox now. A runtime that proves its isolation first, as Kubernetes does, answers
     * false until a proof passed, so that callers wait rather than fail.
     */
    default boolean ready() {
        return true;
    }

    /**
     * @param argv the command, run without a shell
     * @param outputLimit how many bytes of each of stdout and stderr are kept; the rest is dropped
     */
    record Command(List<String> argv, Duration timeout, int outputLimit) {
        public Command {
            argv = List.copyOf(argv);
        }
    }

    /**
     * A tar archive that the worker stages. The sandbox reads exactly {@code size} bytes of {@code archive}; the caller
     * keeps the stream and closes it.
     */
    sealed interface Staging permits Add, Replace {
        InputStream archive();

        long size();

        /** How long the staging may take. */
        Duration timeout();
    }

    /**
     * Adds the archive's entries to {@code /workspace}, over what is there.
     *
     * @param names the entries directly in {@code /workspace} that the archive writes, each one plain name
     */
    record Add(List<String> names, InputStream archive, long size, Duration timeout) implements Staging {
        public Add {
            names = List.copyOf(names);
            names.forEach(ExecSandboxes::requireName);
            requireSize(size);
        }
    }

    /**
     * Replaces one directory in {@code /workspace} with the archive's entries. The directory and its parents are made
     * where they are absent.
     *
     * @param directory the directory, relative to {@code /workspace}; never {@code /workspace} itself
     */
    record Replace(String directory, InputStream archive, long size, Duration timeout) implements Staging {
        public Replace {
            for (String name : directory.split("/", -1)) {
                requireName(name);
            }
            requireSize(size);
        }
    }

    /** What one command did. Each output keeps at most the command's limit. */
    final class Execution {
        private final @Nullable Integer exitCode;
        private final byte[] stdout;
        private final byte[] stderr;
        private final boolean truncated;

        /**
         * @param exitCode null when the command still ran at the timeout
         * @param truncated whether stdout or stderr was longer than the limit
         */
        public Execution(@Nullable Integer exitCode, byte[] stdout, byte[] stderr, boolean truncated) {
            this.exitCode = exitCode;
            this.stdout = stdout.clone();
            this.stderr = stderr.clone();
            this.truncated = truncated;
        }

        public @Nullable Integer exitCode() {
            return exitCode;
        }

        public byte[] stdout() {
            return stdout.clone();
        }

        public byte[] stderr() {
            return stderr.clone();
        }

        public boolean truncated() {
            return truncated;
        }

        public boolean timedOut() {
            return exitCode == null;
        }
    }

    /**
     * @param workerGone whether the worker that created the sandbox is positively gone; an unknown state is not gone
     */
    record Listed(String id, String sandboxId, String workerInstance, boolean workerGone) {}

    /** A staged path never leaves {@code /workspace}: each of its names is one plain name. */
    private static void requireName(String name) {
        if (name.isEmpty()
                || name.equals(".")
                || name.equals("..")
                || name.indexOf('/') >= 0
                || name.indexOf('\0') >= 0) {
            // The path stays out of the message, as staged file names stay out of every sandbox log.
            throw new IllegalArgumentException("A staged path must be a plain path in /workspace");
        }
    }

    private static void requireSize(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("An archive cannot have a negative size");
        }
    }
}
