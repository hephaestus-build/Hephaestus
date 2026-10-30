package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that the cluster enforces what the sandbox boundary needs and a Pod spec cannot state (ADR 0041 § Verification
 * gates), at the worker's start and then every {@link KubernetesSandboxProperties#probeInterval}. The worker starts no
 * sandbox while its latest run failed. A failed run at the start stops the worker: an operator sees the reason in its
 * log, and Kubernetes starts it again.
 *
 * <p>The gates, each of which must hold:
 *
 * <ul>
 *   <li><b>Restricted Pod Security.</b> The API server refuses, in a dry run, a Pod that the Baseline standard admits
 *       and the Restricted one does not.
 *   <li><b>Egress to the gateway only.</b> A Pod with a review sandbox's labels reaches this worker's gateway, which is
 *       the control, and none of: this worker's management port, the Kubernetes API, the cluster DNS server. A Pod of no
 *       sandbox kind, which the policies treat as a turn sandbox, does not reach the gateway either.
 *   <li><b>No service account token, a read-only root and a non-root user</b> inside these Pods.
 *   <li><b>The RuntimeClass</b>, when one is named: a class that the cluster does not have runs no probe Pod.
 * </ul>
 *
 * <p>The isolation tier, recorded and not required, because a conformant cluster need not have it: whether a Pod
 * without host users ({@code hostUsers: false}) gets a user namespace of its own, and how many processes a Pod can
 * start. Where user namespaces take effect, every sandbox Pod runs without host users.
 *
 * <p>The probe Pods have the sandbox's own configuration and image. Each is the Pod of a Job of its own
 * ({@link KubernetesSandboxJobs}), which the probe deletes when it ends. A worker that stops during a run leaves its
 * Jobs to the control plane: the deadline ends a Pod that still waits for a node, and the TTL controller removes the
 * Job with its Pod. Each Pod prints one line of booleans and counts, which is all the worker reads of them.
 */
public class KubernetesIsolationProbe {

    private static final Logger log = LoggerFactory.getLogger(KubernetesIsolationProbe.class);

    /** Scheduling, the pull of the agent image and the script. */
    static final Duration PROBE_TIME = Duration.ofMinutes(5);

    /** A node without user namespaces can leave the Pod without host users waiting, so it gets less time. */
    static final Duration USER_NAMESPACE_TIME = Duration.ofSeconds(90);

    /** How many processes the review probe Pod tries to start. A Pod that starts them all has no process limit. */
    static final int PROCESS_TRIAL = 1024;

    /**
     * The memory of the Pod that tries {@link #PROCESS_TRIAL} processes. In the agent image on the default runtime, Node
     * with 1024 children peaked at 204 MiB. A RuntimeClass such as gVisor charges its own overhead to the Pod, and a
     * probe Pod killed for memory would fail the gates.
     */
    static final long TRIAL_MEMORY = 512L * 1024 * 1024;

    /** The memory of a probe Pod that starts no processes: Node alone peaked at 10 MiB. */
    static final long PROBE_MEMORY = 128L * 1024 * 1024;

    static final String CLOSED_KIND = "isolation-probe";

    /** Without a user namespace of its own, a container's {@code /proc/self/uid_map} is the identity map. */
    static final String HOST_USERS_MAP = "0 0 4294967295";

    /**
     * The probe's script, which runs after the Pod's isolation init container. Argument: what to reach and how many
     * processes to start. A target counts as reached once a TCP connection opens; every target has a listener, so a
     * refused or silent one is a denied one. A target that must be reached gets ten seconds: the worker's ingress
     * policy can admit a new Pod a moment after the Pod's own policy is in force.
     */
    static final String SCRIPT = """
            const fs = require('node:fs');
            const net = require('node:net');
            const { spawn } = require('node:child_process');
            const spec = JSON.parse(process.argv[1]);
            const reach = (host, port) => new Promise((done) => {
              const socket = net.connect({ host, port, timeout: 3000 });
              socket.on('connect', () => { socket.destroy(); done(true); });
              socket.on('timeout', () => { socket.destroy(); done(false); });
              socket.on('error', () => done(false));
            });
            (async () => {
              const out = { reached: {}, uid: process.getuid() };
              out.token = fs.existsSync('/var/run/secrets/kubernetes.io/serviceaccount/token');
              try { fs.writeFileSync('/isolation-probe', ''); out.rootWritable = true; }
              catch (error) { out.rootWritable = error.code !== 'EROFS'; }
              try { out.uidMap = fs.readFileSync('/proc/self/uid_map', 'utf8').trim().split(/\\s+/).join(' '); }
              catch { out.uidMap = null; }
              for (const target of spec.targets) {
                const until = Date.now() + (target.reachable ? 10000 : 0);
                let reached = await reach(target.host, target.port);
                while (!reached && Date.now() < until) {
                  await new Promise((wait) => setTimeout(wait, 250));
                  reached = await reach(target.host, target.port);
                }
                out.reached[target.name] = reached;
              }
              if (spec.processes > 0) {
                const children = [];
                out.spawnError = null;
                for (; children.length < spec.processes; ) {
                  try {
                    const child = spawn('sleep', ['60'], { stdio: 'ignore' });
                    await new Promise((started, failed) => { child.once('spawn', started); child.once('error', failed); });
                    children.push(child);
                  } catch (error) { out.spawnError = error.code ?? 'unknown'; break; }
                }
                out.spawned = children.length;
                for (const child of children) child.kill('SIGKILL');
              }
              console.log(JSON.stringify(out));
            })();
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KubernetesClient client;
    private final KubernetesPods pods;
    private final KubernetesSandboxJobs jobs;
    private final KubernetesWorker worker;
    private final String image;
    private final int managementPort;
    private final Duration podStart;

    public KubernetesIsolationProbe(
            KubernetesClient client,
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            KubernetesWorker worker,
            String image,
            int managementPort) {
        this(client, pods, jobs, worker, image, managementPort, KubernetesSandboxJobs.CHILD_START);
    }

    /** @param podStart how long a probe Job may take to create its Pod */
    KubernetesIsolationProbe(
            KubernetesClient client,
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            KubernetesWorker worker,
            String image,
            int managementPort,
            Duration podStart) {
        this.client = client;
        this.pods = pods;
        this.jobs = jobs;
        this.worker = worker;
        this.image = image;
        this.managementPort = managementPort;
        this.podStart = podStart;
    }

    /** One address that a probe Pod tries, and whether it must reach it. */
    record Target(String name, String host, int port, boolean reachable) {}

    /**
     * What one run of the probe proved.
     *
     * @param failures the gates that did not hold; empty when the sandbox boundary holds
     * @param userNamespaces whether a Pod without host users gets a user namespace of its own
     * @param runtimeClass the RuntimeClass of the sandbox Pods; empty for the cluster's default
     * @param processLimit how many processes the review probe Pod started before the node refused one; null when it
     *     started {@link #PROCESS_TRIAL} without a refusal
     */
    public record Proof(
            Instant at,
            List<String> failures,
            boolean userNamespaces,
            String runtimeClass,
            @Nullable Integer processLimit) {
        public Proof {
            failures = List.copyOf(failures);
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        /** The isolation tier, for the operator: the runtime, the users, and the process limit. */
        public String tier() {
            return (runtimeClass.isEmpty() ? "the cluster's default runtime" : "runtime class " + runtimeClass)
                    + ", " + (userNamespaces ? "user namespaces" : "the node's users")
                    + ", "
                    + (processLimit == null
                            ? "no process limit per Pod"
                            : "about " + processLimit + " processes per Pod");
        }
    }

    /**
     * Runs the probe, records what it proved and returns it. A run that failed is logged with every gate. A run whose
     * Pods could not run, because the sandbox quota was full or the nodes had no room, proves nothing: a passing proof
     * before it stands until a later run, and the worker keeps starting sandboxes. A Job whose Pod the quota refuses
     * creates none, so such a run waits up to {@link KubernetesSandboxJobs#CHILD_START} for it.
     */
    public Proof run() {
        List<String> failures = new ArrayList<>();
        refusesUnrestrictedPods().ifPresent(failures::add);
        Target gateway = new Target("gateway", worker.properties().podIp(), worker.gatewayPort(), true);
        List<Target> review = new ArrayList<>(denied());
        review.addFirst(gateway);
        List<Target> closed = List.of(new Target(gateway.name(), gateway.host(), gateway.port(), false));
        CompletableFuture<Outcome> reviewRun = CompletableFuture.supplyAsync(
                () -> run(KubernetesWorker.KIND_REVIEW, review, PROCESS_TRIAL, true, PROBE_TIME));
        CompletableFuture<Outcome> closedRun =
                CompletableFuture.supplyAsync(() -> run(CLOSED_KIND, closed, 0, true, PROBE_TIME));
        CompletableFuture<Outcome> userNamespaceRun =
                CompletableFuture.supplyAsync(() -> run(CLOSED_KIND, List.of(), 0, false, USER_NAMESPACE_TIME));
        Outcome reviewed = reviewRun.join();
        Outcome closedOutcome = closedRun.join();
        failures.addAll(reviewed.failures());
        failures.addAll(closedOutcome.failures());
        Outcome userNamespace = userNamespaceRun.join();
        // A gate that ran and failed always counts; only Pods that did not run leave the last proof standing.
        boolean gateFailed = failures.size()
                > (reviewed.ran() ? 0 : reviewed.failures().size())
                        + (closedOutcome.ran() ? 0 : closedOutcome.failures().size());
        Proof last = worker.proof();
        if (last != null && last.passed() && !gateFailed && !failures.isEmpty()) {
            log.warn(
                    "The sandbox isolation probe could not run its Pods, so the proof of {} stands: {}",
                    last.at(),
                    String.join("; ", failures));
            return last;
        }
        Proof proof = new Proof(
                Instant.now(),
                failures,
                userNamespace.failures().isEmpty() && ownUserNamespace(userNamespace.result()),
                worker.properties().runtimeClassName(),
                processLimit(reviewed.result()));
        worker.proved(proof);
        if (proof.passed()) {
            log.info(
                    "The sandbox isolation probe passed: Restricted Pod Security, gateway-only egress, no service"
                            + " account token, a read-only root and a non-root user. Isolation tier: {}",
                    proof.tier());
        } else {
            // The shared log keeps no exception message, so the operator's reason is logged on its own.
            log.error(failureMessage(proof));
        }
        return proof;
    }

    /**
     * Runs the probe at the worker's start.
     *
     * @throws IllegalStateException naming each gate that did not hold
     */
    public Proof prove() {
        Proof proof = run();
        if (!proof.passed()) {
            throw new IllegalStateException(failureMessage(proof));
        }
        return proof;
    }

    private static String failureMessage(Proof proof) {
        return "The cluster does not hold the sandbox boundary, so this worker runs no sandbox: "
                + String.join("; ", proof.failures())
                + ". docs/admin/kubernetes.mdx § Sandbox prerequisites";
    }

    /**
     * A Pod that meets the Baseline standard and not the Restricted one: it neither drops capabilities, nor sets a
     * seccomp profile, nor runs as non-root. A dry run creates nothing.
     */
    Optional<String> refusesUnrestrictedPods() {
        Pod baseline = new PodBuilder()
                .withNewMetadata()
                .withName("hephaestus-isolation-probe-" + UUID.randomUUID())
                .withNamespace(worker.namespace())
                .endMetadata()
                .withNewSpec()
                .withAutomountServiceAccountToken(false)
                .withContainers(new ContainerBuilder()
                        .withName("baseline")
                        .withImage(image)
                        .build())
                .endSpec()
                .build();
        try {
            client.pods()
                    .inNamespace(worker.namespace())
                    .resource(baseline)
                    .dryRun()
                    .create();
            return Optional.of("the sandbox namespace admits a Pod that the Restricted Pod Security Standard refuses");
        } catch (KubernetesClientException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (e.getCode() == 403 && message.contains("PodSecurity \"restricted")) {
                return Optional.empty();
            }
            return Optional.of("the dry run of an unrestricted Pod ended with HTTP " + e.getCode()
                    + ", not with a Restricted Pod Security refusal");
        }
    }

    /**
     * What a sandbox must not reach: this worker's management port, the API server, the DNS server. The worker's
     * application port listens on loopback only, so the management port is the listener on its Pod address that only
     * the policy keeps from a sandbox.
     */
    private List<Target> denied() {
        List<Target> targets = new ArrayList<>();
        targets.add(new Target("worker management port", worker.properties().podIp(), managementPort, false));
        URI api = URI.create(client.getMasterUrl().toString());
        int apiPort = api.getPort() > 0 ? api.getPort() : 443;
        address(api.getHost()).ifPresent(host -> targets.add(new Target("Kubernetes API", host, apiPort, false)));
        nameserver().ifPresent(host -> targets.add(new Target("DNS server", host, 53, false)));
        return targets;
    }

    /** A sandbox has no DNS, so it is given addresses. */
    private static Optional<String> address(@Nullable String host) {
        if (host == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(host).getHostAddress());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** This worker's first DNS server, as its Pod has it. */
    private static Optional<String> nameserver() {
        try {
            return Files.readAllLines(Path.of("/etc/resolv.conf")).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("nameserver "))
                    .map(line -> line.substring("nameserver ".length()).strip())
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * What one probe Pod showed: the gates that did not hold, and the line it printed.
     *
     * @param ran whether the Pod started and ended; one that the Job controller did not create, for example because
     *     the sandbox quota was full, or that the nodes did not schedule did not
     */
    record Outcome(List<String> failures, @Nullable JsonNode result, boolean ran) {}

    /**
     * Runs one probe Pod.
     *
     * @param hostUsers whether the Pod runs with the node's users; the tier's Pod runs without them
     */
    private Outcome run(String kind, List<Target> targets, int processes, boolean hostUsers, Duration time) {
        // A sandbox's own labels: the chart's gateway policy admits a review Pod by its kind.
        Map<String, String> labels = new LinkedHashMap<>(worker.labels(kind));
        labels.put(KubernetesWorker.PROBE, "true");
        String name = "hephaestus-isolation-probe-" + kind + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String probe = hostUsers ? "the " + kind + " probe Pod" : "the probe Pod without host users";
        var spec = JSON.createObjectNode();
        var list = spec.putArray("targets");
        targets.forEach(target -> list.addObject()
                .put("name", target.name())
                .put("host", target.host())
                .put("port", target.port())
                .put("reachable", target.reachable()));
        spec.put("processes", processes);
        long memory = processes > 0 ? TRIAL_MEMORY : PROBE_MEMORY;
        ResourceLimits limits = new ResourceLimits(0.5, time);
        Pod pod = pods.probePod(
                new KubernetesPods.Workload(name, labels, image, "probe"),
                List.of("node", "-e", SCRIPT, spec.toString()),
                limits,
                memory,
                time.toSeconds(),
                hostUsers);
        Instant end = Instant.now().plus(time);
        try {
            jobs.create(jobs.job(pod, time.toSeconds()));
            String created = jobs.awaitPod(name, min(time, podStart), () -> false)
                    .getMetadata()
                    .getName();
            Duration left = Duration.between(Instant.now(), end);
            KubernetesPods.Waited ended = pods.waitFor(
                    created,
                    candidate -> KubernetesPods.terminal(candidate) || KubernetesPods.fatalWaiting(candidate) != null,
                    left.isNegative() ? Duration.ZERO : left);
            Pod last = ended.pod();
            if (last != null && KubernetesPods.notIsolated(last)) {
                return new Outcome(
                        List.of("the network policy did not filter " + probe + " within "
                                + KubernetesPods.ISOLATION_WAIT.toSeconds() + " seconds"),
                        null,
                        true);
            }
            if (ended.timedOut() || last == null || KubernetesPods.exitCode(last, KubernetesPods.RUNTIME) == null) {
                String waiting = last == null ? null : KubernetesPods.fatalWaiting(last);
                return new Outcome(
                        List.of(probe + " did not run" + (waiting == null ? "" : ": " + waiting)), null, false);
            }
            JsonNode result = parse(pods.logs(created, KubernetesPods.RUNTIME));
            if (result == null) {
                return new Outcome(List.of(probe + " printed no result"), null, true);
            }
            return new Outcome(judge(probe, targets, result), result, true);
        } catch (RuntimeException e) {
            return new Outcome(List.of(probe + " failed: " + e.getMessage()), null, false);
        } finally {
            try {
                jobs.delete(name);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not delete isolation probe Job {}: {}",
                        name,
                        e.getClass().getSimpleName());
            }
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /** The last line of the probe's output, when it is a JSON object. */
    static @Nullable JsonNode parse(String output) {
        try {
            JsonNode result = JSON.readTree(
                    output.strip().lines().reduce((first, last) -> last).orElse(""));
            return result.isObject() ? result : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The gates that a probe Pod's line shows not to hold, against what each target requires. */
    static List<String> judge(String probe, List<Target> targets, JsonNode result) {
        List<String> failures = new ArrayList<>();
        for (Target target : targets) {
            JsonNode reached = result.path("reached").path(target.name());
            if (!reached.isBoolean()) {
                failures.add(probe + " did not try the " + target.name());
            } else if (reached.booleanValue() != target.reachable()) {
                failures.add(probe + (target.reachable() ? " does not reach" : " reaches") + " the " + target.name()
                        + " (" + target.host() + ":" + target.port() + ")");
            }
        }
        if (!result.path("token").isBoolean() || result.path("token").booleanValue()) {
            failures.add(probe + " has a service account token");
        }
        if (!result.path("rootWritable").isBoolean()
                || result.path("rootWritable").booleanValue()) {
            failures.add(probe + " can write its root file system");
        }
        if (!result.path("uid").isIntegralNumber() || result.path("uid").intValue() == 0) {
            failures.add(probe + " runs as root");
        }
        return failures;
    }

    /** Whether the Pod's users are not the node's: its {@code uid_map} is not the identity map. */
    static boolean ownUserNamespace(@Nullable JsonNode result) {
        if (result == null || !result.path("uidMap").isString()) {
            return false;
        }
        return !HOST_USERS_MAP.equals(result.path("uidMap").stringValue());
    }

    /** How many processes the Pod started before the node refused one, or null when it started every one it tried. */
    static @Nullable Integer processLimit(@Nullable JsonNode result) {
        if (result == null || !result.path("spawned").isIntegralNumber()) {
            return null;
        }
        int spawned = result.path("spawned").intValue();
        return "EAGAIN".equals(result.path("spawnError").asString("")) ? spawned : null;
    }
}
