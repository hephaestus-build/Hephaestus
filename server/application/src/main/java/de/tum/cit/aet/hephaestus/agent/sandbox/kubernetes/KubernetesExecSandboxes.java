package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.RequestConfig;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.TtyExecOutputErrorable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExecSandboxes} as Kubernetes Pods in the sandbox namespace, whose chart policy denies a turn Pod all traffic.
 * A sandbox Pod has two containers of the agent image that share the workspace volume and nothing else
 * ({@link KubernetesPods#turnPod}):
 *
 * <ul>
 *   <li>{@code sandbox}, as {@link ExecSandboxes#SANDBOX_USER}, mounts {@code /workspace} read-only and runs every
 *       command and the cancellation of commands. Kubernetes runs an exec as its container's user, so this container
 *       holds nothing that user may not touch.
 *   <li>{@code stager}, as a user of its own, mounts {@code /workspace} writable and runs only the two staging scripts
 *       of this class, with their typed arguments. No other command reaches it.
 * </ul>
 *
 * <p>The sandbox user cannot write what was staged: the mount is read-only. Every exec starts through
 * {@link KubernetesPods#RLIMITS}, so a command has the sandbox's {@code nofile} and {@code core} limits although it is
 * no child of the container's first process. Cancellation runs {@code kill -KILL -1} as the sandbox user in the
 * sandbox container's PID namespace: it reaches every process of that user there, a detached one too, and spares the
 * namespace's init process, which reaps them ({@link KubernetesPods#SANDBOX_INIT}).
 *
 * <p>The client puts an exec's arguments into the URL of its WebSocket and logs that URL when the upgrade fails; the
 * shipped {@code logging.level} closes that logger, and this class logs the failure by class and status only.
 *
 * <p>The worker that uses a sandbox removes it, and the workers remove those of a worker that is gone
 * ({@code TurnSandboxes}); the backend-neutral {@code SandboxReconciler} does not touch turn sandboxes.
 */
public class KubernetesExecSandboxes implements ExecSandboxes, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KubernetesExecSandboxes.class);

    private static final String HOSTNAME = "sandbox";

    /** Scheduling, an image pull and the start of both containers. */
    private static final Duration START_TIME = Duration.ofMinutes(2);

    /**
     * Argument: the archive's length. The runtime does not close stdin after the archive, so {@code head} ends the input
     * at its length.
     */
    static final String ADD_SCRIPT = """
            set -e
            head -c "$1" | tar -x --no-same-owner --no-same-permissions -C /workspace
            """;

    /** Arguments: the archive's length and the target directory, which the archive replaces. */
    static final String REPLACE_SCRIPT = """
            set -e
            n=$1; t=$2
            rm -rf -- "$t"
            mkdir -p -- "$t"
            head -c "$n" | tar -x --no-same-owner --no-same-permissions -C "$t"
            """;

    /** {@code kill -1} spares the calling process and the init process of the PID namespace. */
    static final List<String> CANCEL_COMMAND = List.of("sh", "-c", "kill -KILL -1");

    private static final Duration CANCEL_TIME = Duration.ofSeconds(5);
    private static final int CANCEL_OUTPUT_LIMIT = 1024;
    private static final int STAGING_OUTPUT_LIMIT = 4096;

    private final KubernetesClient client;
    private final KubernetesPods pods;
    private final KubernetesWorker worker;
    private final String image;
    private final SandboxExits exits;
    private final KubernetesLiveWorkers liveWorkers;

    /** Opens each call's exec, so that the call can stop waiting for it. */
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    public KubernetesExecSandboxes(
            KubernetesClient client,
            KubernetesPods pods,
            KubernetesWorker worker,
            String image,
            KubernetesLiveWorkers liveWorkers,
            SandboxExits exits) {
        this.liveWorkers = liveWorkers;
        this.client = client;
        this.pods = pods;
        this.worker = worker;
        this.image = image;
        this.exits = exits;
    }

    /** The worker's Pod name: a restarted worker container keeps it, so its next start removes what a crash left. */
    @Override
    public String workerInstance() {
        return KubernetesWorker.labelValue(worker.properties().podName());
    }

    @Override
    public boolean ready() {
        return worker.isolated();
    }

    @Override
    public String create(String sandboxId, ResourceLimits limits) {
        if (!sandboxId.matches(KubernetesSandboxProperties.DNS_LABEL)) {
            throw new IllegalArgumentException("A turn sandbox id must be a DNS label");
        }
        worker.requireIsolation();
        String name = "hephaestus-turn-" + sandboxId;
        Map<String, String> labels = worker.labels(KubernetesWorker.KIND_TURN);
        labels.put(KubernetesWorker.SANDBOX_ID, sandboxId);
        pods.create(pods.turnPod(new KubernetesPods.Workload(name, labels, image, HOSTNAME), limits));
        KubernetesPods.Waited started;
        try {
            started = pods.waitFor(
                    name,
                    candidate -> bothRunning(candidate)
                            || KubernetesPods.terminal(candidate)
                            || KubernetesPods.fatalWaiting(candidate) != null,
                    START_TIME);
        } catch (SandboxException e) {
            remove(name);
            throw e;
        }
        Pod pod = started.pod();
        if (started.timedOut() || pod == null || !bothRunning(pod)) {
            String waiting = pod == null ? null : KubernetesPods.fatalWaiting(pod);
            remove(name);
            throw new SandboxInfrastructureException(
                    "Could not start a turn sandbox" + (waiting == null ? "" : ": " + waiting));
        }
        return name;
    }

    @Override
    public Execution exec(String sandbox, Command command) {
        return run(sandbox, KubernetesPods.SANDBOX, command.argv(), null, command.timeout(), command.outputLimit());
    }

    @Override
    public void stage(String sandbox, Staging staging) {
        List<String> argv = new ArrayList<>(List.of("sh", "-c"));
        switch (staging) {
            case Add add -> argv.addAll(List.of(ADD_SCRIPT, "add", Long.toString(add.size())));
            case Replace replace ->
                argv.addAll(List.of(
                        REPLACE_SCRIPT,
                        "replace",
                        Long.toString(replace.size()),
                        KubernetesPods.WORKSPACE + "/" + replace.directory()));
        }
        Execution staged = run(sandbox, KubernetesPods.STAGER, argv, staging, staging.timeout(), STAGING_OUTPUT_LIMIT);
        Integer exit = staged.exitCode();
        if (exit == null || exit != 0) {
            // The exit is the classification; the sandbox's stderr can name the staged files and stays out.
            log.warn("Staging into a turn sandbox exited with {}", exit);
            throw new SandboxException("Staging into the turn sandbox failed: exit " + exit);
        }
    }

    /**
     * {@code kill} exits 0 when it signalled processes and 1 when no process of the user was left to end: either
     * confirms the cancellation. Any other outcome does not: no exit means whether the kill ran, or still runs later, is
     * unknown; -1 is the client's value for a failure status, such as an exec process the runtime could not start; 126
     * and 127 mean the shell could not run {@code kill}. The caller must then not run another command in the sandbox.
     *
     * @throws SandboxException when the cancellation was not confirmed
     */
    @Override
    public void cancelCommands(String sandbox) {
        Execution cancelled =
                run(sandbox, KubernetesPods.SANDBOX, CANCEL_COMMAND, null, CANCEL_TIME, CANCEL_OUTPUT_LIMIT);
        Integer exit = cancelled.exitCode();
        if (exit == null || (exit != 0 && exit != 1)) {
            log.warn("Cancelling the commands of a turn sandbox ended with {}", exit);
            throw new SandboxException("The cancellation of a turn sandbox's commands was not confirmed: exit " + exit);
        }
    }

    /**
     * Runs one exec and waits for its exit status at most {@code timeout}. A command that opened and still runs then is
     * left running, as {@link ExecSandboxes#exec} says; closing the stream does not end it.
     *
     * <p>Every request of the call is bounded by what is left of the budget and never retried ({@link #bounded}), as
     * the client pads each attempt's deadline and would retry a failed request. The exec opens on a thread of its own,
     * so the call stops waiting for it at the deadline or at an interrupt; an exec that opens after that is closed as
     * it opens, as nothing reads it.
     *
     * <p>The client reports failures with whatever unchecked exception its transport raised, a failed WebSocket
     * handshake included. So the whole call is one boundary: a {@link SandboxException} leaves it as it is, any other
     * {@link RuntimeException} leaves it as a {@link SandboxException} ({@link #broke}). An {@link Error} is not caught.
     *
     * @throws SandboxException when the exec did not open before the deadline, or opening it failed, or the client
     *     failed in any other way: the API server may have started the command all the same, now or later, so the
     *     caller must not use the sandbox again
     */
    private Execution run(
            String sandbox,
            String container,
            List<String> argv,
            @Nullable Staging staging,
            Duration timeout,
            int outputLimit) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Bounded stdout = new Bounded(outputLimit);
        Bounded stderr = new Bounded(outputLimit);
        if (timeout.toMillis() <= 0) {
            return new Execution(null, stdout.bytes(), stderr.bytes(), false);
        }
        try {
            CompletableFuture<ExecWatch> opening;
            try {
                opening = CompletableFuture.supplyAsync(
                        () -> {
                            ContainerResource resource = bounded(deadline)
                                    .pods()
                                    .inNamespace(worker.namespace())
                                    .withName(sandbox)
                                    .inContainer(container);
                            // An input buffer of one byte: the client then sends each write of the copy as it comes,
                            // one send and at most one wait on its queue per write, with a read of the archive before
                            // the next. A larger buffer can send a buffered part and the new write within one write,
                            // two waits that a single interrupt of the copy cannot both end (see send).
                            TtyExecOutputErrorable streams = staging == null ? resource : resource.redirectingInput(1);
                            return streams.writingOutput(stdout)
                                    .writingError(stderr)
                                    .exec(KubernetesPods.limited(argv).toArray(String[]::new));
                        },
                        calls);
            } catch (RejectedExecutionException e) {
                throw new SandboxException("This worker no longer runs turn sandbox commands", e);
            }
            ExecWatch watch;
            try {
                watch = opening.get(Math.max(1, left(deadline)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                closeOnceOpen(opening);
                throw new SandboxException(
                        "A turn sandbox command did not open before its deadline and may still start", e);
            } catch (InterruptedException e) {
                closeOnceOpen(opening);
                Thread.currentThread().interrupt();
                throw new SandboxException("Interrupted while a turn sandbox command opened", e);
            } catch (ExecutionException e) {
                if (left(deadline) <= 0) {
                    throw new SandboxException(
                            "A turn sandbox command did not open before its deadline and may still start", e);
                }
                throw failed(sandbox, deadline, "Could not run a command in a turn sandbox", e);
            }
            try (watch) {
                if (staging != null) {
                    send(sandbox, watch, staging, deadline);
                }
                Integer exit;
                try {
                    exit = watch.exitCode().get(Math.max(1, left(deadline)), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    exit = null;
                } catch (ExecutionException e) {
                    if (left(deadline) <= 0) {
                        exit = null;
                    } else {
                        throw failed(sandbox, deadline, "A command in a turn sandbox failed", e);
                    }
                }
                if (exit == null && left(deadline) > 0) {
                    // The stream closed without an exit status before the timeout.
                    throw failed(sandbox, deadline, "A command in a turn sandbox lost its stream", null);
                }
                return new Execution(exit, stdout.bytes(), stderr.bytes(), stdout.truncated || stderr.truncated);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxException("Interrupted while a turn sandbox ran a command", e);
            }
        } catch (SandboxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw broke(deadline, e);
        }
    }

    /** An exec that opens after its call stopped waiting: nothing reads it. */
    private static void closeOnceOpen(CompletableFuture<ExecWatch> opening) {
        var unused = opening.thenAccept(ExecWatch::close);
    }

    /**
     * A failure of the client that no step of the call classified. It is logged by class only, as {@link #failed}
     * logs: the client's message can carry the URL and with it the command.
     */
    private static SandboxException broke(long deadline, RuntimeException cause) {
        boolean late = left(deadline) <= 0;
        log.atWarn()
                .addKeyValue("event.name", "sandbox.exec.failed")
                .addKeyValue("sandbox.exec.error_type", cause.getClass().getName())
                .addKeyValue(
                        "sandbox.exec.http_status", cause instanceof KubernetesClientException api ? api.getCode() : 0)
                .addKeyValue("sandbox.exec.late", late)
                .log("A turn sandbox command failed in its client");
        if (late) {
            return new SandboxException(
                    "A turn sandbox command did not end before its deadline and may still start or run", cause);
        }
        return new SandboxInfrastructureException("A turn sandbox command failed in its client", cause);
    }

    /** The milliseconds left until {@code deadline}, a {@link System#nanoTime} value; none or fewer once it passed. */
    private static long left(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    /**
     * The shared client with each request bounded by what is left of the budget and never retried, the client's own
     * way to scope request settings. The view shares the shared client's transports.
     */
    private KubernetesClient bounded(long deadline) {
        RequestConfig requests = new RequestConfigBuilder(
                        client.getConfiguration().getRequestConfig())
                .withRequestTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, left(deadline))))
                .withRequestRetryBackoffLimit(0)
                .build();
        return client.newClient(requests).adapt(KubernetesClient.class);
    }

    /**
     * Writes exactly the archive's size; the caller keeps and closes the archive. The archive is read only before the
     * deadline. The client's input stream reports a failed WebSocket with its own unchecked exception, not with an
     * {@link IOException}.
     *
     * <p>A write can wait in the client while its send queue is full, and the client's wait ends only on an interrupt
     * or a failed exit status: a closed or normally ended stream does not end it. So the copy runs on a thread of its
     * own, which this call alone owns, and the call waits for it at most until the deadline. When time runs out, or
     * the calling thread is interrupted, the call abandons the copy and interrupts the copy's thread, never its own.
     * The client's wait clears that interrupt as it ends; the exec's one-byte input buffer ({@link #run}) leaves at
     * most one such wait per write, and the copy also stops at its next read of the archive once
     * abandoned. Closing the executor waits until the copy's thread has ended, so nothing reads the archive once this
     * method returns, and it keeps an interrupt of the calling thread. A copy that only ends after the deadline is no
     * success: whether the sandbox received the archive is unknown.
     */
    private void send(String sandbox, ExecWatch watch, Staging staging, long deadline) {
        AtomicBoolean abandoned = new AtomicBoolean();
        try (ExecutorService writes = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> written = writes.submit(() -> {
                OutputStream input = watch.getInput();
                long copied = IOUtils.copyLarge(
                        new UntilDeadline(staging.archive(), deadline, abandoned), input, 0, staging.size());
                input.flush();
                return copied;
            });
            long sent;
            try {
                sent = written.get(Math.max(1, left(deadline)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                abandoned.set(true);
                written.cancel(true);
                throw new SandboxException("Staging into the turn sandbox timed out", e);
            } catch (InterruptedException e) {
                abandoned.set(true);
                written.cancel(true);
                Thread.currentThread().interrupt();
                throw new SandboxException("Interrupted while staging into a turn sandbox", e);
            } catch (ExecutionException e) {
                throw sendFailed(sandbox, deadline, e);
            }
            if (left(deadline) <= 0) {
                throw new SandboxException("Staging into the turn sandbox timed out");
            }
            if (sent != staging.size()) {
                throw new SandboxException("The staged archive is shorter than its size");
            }
        }
    }

    /**
     * The failure of the copy, classified as a failure in the calling thread would be: an I/O or client failure as the
     * staging's, past the deadline as its timeout; any other unchecked exception or error unchanged, for {@link #run}'s
     * boundary.
     */
    private SandboxException sendFailed(String sandbox, long deadline, ExecutionException e) {
        Exception failure = switch (e.getCause()) {
            case IOException io -> io;
            case KubernetesClientException api -> api;
            case RuntimeException unchecked -> throw unchecked;
            case Error error -> throw error;
            case null, default -> e;
        };
        if (left(deadline) <= 0) {
            return new SandboxException("Staging into the turn sandbox timed out", failure);
        }
        return failed(sandbox, deadline, "Could not send an archive to a turn sandbox", failure);
    }

    /**
     * A failure of a sandbox that is gone is the caller's to handle; any other failure is the runtime's. The event names
     * the failure by class and HTTP status only: the command, the URL that carries it and any response body stay out.
     */
    private SandboxException failed(String sandbox, long deadline, String message, @Nullable Exception cause) {
        boolean gone = gone(sandbox, deadline);
        String text = gone ? "The turn sandbox is gone" : message;
        Throwable apiFailure = cause;
        while (apiFailure != null && !(apiFailure instanceof KubernetesClientException)) {
            apiFailure = apiFailure.getCause();
        }
        log.atWarn()
                .addKeyValue("event.name", "sandbox.exec.failed")
                .addKeyValue(
                        "sandbox.exec.error_type",
                        cause == null
                                ? "none"
                                : (cause instanceof ExecutionException wrapped && wrapped.getCause() != null
                                                ? wrapped.getCause()
                                                : cause)
                                        .getClass()
                                        .getName())
                .addKeyValue(
                        "sandbox.exec.http_status",
                        apiFailure instanceof KubernetesClientException api ? api.getCode() : 0)
                .addKeyValue("sandbox.gone", gone)
                .log("A turn sandbox command failed");
        if (cause == null) {
            return gone ? new SandboxException(text) : new SandboxInfrastructureException(text);
        }
        return gone ? new SandboxException(text, cause) : new SandboxInfrastructureException(text, cause);
    }

    /** Whether the sandbox is gone, read within what is left of the call's budget; not gone when none is left. */
    private boolean gone(String sandbox, long deadline) {
        if (left(deadline) <= 0) {
            return false;
        }
        try {
            Pod pod = bounded(deadline)
                    .pods()
                    .inNamespace(worker.namespace())
                    .withName(sandbox)
                    .get();
            return pod == null
                    || pod.getMetadata().getDeletionTimestamp() != null
                    || KubernetesPods.terminal(pod)
                    || !bothRunning(pod);
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    private static boolean bothRunning(Pod pod) {
        return KubernetesPods.running(pod, KubernetesPods.SANDBOX)
                && KubernetesPods.running(pod, KubernetesPods.STAGER);
    }

    @Override
    public void remove(String sandbox) {
        // Before the delete: the Pod's exit code and log go with it.
        exits.record(SandboxExits.Kind.TURN, sandbox);
        try {
            pods.delete(sandbox, 0);
        } catch (SandboxException e) {
            log.warn(
                    "Could not remove turn sandbox {}: {}",
                    sandbox,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * The turn sandboxes of every worker, by the worker Pod that created each. A turn sandbox has no network until it
     * pulls its commands, so it cannot see that its worker is gone: its worker counts as gone once the worker process
     * that created it no longer beats in the registry, and its deadline ends it in any case
     * ({@link KubernetesPods#turnPod}). When the registry cannot be read, no worker is gone.
     */
    @Override
    public List<Listed> list() {
        Optional<Set<String>> live = liveWorkers.labels();
        return pods.list(KubernetesWorker.KIND_TURN).stream()
                .map(pod -> {
                    Map<String, String> labels = pod.getMetadata().getLabels();
                    String process = labels.getOrDefault(KubernetesWorker.WORKER_ID, "");
                    return new Listed(
                            pod.getMetadata().getName(),
                            labels.getOrDefault(KubernetesWorker.SANDBOX_ID, ""),
                            labels.getOrDefault(KubernetesWorker.WORKER_POD, ""),
                            live.isPresent() && !live.get().contains(process));
                })
                .toList();
    }

    /** Stops taking calls; an exec that still opens is closed as it opens. */
    @Override
    public void close() {
        calls.shutdown();
    }

    /** The caller's archive, read only before the deadline and until the call abandons it; closing it is the caller's. */
    private static final class UntilDeadline extends InputStream {
        private final InputStream archive;
        private final long deadline;
        private final AtomicBoolean abandoned;

        UntilDeadline(InputStream archive, long deadline, AtomicBoolean abandoned) {
            this.archive = archive;
            this.deadline = deadline;
            this.abandoned = abandoned;
        }

        @Override
        public int read() throws IOException {
            requireTime();
            return archive.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            requireTime();
            return archive.read(buffer, offset, length);
        }

        private void requireTime() throws InterruptedIOException {
            if (abandoned.get() || left(deadline) <= 0) {
                throw new InterruptedIOException("The staging's time ran out");
            }
        }
    }

    /** One output stream of a command, kept up to its limit. Written on the client's reader thread. */
    private static final class Bounded extends OutputStream {
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final int limit;
        volatile boolean truncated;

        Bounded(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            int room = limit - kept.size();
            if (length > room) {
                truncated = true;
            }
            if (room > 0) {
                kept.write(bytes, offset, Math.min(room, length));
            }
        }

        synchronized byte[] bytes() {
            return kept.toByteArray();
        }
    }
}
