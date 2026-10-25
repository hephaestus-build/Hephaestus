package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExecSandboxes} on the Docker daemon of the practice review sandboxes. A sandbox is a container of the agent
 * image with the review's hardened host configuration, network mode {@code none} and an init process that reaps
 * orphaned processes. Its command sleeps; work arrives through {@code docker exec}. A staging runs as root, so the
 * folder it writes belongs to root and is read-only after it. A command and the cancellation of commands run as
 * {@link ExecSandboxes#SANDBOX_USER}. The init process and its sleep run as root, out of that user's reach.
 *
 * <p>Labels record this installation ({@link SandboxLabels#OWNER}), the sandbox id and the worker that created it
 * ({@link SandboxCreator}). {@code SandboxReconciler} ignores these containers: they have no job id and no session id.
 * The worker that uses them removes them ({@code TurnSandboxes}).
 */
public class DockerExecSandboxes implements ExecSandboxes, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DockerExecSandboxes.class);

    private static final String HOSTNAME = "sandbox";

    /** The memory file system that holds the context folder; the image's {@code /workspace} is under it. */
    static final String WORKSPACE_TMPFS = "rw,noexec,nosuid,nodev,size=536870912,mode=0755";

    private static final SecurityProfile PROFILE =
            new SecurityProfile(null, "none", List.of("ALL"), Map.of("/workspace", WORKSPACE_TMPFS));

    private static final String ROOT = "0:0";
    private static final String UNPRIVILEGED = SANDBOX_USER + ":" + SANDBOX_USER;
    private static final String WORKSPACE = "/workspace";

    /**
     * Arguments: the archive's length, then the entries in {@code /workspace} that it writes. Runs as root; the folder
     * is read-only after. The runtime does not close stdin after the archive, so {@code head} ends the input at its
     * length.
     */
    private static final String ADD_SCRIPT = """
            set -e
            n=$1; shift
            chmod u+w /workspace
            for d; do if [ -e "/workspace/$d" ]; then chmod -R u+w "/workspace/$d"; fi; done
            head -c "$n" | tar -x --no-same-owner -C /workspace
            for d; do if [ -e "/workspace/$d" ]; then chmod -R a-w "/workspace/$d"; fi; done
            chmod a-w /workspace
            """;

    /**
     * Arguments: the archive's length, the target directory, then its ancestors below {@code /workspace}. Replaces the
     * target with the archive on stdin. Runs as root; the folder is read-only after.
     */
    private static final String REPLACE_SCRIPT = """
            set -e
            n=$1; t=$2; shift 2
            chmod u+w /workspace
            for d; do if [ -d "$d" ]; then chmod u+w "$d"; fi; done
            if [ -e "$t" ]; then chmod -R u+w "$t"; rm -rf -- "$t"; fi
            mkdir -p -- "$t"
            head -c "$n" | tar -x --no-same-owner -C "$t"
            chmod -R a-w "$t"
            for d; do chmod a-w "$d"; done
            chmod a-w /workspace
            """;

    /**
     * {@code kill -1} reaches every process of the sandbox user, a detached one too, and spares only the calling process
     * and the init process. The image has no {@code pkill}.
     */
    private static final List<String> CANCEL_COMMAND = List.of("sh", "-c", "kill -KILL -1");

    private static final Duration CANCEL_TIME = Duration.ofSeconds(5);
    private static final int CANCEL_OUTPUT_LIMIT = 1024;
    private static final int STAGING_OUTPUT_LIMIT = 4096;

    private final DockerClient client;
    private final DockerClientOperations operations;
    private final ContainerSecurityPolicy securityPolicy;
    private final SandboxImageGuard imageGuard;
    private final SandboxCreator creator;
    private final String image;
    private final String owner;
    private final int serverPort;

    /**
     * @param serverPort the worker's HTTP port, which tells two workers outside containers on one machine apart
     * @param client a client of its own, so that commands waiting for output do not take the connections that the
     *     practice reviews wait on
     */
    public DockerExecSandboxes(
            DockerClient client,
            DockerClientOperations operations,
            ContainerSecurityPolicy securityPolicy,
            SandboxImageGuard imageGuard,
            SandboxCreator creator,
            String image,
            String owner,
            int serverPort) {
        this.client = client;
        this.operations = operations;
        this.securityPolicy = securityPolicy;
        this.imageGuard = imageGuard;
        this.creator = creator;
        this.image = image;
        this.owner = owner;
        this.serverPort = serverPort;
    }

    /**
     * The worker's container, or the machine and port for a worker outside any container: both outlast a restart, and
     * two workers on one machine listen on different ports. A worker in a container that it cannot identify has none,
     * as for the Heph host ({@link SandboxCreator}).
     */
    @Override
    public @Nullable String workerInstance() {
        String container = creator.labels().get(SandboxLabels.CREATOR_CONTAINER);
        if (container != null) {
            return container;
        }
        return creator.outsideContainer() ? machineIdentity(machineName(), serverPort) : null;
    }

    static String machineIdentity(String machine, int port) {
        return "machine:" + machine + ":" + port;
    }

    private static String machineName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unnamed) {
            return "localhost";
        }
    }

    @Override
    public String create(String sandboxId, ResourceLimits limits) {
        String worker = workerInstance();
        if (worker == null) {
            throw new SandboxInfrastructureException(
                    "This worker runs in a container that it cannot identify, so it starts no turn sandbox. Keep the"
                            + " worker container's default hostname.");
        }
        Map<String, String> labels = new HashMap<>(creator.labels());
        labels.put(SandboxLabels.OWNER, owner);
        labels.put(SandboxLabels.KIND, SandboxLabels.KIND_HEPH_SANDBOX);
        labels.put(SandboxLabels.HEPH_SANDBOX, sandboxId);
        labels.put(SandboxLabels.HEPH_WORKER, worker);
        HostConfig hostConfig = operations
                .hostConfig(securityPolicy.buildHostConfig(PROFILE, limits, false))
                .withNetworkMode("none")
                .withInit(true);
        imageGuard.ensurePresent(image);
        String id = null;
        try {
            id = client.createContainerCmd(image)
                    .withHostConfig(hostConfig)
                    .withLabels(labels)
                    .withHostName(HOSTNAME)
                    // The init process and its sleep run as root, so that the sandbox user's kill -1 cannot end
                    // the sandbox; every command names its own user.
                    .withUser(ROOT)
                    .withWorkingDir(WORKSPACE)
                    .withEntrypoint("sleep")
                    .withCmd("infinity")
                    .exec()
                    .getId();
            client.startContainerCmd(id).exec();
            return id;
        } catch (DockerException | IllegalStateException e) {
            if (id != null) remove(id);
            throw new SandboxInfrastructureException("Could not start a turn sandbox", e);
        }
    }

    @Override
    public Execution exec(String sandbox, Command command) {
        return run(sandbox, command.argv(), UNPRIVILEGED, null, command.timeout(), command.outputLimit());
    }

    @Override
    public void stage(String sandbox, Staging staging) {
        List<String> argv = new ArrayList<>(List.of("sh", "-c"));
        switch (staging) {
            case Add add -> {
                argv.addAll(List.of(ADD_SCRIPT, "add", Long.toString(add.size())));
                argv.addAll(add.names());
            }
            case Replace replace -> {
                argv.addAll(List.of(REPLACE_SCRIPT, "replace", Long.toString(replace.size())));
                String[] names = replace.directory().split("/", -1);
                argv.add(WORKSPACE + "/" + replace.directory());
                for (int end = 1; end < names.length; end++) {
                    argv.add(WORKSPACE + "/" + String.join("/", Arrays.copyOfRange(names, 0, end)));
                }
            }
        }
        Execution staged = run(sandbox, argv, ROOT, staging.archive(), staging.timeout(), STAGING_OUTPUT_LIMIT);
        Integer exit = staged.exitCode();
        if (exit == null || exit != 0) {
            // The exit is the classification; the sandbox's stderr can name the staged files and stays out.
            log.warn("Staging into a turn sandbox exited with {}", exit);
            throw new SandboxException("Staging into the turn sandbox failed: exit " + exit);
        }
    }

    /** Its own exit says nothing: {@code kill} fails when no process of the user is left to end. */
    @Override
    public void cancelCommands(String sandbox) {
        run(sandbox, CANCEL_COMMAND, UNPRIVILEGED, null, CANCEL_TIME, CANCEL_OUTPUT_LIMIT);
    }

    private Execution run(
            String sandbox,
            List<String> argv,
            String user,
            @Nullable InputStream stdin,
            Duration timeout,
            int outputLimit) {
        String execId;
        try {
            execId = client.execCreateCmd(sandbox)
                    .withCmd(argv.toArray(String[]::new))
                    .withUser(user)
                    .withWorkingDir(WORKSPACE)
                    .withAttachStdin(stdin != null)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec()
                    .getId();
        } catch (NotFoundException | ConflictException e) {
            // Conflict: the container exists but no longer runs.
            throw new SandboxException("The turn sandbox is gone", e);
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Could not run a command in a turn sandbox", e);
        }
        Output output = new Output(outputLimit);
        boolean completed;
        try (Output callback = output) {
            var start = client.execStartCmd(execId).withDetach(false).withTty(false);
            if (stdin != null) start.withStdIn(stdin);
            completed = start.exec(callback).awaitCompletion(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted while a turn sandbox ran a command", e);
        } catch (IOException | RuntimeException e) {
            // docker-java rethrows a failure of the attached stream from awaitCompletion.
            throw new SandboxInfrastructureException("A command in a turn sandbox failed", e);
        }
        if (!completed) {
            return new Execution(null, output.stdout.toByteArray(), output.stderr.toByteArray(), output.truncated);
        }
        Long exitCode;
        try {
            exitCode = client.inspectExecCmd(execId).exec().getExitCodeLong();
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Could not read the exit code of a turn sandbox command", e);
        }
        return new Execution(
                exitCode == null ? -1 : exitCode.intValue(),
                output.stdout.toByteArray(),
                output.stderr.toByteArray(),
                output.truncated);
    }

    @Override
    public void remove(String sandbox) {
        try {
            client.removeContainerCmd(sandbox)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (NotFoundException e) {
            log.debug("Turn sandbox {} was already removed", sandbox);
        } catch (DockerException | IllegalStateException e) {
            // The class only: a Docker client exception can quote the daemon's response body.
            log.warn(
                    "Could not remove turn sandbox {}: {}",
                    sandbox,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public List<Listed> list() {
        try {
            return client
                    .listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(
                            Map.of(SandboxLabels.OWNER, owner, SandboxLabels.KIND, SandboxLabels.KIND_HEPH_SANDBOX))
                    .exec()
                    .stream()
                    .map(container -> {
                        Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
                        return new Listed(
                                container.getId(),
                                labels.getOrDefault(SandboxLabels.HEPH_SANDBOX, ""),
                                labels.getOrDefault(SandboxLabels.HEPH_WORKER, ""),
                                creator.liveness(labels) == SandboxCreator.Liveness.GONE);
                    })
                    .toList();
        } catch (DockerException e) {
            throw new SandboxInfrastructureException("Could not list the turn sandboxes", e);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /** The output of one command, each stream kept up to its limit. Called on docker-java's reader thread. */
    private static final class Output extends ResultCallback.Adapter<Frame> {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int limit;
        volatile boolean truncated;

        Output(int limit) {
            this.limit = limit;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload == null) return;
            ByteArrayOutputStream target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
            synchronized (this) {
                int room = limit - target.size();
                if (payload.length > room) truncated = true;
                if (room > 0) target.write(payload, 0, Math.min(room, payload.length));
            }
        }
    }
}
