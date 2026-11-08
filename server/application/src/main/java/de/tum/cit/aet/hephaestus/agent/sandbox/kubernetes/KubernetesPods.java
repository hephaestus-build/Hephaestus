package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.BoundedTranscript;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SeccompProfile;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandbox Pods and the API calls on them. Every sandbox Pod meets the Restricted Pod Security Standard, which the
 * chart enforces on the sandbox namespace: every container non-root, no privilege escalation, no capability, a
 * read-only root file system and the seccomp profile ({@code RuntimeDefault} unless the operator names the shipped
 * Localhost one). It also has no service account token, no service variables, no host namespace, no DNS server and
 * {@code restartPolicy: Never}. Writable places are {@code emptyDir} volumes, on the node's disk with a size limit,
 * except {@code /run}, which can hold a credential and stays in memory.
 *
 * <p>Controls that the Pod API does not express:
 *
 * <ul>
 *   <li>The {@code nofile} 1024 and {@code core} 0 limits, soft and hard: every container command and every exec starts
 *       through {@link #RLIMITS}, which lowers both before it executes the command with its arguments unchanged. A
 *       process cannot raise a hard limit without a capability, and the containers have none.
 *   <li>A per-container process limit: the kubelet's per-Pod limit stands in where the nodes set one. The isolation
 *       probe records whether they do; without one, a sandbox's memory limit bounds its processes.
 *   <li>An {@code emptyDir} has no {@code noexec} mount option
 *       and a Pod has a private IPC namespace of its own. ADR 0041 records why Kubernetes does without them.
 * </ul>
 *
 * <p>Network isolation is the chart's NetworkPolicies. The isolation probe proves at the worker's start that the
 * cluster enforces them, the Pod Security Standard and the process limit ({@link KubernetesIsolationProbe}). A network
 * plugin may filter a new Pod only some time after it started, so the first init container of every sandbox Pod waits
 * for its policy ({@link #ISOLATION_SCRIPT}).
 */
public class KubernetesPods {

    private static final Logger log = LoggerFactory.getLogger(KubernetesPods.class);

    /** The first init container of every sandbox Pod: it waits until the network policy filters the Pod. */
    public static final String ISOLATION = "isolation";

    /** The workspace initializer of a review or host Pod: an init container on the attempt volumes. */
    public static final String INITIALIZER = "workspace";

    /** The review's or host's trusted process. It alone holds the attempt's credential. */
    public static final String LOOP = "loop";

    /** A review's native sidecar: it runs every untrusted command, with no credential. */
    public static final String TOOLS = "tools";

    /** A review's native sidecar for custom practice programs: out of reach of the commands in {@link #TOOLS}. */
    public static final String PROGRAMS = "programs";

    /** The loop's user, and the initializer's. */
    static final long SANDBOX_UID = 1000;

    /** Not the loop's user: a tools command cannot signal, trace or read the loop. */
    static final long TOOLS_UID = 1001;

    /** Not the tools user: a tools command cannot trace or rewrite a program, whose result the loop trusts. */
    static final long PROGRAMS_UID = 1002;

    static final String WORKSPACE = "/workspace";

    /** Where the tools side listens: a volume of the loop and the tools alone. */
    static final String TOOLS_SOCKET = "/run/tools/tools.sock";

    /** Where the program side listens. Only the loop and the programs mount it: the boundary is the missing mount. */
    static final String PROGRAMS_SOCKET = "/run/programs/programs.sock";

    /** Starts the tools program under its system call filter, on either side. */
    static final List<String> TOOLS_COMMAND = List.of("sh", "/opt/pi-sdk/tools-launch.sh");

    /**
     * The attempt workspace, one volume per path: the initializer writes all of them,
     * the runtime reads {@code /workspace} only.
     */
    private static final Map<String, String> ATTEMPT_VOLUMES = attemptVolumes();

    /**
     * An empty volume hides the image's {@code /workspace/node_modules} link. The initializer restores that link so
     * the runner resolves its SDK from the image.
     */
    static final List<String> INITIALIZER_COMMAND = List.of(
            "sh",
            "-c",
            "ln -s /opt/pi-sdk/node_modules /workspace/node_modules && exec "
                    + String.join(" ", SandboxLayout.INITIALIZER_COMMAND));

    /**
     * How long the isolation init container waits for the network policy. A network plugin may filter a new Pod only
     * after it started; on the plugin of k3s that takes seconds.
     */
    static final Duration ISOLATION_WAIT = Duration.ofSeconds(60);

    /**
     * The isolation init container's script. Every policy of a sandbox denies the Kubernetes API, whose address the
     * kubelet gives every container. The script waits until a connection to it fails, so no later container starts
     * before the Pod's own policy is in force, and it fails the Pod when that does not happen in time. It runs only
     * the image's Node and no input of the sandbox.
     */
    static final String ISOLATION_SCRIPT = """
            const net = require('node:net');
            const host = process.env.KUBERNETES_SERVICE_HOST, port = Number(process.env.KUBERNETES_SERVICE_PORT);
            const until = Date.now() + Number(process.argv[1]);
            const open = () => new Promise((done) => {
              const socket = net.connect({ host, port, timeout: 1000 });
              socket.on('connect', () => { socket.destroy(); done(true); });
              socket.on('timeout', () => { socket.destroy(); done(false); });
              socket.on('error', () => done(false));
            });
            (async () => {
              while (await open()) {
                if (Date.now() > until) { console.error('The network policy did not filter this Pod'); process.exit(1); }
                await new Promise((wait) => setTimeout(wait, 250));
              }
            })();
            """;

    /** The isolation gate only connects once a quarter second: every sandbox Pod requests at least this. */
    private static final Quantity ISOLATION_CPU = new Quantity("10m");

    private static final Quantity ISOLATION_MEMORY = new Quantity("128Mi");

    private static final long MIB = 1024L * 1024;

    /** The scratch places of every sandbox Pod, on the node's disk. */
    private static final List<String> SCRATCH_PATHS = List.of("/home/agent/.local", "/tmp");

    /** The scratch place that can hold a credential stays in memory: its pages count against the memory limit. */
    private static final Map<String, Long> MEMORY_VOLUMES = Map.of("/run", 16 * MIB);

    /** What a container writes besides its volumes, its own logs, with headroom; a side container writes less. */
    private static final long LOG_BYTES = 64 * MIB;

    private static final long SIDE_LOG_BYTES = 16 * MIB;

    /** A socket file and nothing else: the smallest memory volume. */
    private static final long SOCKET_BYTES = MIB;

    /**
     * The sides beyond the loop, from measured use. Each has its own limit, so that a memory kill of the tools never takes
     * the loop. Their requests and the loop's add up to one review in sandbox-pod-requests.json, so a review that runs
     * programs takes the quota that a review always took. A program process lives briefly, so the limit, not the request,
     * holds it.
     */
    private static final ResourceRequirements TOOLS_RESOURCES = side(32 * MIB);

    private static final ResourceRequirements PROGRAMS_RESOURCES = side(40 * MIB);

    private static ResourceRequirements side(long memoryRequest) {
        return new ResourceRequirementsBuilder()
                .withLimits(Map.of(
                        "cpu", new Quantity("500m"),
                        "memory", bytes(768 * MIB),
                        "ephemeral-storage", bytes(SandboxLayout.SCRATCH_BYTES + SIDE_LOG_BYTES)))
                .withRequests(Map.of(
                        "cpu", new Quantity("10m"),
                        "memory", bytes(memoryRequest),
                        "ephemeral-storage", bytes(SIDE_LOG_BYTES)))
                .build();
    }

    /**
     * The memory that one kind of sandbox Pod requests and may use, and its local disk. Kubernetes sizes its sandbox
     * Pods here, from measured use. Each of the Pod's {@code diskVolumes} volumes on the node's disk has a size limit of {@code
     * volumeBytes}, and the Pod's main container may use them all and its logs ({@link #diskLimit}). The kubelet evicts
     * the Pod past either bound, so that no sandbox can fill the node's disk.
     */
    record Size(
            long memoryRequest,
            long memoryLimit,
            long diskRequest,
            long volumeBytes,
            int diskVolumes,
            long cpuRequestMillicores) {

        long diskLimit() {
            return diskVolumes * volumeBytes + LOG_BYTES;
        }
    }

    /**
     * A review's loop: its commands and programs run on the other sides. A large change can pass the request, so the
     * limit stays well above it. Its attempt volumes and scratch places each hold one scratch size.
     */
    static final Size REVIEW = new Size(
            312 * MIB,
            1280 * MIB,
            992 * MIB,
            SandboxLayout.SCRATCH_BYTES,
            ATTEMPT_VOLUMES.size() + SCRATCH_PATHS.size(),
            30);

    /** The Heph host, in the same volumes as a review. It is idle between turns, so it requests 50m of its one CPU. */
    static final Size HOST = new Size(
            256 * MIB,
            SandboxLayout.HEPH_HOST_MEMORY_BYTES,
            256 * MIB,
            SandboxLayout.SCRATCH_BYTES,
            ATTEMPT_VOLUMES.size() + SCRATCH_PATHS.size(),
            50);

    /**
     * A conversation's tools Pod: the tools program and the commands of one turn attempt. Its one volume, {@code /tmp},
     * holds the tools workspace, where the turn's documents and the repositories it loads land, and the spill files. It
     * requests 25m: a pooled Pod is idle, and a turn's commands are short bursts that its limit serves.
     */
    static final Size TOOLS_POD = new Size(160 * MIB, 512 * MIB, 256 * MIB, SandboxLayout.TURN_WORKSPACE_BYTES, 1, 25);

    /**
     * The fixed start of every container command and every exec: the {@code nofile} and {@code core} limits,
     * soft and hard. {@code prlimit} (util-linux, in the agent image) sets them on
     * itself and executes the command after {@code --} with its arguments as they are: no shell parses them. The
     * process count stays the cgroup's: {@code RLIMIT_NPROC} counts the user's processes on the whole host.
     */
    static final List<String> RLIMITS = List.of("/usr/bin/prlimit", "--nofile=1024:1024", "--core=0:0", "--");

    /** A review's two sides beyond the loop: the nonce that binds them to this Pod, and whether it runs programs. */
    public record Sidecars(String nonce, boolean programs) {}

    /** Waiting reasons from which a container does not start on its own. */
    private static final Set<String> FATAL_WAITING = Set.of(
            "ErrImageNeverPull",
            "InvalidImageName",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError");

    /** How long a stopped Pod may take to report its end beyond its grace period. */
    private static final Duration STOP_SETTLE = Duration.ofSeconds(30);

    private final KubernetesClient client;
    private final KubernetesWorker worker;
    private final long stopGraceSeconds;
    private final String pullPolicy;

    public KubernetesPods(
            KubernetesClient client, KubernetesWorker worker, int stopGraceSeconds, ImagePullPolicy pullPolicy) {
        this.client = client;
        this.worker = worker;
        this.stopGraceSeconds = stopGraceSeconds;
        this.pullPolicy = switch (pullPolicy) {
            case ALWAYS -> "Always";
            case IF_NOT_PRESENT -> "IfNotPresent";
            case NEVER -> "Never";
        };
    }

    /** A sandbox Pod: its name and labels, its image and the hostname its processes see. */
    public record Workload(String name, Map<String, String> labels, String image, String hostname) {
        public Workload {
            labels = Map.copyOf(labels);
        }
    }

    /** What waiting for a Pod saw: its last state, or null when it is gone. */
    public record Waited(boolean timedOut, @Nullable Pod pod) {}

    /** The command under the system call filter of a review's tools side (see tools-launch.sh). */
    static List<String> filtered(List<String> command) {
        List<String> argv = new ArrayList<>(List.of(
                "sh",
                "-c",
                "exec setpriv --no-new-privs --seccomp-filter \"/opt/pi-sdk/tools-seccomp-$(uname -m).bpf\" \"$@\"",
                "filter"));
        argv.addAll(command);
        return List.copyOf(argv);
    }

    /** The command, started through {@link #RLIMITS}: each argument stays one argument. */
    static List<String> limited(List<String> command) {
        List<String> argv = new ArrayList<>(RLIMITS.size() + command.size());
        argv.addAll(RLIMITS);
        argv.addAll(command);
        return List.copyOf(argv);
    }

    /**
     * A review or host Pod: the initializer fills the attempt volumes from the gateway, then the runtime runs on them
     * with {@code /workspace} read-only. The initializer mounts {@code /workspace} writable, which makes the mount
     * points of the runtime's nested volumes. The Pod has no deadline of its own: a review's Job bounds it, and a host
     * runs until it is removed.
     */
    public Pod workspacePod(
            Workload workload,
            Map<String, String> environment,
            List<String> command,
            ResourceLimits limits,
            Size size,
            @Nullable Sidecars sidecars) {
        List<EnvVar> env = new TreeMap<>(environment)
                .entrySet().stream()
                        .map(entry -> new EnvVarBuilder()
                                .withName(entry.getKey())
                                .withValue(entry.getValue())
                                .build())
                        .toList();
        List<Volume> volumes = new ArrayList<>();
        ATTEMPT_VOLUMES.keySet().forEach(name -> volumes.add(diskVolume(name, size.volumeBytes())));
        volumes.addAll(scratchVolumes(size));
        ResourceRequirements resources = resources(limits, size);
        Container initializer = base(INITIALIZER, workload.image(), SANDBOX_UID)
                .withCommand(limited(INITIALIZER_COMMAND))
                .withEnv(env)
                .withVolumeMounts(attemptMounts(false))
                .withResources(resources)
                .build();
        List<EnvVar> loopEnv = new ArrayList<>(env);
        List<VolumeMount> loopMounts = attemptMounts(true);
        List<Container> inits = new ArrayList<>(List.of(isolation(workload.image()), initializer));
        if (sidecars != null) {
            loopEnv.add(variable("TOOLS_SOCKET", TOOLS_SOCKET));
            loopEnv.add(variable("TOOLS_NONCE", sidecars.nonce()));
            volumes.add(memoryVolume("tools-socket", SOCKET_BYTES));
            volumes.add(diskVolume("tools-tmp", size.volumeBytes()));
            loopMounts.add(mount("tools-socket", "/run/tools", false));
            List<EnvVar> gitSettings = env.stream()
                    .filter(variable -> variable.getName().startsWith("GIT_"))
                    .toList();
            inits.add(sidecar(workload, TOOLS, TOOLS_UID, TOOLS_SOCKET, sidecars.nonce(), "tools", gitSettings));
            if (sidecars.programs()) {
                loopEnv.add(variable("PROGRAMS_SOCKET", PROGRAMS_SOCKET));
                volumes.add(memoryVolume("programs-socket", SOCKET_BYTES));
                volumes.add(diskVolume("programs-tmp", size.volumeBytes()));
                loopMounts.add(mount("programs-socket", "/run/programs", false));
                inits.add(sidecar(
                        workload, PROGRAMS, PROGRAMS_UID, PROGRAMS_SOCKET, sidecars.nonce(), "programs", gitSettings));
            }
        }
        Container loop = base(LOOP, workload.image(), SANDBOX_UID)
                .withCommand(limited(command))
                .withEnv(loopEnv)
                .withVolumeMounts(loopMounts)
                .withResources(resources)
                .build();
        return pod(workload, SANDBOX_UID, null)
                .editSpec()
                .withInitContainers(inits)
                .withContainers(loop)
                .withVolumes(volumes)
                .endSpec()
                .build();
    }

    /**
     * A native sidecar: an init container that stays up beside the loop. It gets the nonce, its socket and Git's settings,
     * so the attempt's credential never reaches it. It mounts the workspace read-only and has its own {@code
     * /tmp}; the loop's {@code out}, {@code .pi} and {@code .sessions} are not mounted at all.
     */
    private Container sidecar(
            Workload workload,
            String name,
            long uid,
            String socket,
            String nonce,
            String role,
            List<EnvVar> gitSettings) {
        List<EnvVar> env = new ArrayList<>(gitSettings);
        env.add(variable("TOOLS_NONCE", nonce));
        env.add(variable("TOOLS_SOCKET", socket));
        env.add(variable("TOOLS_ROLE", role));
        return base(name, workload.image(), uid)
                .withRestartPolicy("Always")
                .withCommand(limited(TOOLS_COMMAND))
                .withEnv(env)
                .withVolumeMounts(
                        mount("workspace", WORKSPACE, true),
                        mount("work", "/workspace/work", true),
                        mount(name + "-socket", socket.substring(0, socket.lastIndexOf('/')), false),
                        mount(name + "-tmp", "/tmp", false))
                .withResources(TOOLS.equals(name) ? TOOLS_RESOURCES : PROGRAMS_RESOURCES)
                .build();
    }

    private static EnvVar variable(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /**
     * A conversation's tools Pod: the review sidecar's tools program as its only container, serving its host on TCP. It
     * gets the nonce and Git's settings and no credential; the isolation init container holds it until the network
     * policy that leaves it no egress is in force. It ends itself at the latest at its limits' runtime, so no worker needs
     * to be alive to bound it.
     */
    public Pod toolsPod(Workload workload, ResourceLimits limits, String nonce, Map<String, String> gitSettings) {
        List<EnvVar> env = new ArrayList<>();
        gitSettings.forEach((name, value) -> env.add(variable(name, value)));
        env.add(variable("TOOLS_NONCE", nonce));
        env.add(variable("TOOLS_PORT", Integer.toString(ToolsPods.PORT)));
        env.add(variable("TOOLS_ROLE", "conversation"));
        Container tools = base(TOOLS, workload.image(), TOOLS_UID)
                .withCommand(limited(TOOLS_COMMAND))
                .withEnv(env)
                .withPorts(new ContainerPortBuilder()
                        .withName(TOOLS)
                        .withContainerPort(ToolsPods.PORT)
                        .withProtocol("TCP")
                        .build())
                .withVolumeMounts(mount("tools-tmp", "/tmp", false))
                .withResources(resources(limits, TOOLS_POD))
                .build();
        return pod(workload, TOOLS_UID, limits.maxRuntime().toSeconds())
                .editSpec()
                .withInitContainers(isolation(workload.image()))
                .withContainers(tools)
                .withVolumes(diskVolume("tools-tmp", TOOLS_POD.volumeBytes()))
                .endSpec()
                .build();
    }

    /** A Pod of the isolation probe: one container with the sandbox's configuration that runs {@code command}. */
    public Pod probePod(
            Workload workload,
            List<String> command,
            ResourceLimits limits,
            long memoryBytes,
            long activeDeadlineSeconds,
            long uid) {
        Size size = probeSize(limits, memoryBytes);
        Container probe = base(LOOP, workload.image(), uid)
                .withCommand(command)
                .withVolumeMounts(scratchMounts())
                .withResources(resources(limits, size))
                .build();
        return pod(workload, SANDBOX_UID, activeDeadlineSeconds)
                .editSpec()
                .withInitContainers(isolation(workload.image()))
                .withContainers(probe)
                .withVolumes(scratchVolumes(size))
                .endSpec()
                .build();
    }

    /** The init container that waits for the Pod's network policy ({@link #ISOLATION_SCRIPT}). */
    private Container isolation(String image) {
        Map<String, Quantity> bounds =
                Map.of("cpu", ISOLATION_CPU, "memory", ISOLATION_MEMORY, "ephemeral-storage", bytes(SIDE_LOG_BYTES));
        return base(ISOLATION, image, SANDBOX_UID)
                .withCommand(limited(List.of("node", "-e", ISOLATION_SCRIPT, Long.toString(ISOLATION_WAIT.toMillis()))))
                .withResources(new ResourceRequirementsBuilder()
                        .withLimits(bounds)
                        .withRequests(bounds)
                        .build())
                .build();
    }

    /**
     * Why Kubernetes ended the Pod, from its {@code DisruptionTarget} condition, or null when it did not: an eviction, a
     * preemption or a node's taint ended it, not the sandbox's own processes.
     */
    static @Nullable String disruption(Pod pod) {
        if (pod.getStatus() == null) {
            return null;
        }
        String condition = pod.getStatus().getConditions() == null
                ? null
                : pod.getStatus().getConditions().stream()
                        .filter(each -> "DisruptionTarget".equals(each.getType()) && "True".equals(each.getStatus()))
                        .map(each -> each.getReason() == null ? "DisruptionTarget" : each.getReason())
                        .findFirst()
                        .orElse(null);
        // The kubelet also evicts a Pod whose scratch place outgrew its size limit, also where it sets no condition.
        return condition == null && "Evicted".equals(pod.getStatus().getReason()) ? "Evicted" : condition;
    }

    /** Whether the Pod ended in its isolation init container: its network policy was not in force in time. */
    public static boolean notIsolated(Pod pod) {
        Integer exit = exitCode(pod, ISOLATION);
        return exit != null && exit != 0;
    }

    /** A Heph host or tools Pod is a conversation; a review and every probe Pod rank below it. */
    private static @Nullable String priorityClass(Workload workload, KubernetesSandboxProperties properties) {
        String kind = workload.labels().get(KubernetesWorker.SANDBOX_KIND);
        boolean conversation = !workload.labels().containsKey(KubernetesWorker.PROBE)
                && (KubernetesWorker.KIND_HOST.equals(kind) || KubernetesWorker.KIND_TOOLS.equals(kind));
        String name = conversation ? properties.conversationPriorityClass() : properties.reviewPriorityClass();
        return name.isEmpty() ? null : name;
    }

    private PodBuilder pod(Workload workload, long fsGroup, @Nullable Long activeDeadlineSeconds) {
        KubernetesSandboxProperties properties = worker.properties();
        String seccomp = properties.seccompProfile();
        return new PodBuilder()
                .withNewMetadata()
                .withName(workload.name())
                .withNamespace(worker.namespace())
                .withLabels(workload.labels())
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .withEnableServiceLinks(false)
                .withHostname(workload.hostname())
                .withRuntimeClassName(properties.runtimeClassName().isEmpty() ? null : properties.runtimeClassName())
                .withPriorityClassName(priorityClass(workload, properties))
                .withNodeSelector(properties.nodeSelectorMap().isEmpty() ? null : properties.nodeSelectorMap())
                .withTolerations(properties.tolerationList())
                // ADR 0068 C: no sandbox falls back to the node's user namespace.
                .withHostUsers(false)
                // No DNS: the sandbox reaches its gateway by address (CVE-2024-29018 for the Docker equivalent).
                .withDnsPolicy("None")
                .withNewDnsConfig()
                .withNameservers("0.0.0.0")
                .endDnsConfig()
                .withTerminationGracePeriodSeconds(stopGraceSeconds)
                .withActiveDeadlineSeconds(activeDeadlineSeconds)
                .withSecurityContext(new PodSecurityContextBuilder()
                        .withRunAsNonRoot(true)
                        // The emptyDir volumes belong to this group, so the non-root writer of each can write it.
                        .withFsGroup(fsGroup)
                        .withSeccompProfile(
                                seccomp.isEmpty()
                                        ? new SeccompProfile(null, "RuntimeDefault")
                                        : new SeccompProfile(seccomp, "Localhost"))
                        .build())
                .endSpec();
    }

    private ContainerBuilder base(String name, String image, long uid) {
        return new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withImagePullPolicy(pullPolicy)
                .withWorkingDir(WORKSPACE)
                .withSecurityContext(restricted(uid));
    }

    static SecurityContext restricted(long uid) {
        return new SecurityContextBuilder()
                .withRunAsUser(uid)
                .withRunAsGroup(uid)
                .withRunAsNonRoot(true)
                .withPrivileged(false)
                .withAllowPrivilegeEscalation(false)
                .withReadOnlyRootFilesystem(true)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .build();
    }

    /**
     * A sandbox container's bounds: the memory, disk and CPU that its kind requests from measured use. Its CPU limit is
     * a share of CPU time, not a reserved core.
     */
    static ResourceRequirements resources(ResourceLimits limits, Size size) {
        long millicores = Math.max(1, Math.round(limits.cpus() * 1000));
        return new ResourceRequirementsBuilder()
                .withLimits(Map.of(
                        "cpu",
                        new Quantity(millicores + "m"),
                        "memory",
                        bytes(size.memoryLimit()),
                        "ephemeral-storage",
                        bytes(size.diskLimit())))
                .withRequests(Map.of(
                        "cpu",
                        new Quantity(size.cpuRequestMillicores() + "m"),
                        "memory",
                        bytes(size.memoryRequest()),
                        "ephemeral-storage",
                        bytes(size.diskRequest())))
                .build();
    }

    /**
     * What a review Pod holds at most: its loop and both of its sides. A sidecar runs beside the loop, so a quota adds them
     * up. A review that runs no program holds less, and room is counted for the larger one.
     */
    static ResourceRequirements reviewPodResources(ResourceLimits limits) {
        ResourceRequirements loop = resources(limits, REVIEW);
        return new ResourceRequirementsBuilder()
                .withLimits(added(loop.getLimits(), TOOLS_RESOURCES.getLimits(), PROGRAMS_RESOURCES.getLimits()))
                .withRequests(
                        added(loop.getRequests(), TOOLS_RESOURCES.getRequests(), PROGRAMS_RESOURCES.getRequests()))
                .build();
    }

    @SafeVarargs
    private static Map<String, Quantity> added(Map<String, Quantity>... parts) {
        Map<String, BigDecimal> total = new TreeMap<>();
        for (Map<String, Quantity> part : parts) {
            part.forEach((name, amount) -> total.merge(name, amount.getNumericalAmount(), BigDecimal::add));
        }
        Map<String, Quantity> quantities = new TreeMap<>();
        total.forEach((name, amount) -> quantities.put(name, new Quantity(amount.toPlainString())));
        return quantities;
    }

    /** A probe Pod requests the memory it is given and writes nothing but its line of output. */
    private static Size probeSize(ResourceLimits limits, long memoryBytes) {
        long millicores = Math.max(1, Math.round(limits.cpus() * 1000));
        return new Size(
                memoryBytes,
                memoryBytes,
                SIDE_LOG_BYTES,
                SIDE_LOG_BYTES,
                SCRATCH_PATHS.size(),
                Math.max(1, millicores / 10));
    }

    private static Quantity bytes(long bytes) {
        return new Quantity(Long.toString(bytes));
    }

    /**
     * A tenth of a CPU limit. A request equal to the limit made each idle tools Pod hold a core, and an upgrade's
     * probe Pods then found no room beside the old workers' tools Pods, so the new workers never became ready.
     */
    static Quantity cpuRequest(long limitMillicores) {
        return new Quantity(Math.max(1, limitMillicores / 10) + "m");
    }

    /** The scratch places of {@link #SCRATCH_PATHS} on disk and of {@link #MEMORY_VOLUMES} in memory. */
    private static List<Volume> scratchVolumes(Size size) {
        List<Volume> list = new ArrayList<>();
        for (int index = 0; index < SCRATCH_PATHS.size(); index++) {
            list.add(diskVolume("scratch-" + index, size.volumeBytes()));
        }
        int index = 0;
        for (Long bytes : new TreeMap<>(MEMORY_VOLUMES).values()) {
            list.add(memoryVolume("memory-" + index++, bytes));
        }
        return list;
    }

    private static List<VolumeMount> scratchMounts() {
        List<VolumeMount> mounts = new ArrayList<>();
        int index = 0;
        for (String path : SCRATCH_PATHS) {
            mounts.add(mount("scratch-" + index++, path, false));
        }
        index = 0;
        for (String path : new TreeMap<>(MEMORY_VOLUMES).keySet()) {
            mounts.add(mount("memory-" + index++, path, false));
        }
        return mounts;
    }

    private static List<VolumeMount> attemptMounts(boolean runtime) {
        List<VolumeMount> mounts = new ArrayList<>();
        ATTEMPT_VOLUMES.forEach((name, path) -> mounts.add(mount(name, path, runtime && path.equals(WORKSPACE))));
        mounts.addAll(scratchMounts());
        return mounts;
    }

    /** A volume on the node's disk; the kubelet evicts the Pod when it holds more than {@code bytes}. */
    private static Volume diskVolume(String name, long bytes) {
        return new VolumeBuilder()
                .withName(name)
                .withNewEmptyDir()
                .withSizeLimit(bytes(bytes))
                .endEmptyDir()
                .build();
    }

    private static Volume memoryVolume(String name, long bytes) {
        return new VolumeBuilder()
                .withName(name)
                .withNewEmptyDir()
                .withMedium("Memory")
                .withSizeLimit(new Quantity(Long.toString(bytes)))
                .endEmptyDir()
                .build();
    }

    private static VolumeMount mount(String volume, String path, boolean readOnly) {
        return new VolumeMountBuilder()
                .withName(volume)
                .withMountPath(path)
                .withReadOnly(readOnly)
                .build();
    }

    private static Map<String, String> attemptVolumes() {
        Map<String, String> volumes = new LinkedHashMap<>();
        volumes.put("workspace", WORKSPACE);
        volumes.put("work", "/workspace/work");
        volumes.put("out", "/workspace/out");
        volumes.put("pi", "/workspace/.pi");
        volumes.put("sessions", "/workspace/.sessions");
        return volumes;
    }

    // --- API calls ---

    /** A view of the shared transports with a bounded request and no retry. */
    KubernetesClient boundedClient(int timeoutMillis) {
        var requests = new RequestConfigBuilder(client.getConfiguration().getRequestConfig())
                .withRequestTimeout(timeoutMillis)
                .withRequestRetryBackoffLimit(0)
                .build();
        return client.newClient(requests).adapt(KubernetesClient.class);
    }

    /**
     * @throws SandboxException when a Pod of the name exists: an attempt runs once
     * @throws SandboxInfrastructureException when the API refuses or fails
     */
    public void create(Pod pod) {
        try {
            client.pods().inNamespace(worker.namespace()).resource(pod).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new SandboxException("A sandbox Pod of this name already exists", e);
            }
            if (e.getCode() == 403 && e.getMessage() != null && e.getMessage().contains("exceeded quota")) {
                var full = new SandboxCapacityException(
                        "The sandbox quota has no room for another Pod",
                        SandboxCapacityException.Reason.QUOTA_EXCEEDED);
                full.initCause(e);
                throw full;
            }
            throw failure("Could not create a sandbox Pod", e);
        }
    }

    public @Nullable Pod get(String name) {
        try {
            return client.pods().inNamespace(worker.namespace()).withName(name).get();
        } catch (KubernetesClientException e) {
            throw failure("Could not read a sandbox Pod", e);
        }
    }

    /**
     * A failed API call, as a retryable {@link SandboxInfrastructureException} only when it can heal by itself: no
     * response, a timeout, throttling or a server error. A refusal (an RBAC denial, an admission rejection, an invalid
     * spec) fails the same way on every retry.
     */
    static SandboxException failure(String action, KubernetesClientException e) {
        int code = e.getCode();
        String message = action + ": HTTP " + code;
        return code == 0 || code == 408 || code == 429 || code >= 500
                ? new SandboxInfrastructureException(message, e)
                : new SandboxException(message, e);
    }

    /** The sandbox Pods of the kind. */
    public List<Pod> list(String kind) {
        try {
            return client.pods()
                    .inNamespace(worker.namespace())
                    .withLabels(worker.selector(kind))
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            throw failure("Could not list sandbox Pods", e);
        }
    }

    /**
     * Waits until the Pod is gone or {@code ended} holds for it. A Pod being deleted ends every wait.
     *
     * @throws SandboxInfrastructureException when the watch fails or the thread is interrupted
     */
    public Waited waitFor(String name, Predicate<Pod> ended, Duration timeout) {
        try {
            Pod pod = client.pods()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .waitUntilCondition(
                            candidate -> candidate == null
                                    || candidate.getMetadata().getDeletionTimestamp() != null
                                    || ended.test(candidate),
                            Math.max(1, timeout.toMillis()),
                            TimeUnit.MILLISECONDS);
            return new Waited(false, pod);
        } catch (KubernetesClientTimeoutException e) {
            return new Waited(true, null);
        } catch (KubernetesClientException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SandboxInfrastructureException("Interrupted while waiting for a sandbox Pod", e);
            }
            throw failure("Could not watch a sandbox Pod", e);
        }
    }

    /**
     * Stops the Pod as the kubelet stops a Pod past its deadline: SIGTERM, the grace period, SIGKILL. The Pod stays,
     * with its statuses and logs, until it is deleted. Returns its last state, or null when it is gone.
     */
    public @Nullable Pod stop(String name) {
        if (!terminate(name)) {
            return null;
        }
        return waitFor(
                        name,
                        KubernetesPods::terminal,
                        Duration.ofSeconds(stopGraceSeconds).plus(STOP_SETTLE))
                .pod();
    }

    /**
     * Asks the kubelet to stop the Pod, as at its deadline, and returns at once. A Pod whose deadline cannot be set is
     * deleted with its grace period instead.
     *
     * @return whether the Pod stays, with its logs
     */
    public boolean terminate(String name) {
        try {
            client.pods()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .edit(pod -> new PodBuilder(pod)
                            .editSpec()
                            .withActiveDeadlineSeconds(1L)
                            .endSpec()
                            .build());
            return true;
        } catch (KubernetesClientException e) {
            log.warn("Could not stop sandbox Pod {}: HTTP {}", name, e.getCode());
            delete(name, stopGraceSeconds);
            return false;
        }
    }

    /** Deletes the Pod; a Pod that is already gone is no error. */
    public void delete(String name, long graceSeconds) {
        try {
            client.pods()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .withGracePeriod(graceSeconds)
                    .delete();
        } catch (KubernetesClientException e) {
            throw failure("Could not delete a sandbox Pod", e);
        }
    }

    public long stopGraceSeconds() {
        return stopGraceSeconds;
    }

    /**
     * The container's log, whole or bounded as every sandbox transcript is ({@link BoundedTranscript}), or empty when it
     * cannot be read.
     */
    public String logs(String name, String container) {
        BoundedTranscript transcript = new BoundedTranscript();
        try (Reader reader = client.pods()
                .inNamespace(worker.namespace())
                .withName(name)
                .inContainer(container)
                .getLogReader()) {
            char[] buffer = new char[8192];
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                transcript.append(new String(buffer, 0, read));
            }
            return transcript.toString();
        } catch (KubernetesClientException e) {
            log.debug("Could not read the log of sandbox Pod {}: HTTP {}", name, e.getCode());
            return "";
        } catch (IOException e) {
            log.debug(
                    "Could not read the log of sandbox Pod {}: {}",
                    name,
                    e.getClass().getSimpleName());
            return transcript.toString();
        }
    }

    /**
     * The last {@code lines} lines of the container's log, read with the log API's own bounds, or empty when it cannot
     * be read.
     */
    public String tail(String name, String container, int lines, int bytes) {
        try {
            String text = client.pods()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .inContainer(container)
                    .limitBytes(bytes)
                    .tailingLines(lines)
                    .getLog();
            return text == null ? "" : text;
        } catch (KubernetesClientException e) {
            log.debug("Could not read the log tail of sandbox Pod {}: HTTP {}", name, e.getCode());
            return "";
        }
    }

    // --- Pod state ---

    public static boolean terminal(Pod pod) {
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }

    public static @Nullable Integer exitCode(Pod pod, String container) {
        ContainerStatus status = status(pod, container);
        return status == null || status.getState() == null || status.getState().getTerminated() == null
                ? null
                : status.getState().getTerminated().getExitCode();
    }

    public static boolean running(Pod pod, String container) {
        ContainerStatus status = status(pod, container);
        return status != null && status.getState() != null && status.getState().getRunning() != null;
    }

    /** The reason a container waits from which it does not start on its own, or null. */
    public static @Nullable String fatalWaiting(Pod pod) {
        return statuses(pod)
                .filter(status -> status.getState() != null && status.getState().getWaiting() != null)
                .map(status -> status.getState().getWaiting().getReason())
                .filter(reason -> reason != null && FATAL_WAITING.contains(reason))
                .findFirst()
                .orElse(null);
    }

    static boolean initializationEnded(Pod pod) {
        return terminal(pod) || exitCode(pod, INITIALIZER) != null || fatalWaiting(pod) != null;
    }

    static boolean runtimeEnded(Pod pod) {
        return terminal(pod) || exitCode(pod, LOOP) != null || fatalWaiting(pod) != null;
    }

    private static @Nullable ContainerStatus status(Pod pod, String container) {
        return statuses(pod)
                .filter(status -> container.equals(status.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Stream<ContainerStatus> statuses(Pod pod) {
        if (pod.getStatus() == null) {
            return Stream.empty();
        }
        return Stream.concat(
                listOf(pod.getStatus().getInitContainerStatuses()).stream(),
                listOf(pod.getStatus().getContainerStatuses()).stream());
    }

    private static <T> List<T> listOf(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }
}
