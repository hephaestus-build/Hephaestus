package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.BoundedTranscript;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
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
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>Network isolation is the chart's NetworkPolicies. The isolation probe proves at the server's start that the
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

    static final String WORKSPACE = "/workspace";

    /** Starts the tools program under its system call filter, on either side. */
    static final List<String> TOOLS_COMMAND = List.of("sh", "/opt/pi-sdk/tools-launch.sh");

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

    /** Waiting reasons from which a container does not start on its own. */
    private static final Set<String> FATAL_WAITING = Set.of(
            "ErrImageNeverPull",
            "InvalidImageName",
            "CreateContainerConfigError",
            "CreateContainerError",
            "RunContainerError");

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

    /** How long a deleted Pod may take, beyond its grace period, to report its runtime's exit. */
    private static final Duration STOP_SETTLE = Duration.ofSeconds(30);

    /** The command under the system call filter of the tools program (see tools-launch.sh). */
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

    private static EnvVar variable(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /**
     * A conversation's tools Pod: the review sidecar's tools program as its only container, serving its host on TCP. It
     * gets the nonce and Git's settings and no credential; the isolation init container holds it until the network
     * policy that leaves it no egress is in force. It ends itself at the latest at its limits' runtime, so no server needs
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

    /** A tools Pod is a conversation; a review and every probe Pod rank below it. */
    private static @Nullable String priorityClass(Workload workload, KubernetesSandboxProperties properties) {
        String kind = workload.labels().get(KubernetesWorker.SANDBOX_KIND);
        boolean conversation =
                !workload.labels().containsKey(KubernetesWorker.PROBE) && KubernetesWorker.KIND_TOOLS.equals(kind);
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
     * probe Pods then found no room beside the old servers' tools Pods, so the new servers never became ready.
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

    // --- API calls ---

    /** A view of the shared transports with a bounded request and no retry. */
    KubernetesClient boundedClient(int timeoutMillis) {
        var requests = new RequestConfigBuilder(client.getConfiguration().getRequestConfig())
                .withRequestTimeout(timeoutMillis)
                .withRequestRetryBackoffLimit(0)
                .build();
        return client.newClient(requests).adapt(KubernetesClient.class);
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
     * Waits for the runtime of a Pod that is being deleted to exit. The deletion stops it as a deadline does (SIGTERM,
     * the grace period, SIGKILL), and the kubelet reports the exit before the Pod goes, so the runtime can still upload
     * its result in the grace period.
     *
     * @return the Pod with its runtime's exit, or null when the Pod went first or reported no exit in time
     * @throws SandboxInfrastructureException when the watch fails or the thread is interrupted
     */
    public @Nullable Pod awaitRuntimeExit(String name) {
        try {
            return client.pods()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .waitUntilCondition(
                            candidate -> candidate == null || runtimeEnded(candidate),
                            Duration.ofSeconds(stopGraceSeconds)
                                    .plus(STOP_SETTLE)
                                    .toMillis(),
                            TimeUnit.MILLISECONDS);
        } catch (KubernetesClientTimeoutException e) {
            return null;
        } catch (KubernetesClientException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SandboxInfrastructureException("Interrupted while waiting for a sandbox Pod", e);
            }
            throw failure("Could not watch a sandbox Pod", e);
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
