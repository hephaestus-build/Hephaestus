package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.BoundedTranscript;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
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
import java.io.IOException;
import java.io.Reader;
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
 * {@code restartPolicy: Never}. Writable places are {@code emptyDir} volumes; those that Docker gives as tmpfs are in
 * memory, with the same size.
 *
 * <p>Where Docker's host configuration has no Pod field:
 *
 * <ul>
 *   <li>The {@code nofile} 1024 and {@code core} 0 limits, soft and hard: every container command and every exec starts
 *       through {@link #RLIMITS}, which lowers both before it executes the command with its arguments unchanged. A
 *       process cannot raise a hard limit without a capability, and the containers have none.
 *   <li>A per-container process limit: the kubelet's per-Pod limit stands in where the nodes set one. The isolation
 *       probe records whether they do; without one, a sandbox's memory limit bounds its processes.
 *   <li>Docker's {@code noexec} tmpfs mounts and its IPC mode {@code none}: an {@code emptyDir} has no mount options
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
    public static final String INITIALIZER = "initialize";

    /** The review's or host's process. */
    public static final String RUNTIME = "runtime";

    /** A turn sandbox's container, which runs every untrusted command. */
    public static final String SANDBOX = "sandbox";

    /** A turn sandbox's trusted container, which only stages archives into the workspace. */
    public static final String STAGER = "stager";

    static final long SANDBOX_UID = Long.parseLong(ExecSandboxes.SANDBOX_USER);

    /** Not the sandbox user: no untrusted process can signal or trace the stager's processes. */
    static final long STAGER_UID = 1001;

    static final String WORKSPACE = "/workspace";

    /**
     * The attempt workspace of {@code DockerAttemptWorkspace}, one volume per path: the initializer writes all of them,
     * the runtime reads {@code /workspace} only.
     */
    private static final Map<String, String> ATTEMPT_VOLUMES = attemptVolumes();

    /**
     * An empty volume hides what the image keeps in {@code /workspace}, where a Docker volume is filled from the image
     * on its first mount; the runner resolves its SDK through the image's {@code /workspace/node_modules} link.
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

    private static final Quantity ISOLATION_CPU = new Quantity("100m");
    private static final Quantity ISOLATION_MEMORY = new Quantity("128Mi");

    /** The memory file systems of every review and host Pod, with their sizes: the mandatory ones of a Docker sandbox. */
    private static final Map<String, Long> MEMORY_VOLUMES = Map.of(
            "/tmp", 1_073_741_824L,
            "/run", 67_108_864L,
            "/home/agent/.local", 1_073_741_824L);

    /** The most the memory volumes of a sandbox Pod hold. Their pages count against its memory limit. */
    static final long MEMORY_VOLUME_BYTES =
            MEMORY_VOLUMES.values().stream().mapToLong(Long::longValue).sum();

    /** A turn sandbox's workspace, in memory, as Docker's {@code DockerExecSandboxes.WORKSPACE_TMPFS}. */
    static final long TURN_WORKSPACE_BYTES = 536_870_912L;

    /** A memory volume is charged to the container that writes it, so the stager holds the whole workspace. */
    private static final Quantity STAGER_MEMORY = new Quantity(Long.toString(TURN_WORKSPACE_BYTES + 134_217_728L));

    private static final long STAGER_CPU_MILLICORES = 250;

    /**
     * The turn sandbox's first process, the init process of its container's PID namespace. The kernel delivers it no
     * signal from inside the namespace that it does not handle, and {@code kill -1} spares it, so the sandbox user
     * cannot end it; it ignores the rest. Its {@code wait} reaps a process that a command left and cancellation killed.
     */
    static final List<String> SANDBOX_INIT =
            List.of("bash", "-c", "trap '' HUP INT QUIT TERM USR1 USR2 ALRM PIPE; while :; do sleep 3600; done");

    /**
     * The fixed start of every container command and every exec: Docker's {@code nofile} and {@code core} ulimits of
     * {@code ContainerSecurityPolicy}, soft and hard. {@code prlimit} (util-linux, in the agent image) sets them on
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
            Workload workload, Map<String, String> environment, List<String> command, ResourceLimits limits) {
        List<EnvVar> env = new TreeMap<>(environment)
                .entrySet().stream()
                        .map(entry -> new EnvVarBuilder()
                                .withName(entry.getKey())
                                .withValue(entry.getValue())
                                .build())
                        .toList();
        Map<String, Long> memory = MEMORY_VOLUMES;
        List<Volume> volumes = new ArrayList<>();
        ATTEMPT_VOLUMES.keySet().forEach(name -> volumes.add(diskVolume(name)));
        volumes.addAll(memoryVolumeList(memory));
        ResourceRequirements resources = resources(limits);
        Container initializer = base(INITIALIZER, workload.image(), SANDBOX_UID)
                .withCommand(limited(INITIALIZER_COMMAND))
                .withEnv(env)
                .withVolumeMounts(attemptMounts(false, memory))
                .withResources(resources)
                .build();
        Container runtime = base(RUNTIME, workload.image(), SANDBOX_UID)
                .withCommand(limited(command))
                .withEnv(env)
                .withVolumeMounts(attemptMounts(true, memory))
                .withResources(resources)
                .build();
        return pod(workload, SANDBOX_UID, null, worker.userNamespaces())
                .editSpec()
                .withInitContainers(isolation(workload.image()), initializer)
                .withContainers(runtime)
                .withVolumes(volumes)
                .endSpec()
                .build();
    }

    /**
     * A turn sandbox Pod. The sandbox container runs the untrusted commands and reads its workspace through a read-only
     * mount; the stager container alone writes it. The two do not share a PID namespace.
     */
    public Pod turnPod(Workload workload, ResourceLimits limits) {
        List<Volume> volumes = new ArrayList<>();
        volumes.add(memoryVolume("workspace", TURN_WORKSPACE_BYTES));
        volumes.addAll(memoryVolumeList(MEMORY_VOLUMES));
        List<VolumeMount> sandboxMounts = new ArrayList<>();
        sandboxMounts.add(mount("workspace", WORKSPACE, true));
        sandboxMounts.addAll(memoryMounts(MEMORY_VOLUMES));
        Container sandbox = base(SANDBOX, workload.image(), SANDBOX_UID)
                .withCommand(limited(SANDBOX_INIT))
                .withVolumeMounts(sandboxMounts)
                .withResources(resources(limits))
                .build();
        Container stager = base(STAGER, workload.image(), STAGER_UID)
                .withCommand(limited(List.of("sleep", "infinity")))
                .withVolumeMounts(mount("workspace", WORKSPACE, false))
                .withResources(new ResourceRequirementsBuilder()
                        .withLimits(Map.of("cpu", new Quantity(STAGER_CPU_MILLICORES + "m"), "memory", STAGER_MEMORY))
                        .withRequests(Map.of("cpu", cpuRequest(STAGER_CPU_MILLICORES), "memory", STAGER_MEMORY))
                        .build())
                .build();
        // The sandbox ends itself at the latest at its limits' runtime: no worker needs to be alive to bound it.
        return pod(workload, STAGER_UID, limits.maxRuntime().toSeconds(), worker.userNamespaces())
                .editSpec()
                .withInitContainers(isolation(workload.image()))
                .withContainers(sandbox, stager)
                .withVolumes(volumes)
                .endSpec()
                .build();
    }

    /**
     * A Pod of the isolation probe: one container with the sandbox's configuration that runs {@code command}.
     *
     * @param hostUsers whether the Pod runs in the node's user namespace; the probe tries it without
     */
    public Pod probePod(
            Workload workload,
            List<String> command,
            ResourceLimits limits,
            long activeDeadlineSeconds,
            boolean hostUsers) {
        Container probe = base(RUNTIME, workload.image(), SANDBOX_UID)
                .withCommand(command)
                .withVolumeMounts(memoryMounts(MEMORY_VOLUMES))
                .withResources(resources(limits))
                .build();
        return pod(workload, SANDBOX_UID, activeDeadlineSeconds, !hostUsers)
                .editSpec()
                .withInitContainers(isolation(workload.image()))
                .withContainers(probe)
                .withVolumes(memoryVolumeList(MEMORY_VOLUMES))
                .endSpec()
                .build();
    }

    /** The init container that waits for the Pod's network policy ({@link #ISOLATION_SCRIPT}). */
    private Container isolation(String image) {
        Map<String, Quantity> bounds = Map.of("cpu", ISOLATION_CPU, "memory", ISOLATION_MEMORY);
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
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return null;
        }
        return pod.getStatus().getConditions().stream()
                .filter(condition ->
                        "DisruptionTarget".equals(condition.getType()) && "True".equals(condition.getStatus()))
                .map(condition -> condition.getReason() == null ? "DisruptionTarget" : condition.getReason())
                .findFirst()
                .orElse(null);
    }

    /** Whether the Pod ended in its isolation init container: its network policy was not in force in time. */
    public static boolean notIsolated(Pod pod) {
        Integer exit = exitCode(pod, ISOLATION);
        return exit != null && exit != 0;
    }

    /** A Heph host or turn sandbox is a conversation; a review and every probe Pod rank below it. */
    private static @Nullable String priorityClass(Workload workload, KubernetesSandboxProperties properties) {
        String kind = workload.labels().get(KubernetesWorker.SANDBOX_KIND);
        boolean conversation = !workload.labels().containsKey(KubernetesWorker.PROBE)
                && (KubernetesWorker.KIND_HOST.equals(kind) || KubernetesWorker.KIND_TURN.equals(kind));
        String name = conversation ? properties.conversationPriorityClass() : properties.reviewPriorityClass();
        return name.isEmpty() ? null : name;
    }

    /**
     * @param userNamespace whether the Pod runs in a user namespace of its own ({@code hostUsers: false}): its root is
     *     then no user of the node. Only where the isolation probe showed that the nodes support it
     */
    private PodBuilder pod(
            Workload workload, long fsGroup, @Nullable Long activeDeadlineSeconds, boolean userNamespace) {
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
                .withHostUsers(userNamespace ? Boolean.FALSE : null)
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
     * A sandbox container's bounds. It requests its whole memory limit, so that the node never overcommits it. Its CPU
     * limit is a share of CPU time, as on Docker, not a reserved core: it requests a tenth of it ({@link #cpuRequest}).
     */
    private static ResourceRequirements resources(ResourceLimits limits) {
        long millicores = Math.max(1, Math.round(limits.cpus() * 1000));
        Quantity memory = new Quantity(Long.toString(limits.memoryBytes()));
        return new ResourceRequirementsBuilder()
                .withLimits(Map.of("cpu", new Quantity(millicores + "m"), "memory", memory))
                .withRequests(Map.of("cpu", cpuRequest(millicores), "memory", memory))
                .build();
    }

    /**
     * A tenth of a CPU limit. A request equal to the limit made each idle turn sandbox hold a core, and an upgrade's
     * probe Pods then found no room beside the old workers' turn sandboxes, so the new workers never became ready.
     */
    static Quantity cpuRequest(long limitMillicores) {
        return new Quantity(Math.max(1, limitMillicores / 10) + "m");
    }

    private static List<Volume> memoryVolumeList(Map<String, Long> volumes) {
        List<Volume> list = new ArrayList<>();
        int index = 0;
        for (Long bytes : new TreeMap<>(volumes).values()) {
            list.add(memoryVolume("memory-" + index++, bytes));
        }
        return list;
    }

    private static List<VolumeMount> memoryMounts(Map<String, Long> volumes) {
        List<VolumeMount> mounts = new ArrayList<>();
        int index = 0;
        for (String path : new TreeMap<>(volumes).keySet()) {
            mounts.add(mount("memory-" + index++, path, false));
        }
        return mounts;
    }

    private static List<VolumeMount> attemptMounts(boolean runtime, Map<String, Long> memory) {
        List<VolumeMount> mounts = new ArrayList<>();
        ATTEMPT_VOLUMES.forEach((name, path) -> mounts.add(mount(name, path, runtime && path.equals(WORKSPACE))));
        mounts.addAll(memoryMounts(memory));
        return mounts;
    }

    private static Volume diskVolume(String name) {
        return new VolumeBuilder()
                .withName(name)
                .withNewEmptyDir()
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
        return terminal(pod) || exitCode(pod, RUNTIME) != null || fatalWaiting(pod) != null;
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
