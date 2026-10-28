package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxes as Docker containers. Each sandbox gets a network of its own that only this worker joins, so the
 * worker's gateway is its one destination, and attempt volumes for {@code /workspace} ({@link DockerAttemptWorkspace}).
 * The initializer container fills them; the runtime container is created on the same volumes, with {@code /workspace}
 * read-only, once the initializer succeeded and the sandbox is next observed. {@link ContainerSecurityPolicy} owns
 * the hardening of both.
 *
 * <p>A sandbox's name is its kind, its id and its attempt ({@link Name}), which every container, network and volume of
 * it records in its labels. {@link #list}, {@link #kill} and {@link #remove} read the labels, so they reach a sandbox
 * that an earlier process of this worker launched.
 *
 * <p>A worker outside any container joins no network. Its Hephaestus host then reaches it through the host gateway, which
 * only a network with a route out reaches; a review never runs on such a network. The Docker live tests are the one
 * exception: their gateway runs in the test process on the machine itself, so they construct the backend with
 * {@code reviewsOnTheMachine} and give a review that route too.
 */
public class DockerSandboxBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxBackend.class);

    private static final String CONTAINER_USER = ExecSandboxes.SANDBOX_USER + ":" + ExecSandboxes.SANDBOX_USER;

    private static final String UNIDENTIFIED_CONTAINER =
            "This worker runs in a container that it cannot identify, so it cannot join the internal network of its"
                    + " Hephaestus host. Keep the worker container's default hostname.";

    private final SandboxNetworkManager networkManager;
    private final SandboxContainerManager containerManager;
    private final ContainerSecurityPolicy securityPolicy;
    private final DockerVolumeOperations volumeOperations;
    private final SandboxCreator creator;
    private final String owner;
    private final IntSupplier gatewayPort;
    private final boolean reviewsOnTheMachine;
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();

    public DockerSandboxBackend(
            SandboxNetworkManager networkManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            DockerVolumeOperations volumeOperations,
            SandboxCreator creator,
            String owner,
            IntSupplier gatewayPort) {
        this(networkManager, containerManager, securityPolicy, volumeOperations, creator, owner, gatewayPort, false);
    }

    DockerSandboxBackend(
            SandboxNetworkManager networkManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            DockerVolumeOperations volumeOperations,
            SandboxCreator creator,
            String owner,
            IntSupplier gatewayPort,
            boolean reviewsOnTheMachine) {
        this.reviewsOnTheMachine = reviewsOnTheMachine;
        this.networkManager = networkManager;
        this.containerManager = containerManager;
        this.securityPolicy = securityPolicy;
        this.volumeOperations = volumeOperations;
        this.creator = creator;
        this.owner = owner;
        this.gatewayPort = gatewayPort;
    }

    /**
     * A sandbox's name: {@code hephaestus-review-<job>-<attempt>}, {@code hephaestus-review-<job>} for a review without
     * a claim, or {@code hephaestus-host-<host>}. A review's resources record the job and the attempt, a host's its id
     * ({@link SandboxLabels}).
     */
    record Name(Kind kind, UUID id, @Nullable Integer attempt) {

        private static final String REVIEW = "hephaestus-review-";
        private static final String HOST = "hephaestus-host-";
        private static final int UUID_LENGTH = 36;

        Handle handle() {
            return new Handle(kind == Kind.HOST ? HOST + id : REVIEW + id + (attempt == null ? "" : "-" + attempt));
        }

        /** The name in a handle, or empty for a handle that this backend did not give. */
        static Optional<Name> of(Handle handle) {
            String value = handle.id();
            try {
                if (value.startsWith(HOST) && value.length() == HOST.length() + UUID_LENGTH) {
                    return Optional.of(new Name(Kind.HOST, UUID.fromString(value.substring(HOST.length())), null));
                }
                if (value.startsWith(REVIEW) && value.length() >= REVIEW.length() + UUID_LENGTH) {
                    UUID id = UUID.fromString(value.substring(REVIEW.length(), REVIEW.length() + UUID_LENGTH));
                    String rest = value.substring(REVIEW.length() + UUID_LENGTH);
                    if (rest.isEmpty()) {
                        return Optional.of(new Name(Kind.REVIEW, id, null));
                    }
                    Integer attempt = rest.startsWith("-") ? attempt(rest.substring(1)) : null;
                    return attempt == null ? Optional.empty() : Optional.of(new Name(Kind.REVIEW, id, attempt));
                }
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        /** The name that a resource's labels record, or empty for a resource of no sandbox. */
        static Optional<Name> of(Map<String, String> labels) {
            try {
                String host = labels.get(SandboxLabels.SESSION_ID);
                if (host != null) {
                    return Optional.of(new Name(Kind.HOST, UUID.fromString(host), null));
                }
                String job = labels.get(SandboxLabels.JOB_ID);
                if (job == null) {
                    return Optional.empty();
                }
                String recorded = labels.get(SandboxLabels.JOB_ATTEMPT);
                Integer attempt = recorded == null ? null : attempt(recorded);
                if (recorded != null && attempt == null) {
                    return Optional.empty();
                }
                return Optional.of(new Name(Kind.REVIEW, UUID.fromString(job), attempt));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        private static @Nullable Integer attempt(String value) {
            if (!value.matches("[0-9]{1,10}")) {
                return null;
            }
            long parsed = Long.parseLong(value);
            return parsed > Integer.MAX_VALUE ? null : (int) parsed;
        }
    }

    /** A sandbox that this process launched: what it runs on, and its two containers. */
    private static final class Sandbox {
        private final Name name;
        private final List<String> command;
        private @Nullable DockerAttemptWorkspace workspace;
        private DockerOperations.@Nullable ContainerSpec template;
        private @Nullable String initializerId;
        private boolean initializerEnded;
        private String initializerTranscript = "";
        private @Nullable String runtimeId;
        private boolean killed;

        private Sandbox(Name name, List<String> command) {
            this.name = name;
            this.command = command;
        }
    }

    @Override
    public Handle launch(Launch launch) {
        Sandbox sandbox = new Sandbox(new Name(launch.kind(), launch.id(), launch.attempt()), launch.command());
        Handle handle = sandbox.name.handle();
        try {
            boolean host = launch.kind() == Kind.HOST;
            Map<String, String> labels = host ? hostLabels(launch.id()) : reviewLabels(launch);
            // A worker on the machine itself joins no network: only its Hephaestus host gets the route out it needs.
            boolean outsideDocker = host
                    ? !labels.containsKey(SandboxLabels.CREATOR_CONTAINER)
                    : reviewsOnTheMachine && creator.outsideContainer();
            if (sandboxes.putIfAbsent(handle.id(), sandbox) != null) {
                throw new SandboxException("The sandbox " + handle.id() + " runs already");
            }
            String networkId = networkManager.createJobNetwork(launch.id(), outsideDocker, labels);
            String workerAddress = networkManager.connectAppServer(networkId);
            List<String> extraHosts = List.of();
            if (workerAddress == null) {
                // host.docker.internal is reachable only from a non-internal network.
                if (!outsideDocker) {
                    throw new SandboxException(
                            "Practice reviews run on an internal network. Only a worker that runs in a Docker "
                                    + "container with its default hostname can join this network.");
                }
                log.warn(
                        "This worker runs outside a container, so its host {} is on a network with internet access",
                        launch.id());
                workerAddress = "host.docker.internal";
                extraHosts = List.of("host.docker.internal:host-gateway");
            }
            sandbox.workspace = new DockerAttemptWorkspace(volumeOperations, UUID.randomUUID(), labels);
            Map<String, String> environment =
                    launch.environment().at("http://" + workerAddress + ":" + gatewayPort.getAsInt());
            sandbox.template = new DockerOperations.ContainerSpec(
                    launch.image(),
                    List.of(),
                    environment,
                    networkId,
                    host ? "host" : "agent",
                    CONTAINER_USER,
                    labels,
                    securityPolicy.buildHostConfig(SecurityProfile.DEFAULT, launch.limits(), outsideDocker),
                    extraHosts);
            synchronized (sandbox) {
                sandbox.initializerId =
                        containerManager.createContainer(container(sandbox, SandboxLayout.INITIALIZER_COMMAND, true));
                containerManager.startContainer(sandbox.initializerId);
            }
            return handle;
        } catch (RuntimeException e) {
            if (sandboxes.get(handle.id()) == sandbox) {
                try {
                    remove(handle);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    @Override
    public Observation observe(Handle handle, Stage stage, Instant deadline) {
        Sandbox sandbox = sandbox(handle);
        return switch (stage) {
            case INITIALIZED -> initialized(sandbox, deadline);
            case STARTED ->
                startRuntime(sandbox)
                        ? new Observation(false, null)
                        : new Observation(false, SandboxLayout.EXIT_KILLED);
            case ENDED -> {
                if (!startRuntime(sandbox)) {
                    yield new Observation(false, null);
                }
                var outcome = containerManager.waitForCompletion(requireRuntime(sandbox), until(deadline));
                yield new Observation(outcome.timedOut(), outcome.exitCode());
            }
        };
    }

    /** Waits for the initializer, keeps its transcript when it did not succeed, and removes it whatever happens. */
    private Observation initialized(Sandbox sandbox, Instant deadline) {
        String initializerId = sandbox.initializerId;
        if (initializerId == null) {
            throw new SandboxException("The sandbox has no initializer");
        }
        try {
            var outcome = containerManager.waitForCompletion(initializerId, until(deadline));
            if (outcome.timedOut() || outcome.exitCode() != 0) {
                sandbox.initializerTranscript = containerManager.getLogs(initializerId);
            }
            return new Observation(outcome.timedOut(), outcome.exitCode());
        } finally {
            synchronized (sandbox) {
                sandbox.initializerEnded = true;
            }
            try {
                containerManager.forceRemove(initializerId);
            } catch (RuntimeException exception) {
                log.warn(
                        "Could not remove workspace initializer {}; left for reconciliation", initializerId, exception);
            }
        }
    }

    /**
     * Creates and starts the runtime container unless it runs already. A killed sandbox starts none: the creation holds
     * the sandbox's lock, which {@link #kill} takes too.
     */
    private boolean startRuntime(Sandbox sandbox) {
        synchronized (sandbox) {
            if (sandbox.runtimeId != null) {
                return true;
            }
            if (sandbox.killed) {
                return false;
            }
            String runtimeId = containerManager.createContainer(container(sandbox, sandbox.command, false));
            sandbox.runtimeId = runtimeId;
            log.info("Container created: containerId={}", runtimeId);
            containerManager.startContainer(runtimeId);
            log.info("Container started");
            return true;
        }
    }

    @Override
    public boolean running(Handle handle) {
        String runtimeId = sandbox(handle).runtimeId;
        return runtimeId != null
                && containerManager.listManagedContainers().stream()
                        .anyMatch(container -> container.id().equals(runtimeId) && "running".equals(container.state()));
    }

    @Override
    public String transcript(Handle handle, Container container) {
        Sandbox sandbox = sandbox(handle);
        if (container == Container.INITIALIZER) {
            return sandbox.initializerTranscript;
        }
        String runtimeId = sandbox.runtimeId;
        return runtimeId == null ? "" : containerManager.getLogs(runtimeId);
    }

    /**
     * Stops the sandbox's containers that run, and keeps the runtime of a sandbox that this process launched from
     * starting. A failed stop throws; a retry stops again.
     */
    @Override
    public void kill(Handle handle) {
        Optional<Name> name = Name.of(handle);
        if (name.isEmpty()) {
            return;
        }
        Sandbox sandbox = sandboxes.get(handle.id());
        if (sandbox == null) {
            stopContainers(name.get());
            return;
        }
        synchronized (sandbox) {
            sandbox.killed = true;
            if (sandbox.runtimeId != null) {
                containerManager.stopContainer(sandbox.runtimeId);
            } else if (sandbox.initializerId != null && !sandbox.initializerEnded) {
                containerManager.stopContainer(sandbox.initializerId);
            }
        }
    }

    private void stopContainers(Name name) {
        for (DockerOperations.ContainerInfo container : containerManager.listManagedContainers()) {
            if ("running".equals(container.state()) && owns(name, container.labels())) {
                containerManager.stopContainer(container.id());
            }
        }
    }

    /**
     * Removes the sandbox's containers, then its network unless a container stays on it, then its volumes. Each step
     * runs even when an earlier one failed; the removal fails after all of them when a part stays.
     */
    @Override
    public void remove(Handle handle) {
        Optional<Name> parsed = Name.of(handle);
        if (parsed.isEmpty()) {
            return;
        }
        Name name = parsed.get();
        List<String> failed = new ArrayList<>();
        step(failed, "remove container", handle, () -> {
            for (DockerOperations.ContainerInfo container : containerManager.listManagedContainers()) {
                if (owns(name, container.labels())) {
                    containerManager.forceRemove(container.id());
                }
            }
        });
        step(failed, "remove network", handle, () -> {
            String networkName = networkManager.networkPrefix() + name.id();
            for (DockerOperations.NetworkInfo network : networkManager.listNetworks()) {
                if (network.name().equals(networkName)
                        && owns(name, network.labels())
                        && !networkManager.removeUnlessInUse(network.id(), networkName)) {
                    throw new SandboxException("A container is still attached to network " + networkName);
                }
            }
        });
        step(failed, "remove attempt volumes", handle, () -> {
            for (DockerOperations.VolumeInfo volume : attemptVolumes()) {
                if (owns(name, volume.labels())) {
                    volumeOperations.removeVolume(volume.name());
                }
            }
        });
        sandboxes.remove(handle.id());
        if (!failed.isEmpty()) {
            throw new SandboxException("Could not " + String.join(", ", failed) + " of sandbox " + handle.id());
        }
        log.debug("Cleanup complete: sandbox={}", handle.id());
    }

    /**
     * The sandboxes whose containers, networks or volumes this installation's labels record on this daemon. A Hephaestus
     * host's creator is gone when its recorded worker container is ({@link SandboxCreator#liveness}).
     */
    @Override
    public List<Listed> list() {
        Map<Name, Found> found = new LinkedHashMap<>();
        try {
            for (DockerOperations.ContainerInfo container : containerManager.listManagedContainers()) {
                found(found, container.labels(), container.createdAt());
            }
            for (DockerOperations.NetworkInfo network : networkManager.listNetworks()) {
                found(found, network.labels(), network.createdAt());
            }
            for (DockerOperations.VolumeInfo volume : attemptVolumes()) {
                found(found, volume.labels(), createdAt(volume.labels()));
            }
        } catch (SandboxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SandboxException("Could not list the sandboxes on the Docker daemon", e);
        }
        List<Listed> listed = new ArrayList<>();
        found.forEach((name, parts) -> listed.add(new Listed(
                name.handle(),
                name.kind(),
                name.id(),
                name.attempt(),
                parts.unknownAge ? null : parts.newest,
                name.kind() == Kind.HOST && creator.liveness(parts.labels) == SandboxCreator.Liveness.GONE)));
        return listed;
    }

    /** What {@link #list} found of one sandbox: its newest part, and the labels of its first. */
    private static final class Found {
        private final Map<String, String> labels;
        private @Nullable Instant newest;
        private boolean unknownAge;

        private Found(Map<String, String> labels) {
            this.labels = labels;
        }
    }

    private void found(Map<Name, Found> found, Map<String, String> labels, @Nullable Instant created) {
        if (!owner.equals(labels.get(SandboxLabels.OWNER))) {
            return;
        }
        Name.of(labels).ifPresent(name -> {
            Found parts = found.computeIfAbsent(name, key -> new Found(labels));
            if (created == null) {
                parts.unknownAge = true;
            } else if (parts.newest == null || created.isAfter(parts.newest)) {
                parts.newest = created;
            }
        });
    }

    private List<DockerOperations.VolumeInfo> attemptVolumes() {
        return volumeOperations.listVolumes(
                Map.of(SandboxLabels.OWNER, owner, SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE));
    }

    private static @Nullable Instant createdAt(Map<String, String> labels) {
        String created = labels.get(SandboxLabels.CREATED_AT);
        if (created == null) {
            return null;
        }
        try {
            return Instant.parse(created);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Whether a resource with these labels belongs to the named sandbox of this installation. */
    private boolean owns(Name name, Map<String, String> labels) {
        return owner.equals(labels.get(SandboxLabels.OWNER))
                && Name.of(labels).map(name::equals).orElse(false);
    }

    private Map<String, String> reviewLabels(Launch launch) {
        Integer attempt = launch.attempt();
        Map<String, String> labels = attempt == null
                ? securityPolicy.buildLabels(launch.id())
                : securityPolicy.buildLabels(launch.id(), attempt);
        if (attempt != null && attempt > 0) {
            removeSupersededAttempts(launch.id(), attempt, labels.get(SandboxLabels.OWNER));
        }
        return labels;
    }

    /**
     * The labels of a Hephaestus host: its creator, so that cleanup can wait until that creator is gone. A worker in a
     * container that it cannot identify starts no host rather than give the host a route out.
     */
    private Map<String, String> hostLabels(UUID id) {
        Map<String, String> labels = new HashMap<>(creator.labels());
        if (labels.isEmpty() && !creator.outsideContainer()) {
            // Logged here, where the cause is known: callers log an exception's class only.
            log.warn(UNIDENTIFIED_CONTAINER);
            throw new SandboxException(UNIDENTIFIED_CONTAINER);
        }
        labels.put(SandboxLabels.OWNER, owner);
        labels.put(SandboxLabels.KIND, SandboxLabels.KIND_HEPH_HOST);
        labels.put(SandboxLabels.SESSION_ID, id.toString());
        return labels;
    }

    /**
     * Removes this job's containers from attempts the claim has superseded, so the job network they hold can be
     * recreated. Only a container with this installation's owner, this job and a lower recorded attempt is removed;
     * anything else stays, and the network guard refuses it as before. A failed removal fails this attempt.
     */
    private void removeSupersededAttempts(UUID jobId, int attempt, @Nullable String owner) {
        for (DockerOperations.ContainerInfo container : containerManager.listManagedContainers()) {
            Map<String, String> labels = container.labels();
            String recorded = labels.get(SandboxLabels.JOB_ATTEMPT);
            if (owner == null
                    || !owner.equals(labels.get(SandboxLabels.OWNER))
                    || !jobId.toString().equals(labels.get(SandboxLabels.JOB_ID))
                    || labels.containsKey(SandboxLabels.SESSION_ID)
                    || recorded == null
                    || !recorded.matches("[0-9]{1,10}")
                    || Long.parseLong(recorded) >= attempt) {
                continue;
            }
            log.info("Removing superseded sandbox container: containerId={}, attempt={}", container.id(), recorded);
            containerManager.forceRemove(container.id());
        }
    }

    private DockerOperations.ContainerSpec container(Sandbox sandbox, List<String> command, boolean initialization) {
        DockerOperations.ContainerSpec template = sandbox.template;
        DockerAttemptWorkspace workspace = sandbox.workspace;
        if (template == null || workspace == null) {
            throw new SandboxException("The sandbox is not prepared");
        }
        return new DockerOperations.ContainerSpec(
                template.image(),
                command,
                template.environment(),
                template.networkId(),
                template.hostname(),
                template.user(),
                template.labels(),
                workspace.configure(template.hostConfig(), initialization),
                template.extraHosts());
    }

    private Sandbox sandbox(Handle handle) {
        Sandbox sandbox = sandboxes.get(handle.id());
        if (sandbox == null) {
            throw new SandboxException("The sandbox is gone");
        }
        return sandbox;
    }

    private static String requireRuntime(Sandbox sandbox) {
        String runtimeId = sandbox.runtimeId;
        if (runtimeId == null) {
            throw new SandboxException("The sandbox has no runtime");
        }
        return runtimeId;
    }

    /** What is left until the deadline; none once it passed, so a wait times out at once. */
    private static Duration until(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static void step(List<String> failed, String operation, Handle handle, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            failed.add(operation);
            log.warn(
                    "Cleanup failed ({}): sandbox={}, errorType={}",
                    operation,
                    handle.id(),
                    e.getClass().getName());
        }
    }
}
