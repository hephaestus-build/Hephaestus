package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The tools Jobs of conversation attempts, one per attempt identity, in the sandbox namespace. A Job's name derives
 * from its whole identity, so a Job of another attempt, lease or context is never adopted; acquiring an identity first
 * removes every other Job of the same turn and waits until Kubernetes has deleted it with its Pods. A Job whose Pod the
 * sandbox quota refuses is deleted at once and refused as busy. Any worker may discard every Job whose binding
 * PostgreSQL no longer holds ({@link #retainOnly}). The caller holds the attempt's live lease and its
 * current context; a Job carries neither, nor any credential.
 */
public class KubernetesToolsJobs implements ToolsJobs, AutoCloseable {

    /** The tool protocol's port in every tools Pod; the chart's policy names it. */
    public static final int PORT = 7070;

    /** A tools Job runs the commands of one turn attempt; its deadline, not this, ends it. */
    static final ResourceLimits LIMITS = new ResourceLimits(1.0, ResourceLimits.MAX_RUNTIME);

    private static final Duration POLL = Duration.ofMillis(200);

    private static final SecureRandom NONCES = new SecureRandom();

    private final KubernetesClient client;
    private final KubernetesPods pods;
    private final KubernetesSandboxJobs jobs;
    private final KubernetesWorker worker;
    private final String image;
    private volatile @Nullable String program;

    /**
     * The identities that a discard ended, and when. A discarded identity is never acquired again, so a discard that
     * comes before, during or after its acquire ends it; one older than any turn's runtime is forgotten.
     */
    private final Map<String, Instant> discarded = new ConcurrentHashMap<>();

    private volatile boolean closed;

    public KubernetesToolsJobs(
            KubernetesClient client,
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            KubernetesWorker worker,
            String image) {
        this.client = client;
        this.pods = pods;
        this.jobs = jobs;
        this.worker = worker;
        this.image = image;
    }

    @Override
    public Endpoint acquire(
            long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration, Instant deadline) {
        if (closed) throw new SandboxCancelledException("The worker drains and starts no tools Job");
        worker.requireIsolation();
        Identity identity = new Identity(workspaceId, turnId, attempt, leaseId, contextGeneration);
        String name = identity.name();
        if (ended(name)) throw new SandboxCancelledException("The tools Job of this attempt was discarded");
        try {
            removeOthers(identity, deadline);
            Job job = live(name, deadline);
            if (job == null) job = create(identity, deadline);
            Pod pod = ready(name, deadline);
            String address = Objects.requireNonNull(pod.getStatus().getPodIP(), "a ready Pod has an address");
            // A discard or a drain that came while this ran wins: the Job it made does not outlive it.
            if (ended(name)) {
                delete(name);
                throw new SandboxCancelledException("The tools Job was discarded while it started");
            }
            return new Endpoint(address, PORT, nonce(job));
        } catch (SandboxCapacityException refused) {
            // The quota holds no room for its Pod now: the turn's tools are busy, and the Job does not wait for room.
            delete(name);
            throw refused;
        } catch (RuntimeException e) {
            if (ended(name)) delete(name);
            throw e;
        }
    }

    private boolean ended(String name) {
        return closed || discarded.containsKey(name);
    }

    /** Refuses every later acquire; an acquire that runs now deletes the Job it started. */
    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void discard(long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration) {
        tombstoneAndDelete(new Identity(workspaceId, turnId, attempt, leaseId, contextGeneration).name());
    }

    @Override
    public void retainOnly(Collection<Binding> live) {
        Set<String> kept = live.stream()
                .map(binding -> new Identity(
                                binding.workspaceId(),
                                binding.turnId(),
                                binding.attempt(),
                                binding.leaseId(),
                                binding.contextGeneration())
                        .name())
                .collect(Collectors.toSet());
        all().stream()
                .map(job -> job.getMetadata().getName())
                .filter(name -> !kept.contains(name))
                .forEach(this::tombstoneAndDelete);
    }

    private void tombstoneAndDelete(String name) {
        Instant now = Instant.now();
        discarded.values().removeIf(at -> at.isBefore(now.minus(ResourceLimits.MAX_RUNTIME)));
        discarded.put(name, now);
        delete(name);
    }

    /** Every tools Job of this release's sandbox namespace, whichever worker started it. */
    private List<Job> all() {
        try {
            return client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withLabels(worker.selector(KubernetesWorker.KIND_TOOLS))
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not list the tools Jobs", e);
        }
    }

    /** Deletes every Job of the turn that another identity names, and waits until each is gone. */
    private void removeOthers(Identity identity, Instant deadline) {
        List<String> others = list(identity).stream()
                .map(job -> job.getMetadata().getName())
                .filter(name -> !name.equals(identity.name()))
                .toList();
        others.forEach(this::delete);
        others.forEach(name -> awaitGone(name, deadline));
    }

    /** The Job of this exact identity that still runs, after a discarded one of the same identity is gone. */
    private @Nullable Job live(String name, Instant deadline) {
        Job job = get(name);
        if (job == null || job.getMetadata().getDeletionTimestamp() == null) return job;
        awaitGone(name, deadline);
        return null;
    }

    private Job create(Identity identity, Instant deadline) {
        long seconds = Duration.between(Instant.now(), deadline).toSeconds();
        if (seconds <= 0) throw new SandboxInfrastructureException("The turn's deadline passed before its tools ran");
        byte[] secret = new byte[32];
        NONCES.nextBytes(secret);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Pod pod = pods.toolsPod(
                new KubernetesPods.Workload(
                        identity.name(), identity.labels(worker), image, KubernetesWorker.KIND_TOOLS),
                LIMITS,
                program(),
                PORT,
                nonce,
                seconds);
        Job job = jobs.job(pod, seconds);
        try {
            jobs.create(job);
            return job;
        } catch (SandboxException exists) {
            // Another acquire of this identity created it first: the same attempt shares its one tools Job.
            Job created = get(identity.name());
            if (created == null || created.getMetadata().getDeletionTimestamp() != null) throw exists;
            return created;
        }
    }

    /**
     * The ConfigMap of this worker's tools server and its protocol, named by their digest: a worker of another release
     * mounts its own, and one that exists already is the same content.
     */
    private String program() {
        String known = program;
        if (known != null) return known;
        Map<String, String> files = new TreeMap<>();
        for (String file : List.of("tools-server.ts", "tool-protocol.ts"))
            files.put(file, new String(PiRuntimeFactory.loadClasspathResource(file), StandardCharsets.UTF_8));
        String name = "hephaestus-tools-"
                + digest(String.join(
                                "\n",
                                files.entrySet().stream()
                                        .map(entry -> entry.getKey() + "\n" + entry.getValue())
                                        .toList()))
                        .substring(0, 16);
        ConfigMap map = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(worker.namespace())
                .withLabels(worker.selector(KubernetesWorker.KIND_TOOLS))
                .endMetadata()
                .withImmutable(true)
                .withData(files)
                .build();
        try {
            client.configMaps().inNamespace(worker.namespace()).resource(map).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) throw KubernetesPods.failure("Could not create the tools program", e);
        }
        program = name;
        return name;
    }

    private Pod ready(String name, Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        Gone gone = new Gone(name);
        jobs.awaitPod(name, left, () -> ended(name) || gone.now());
        while (Instant.now().isBefore(deadline) && !ended(name) && !gone.now()) {
            Pod pod = jobs.podOf(name);
            if (pod != null && isReady(pod) && pod.getStatus().getPodIP() != null) return pod;
            pause();
        }
        // Another worker's retainOnly removes a Job whose binding PostgreSQL no longer holds.
        if (ended(name) || gone.now())
            throw new SandboxCancelledException("The tools Job was discarded while it started");
        throw new SandboxInfrastructureException("The tools Pod was not ready before the turn's deadline");
    }

    /** Whether the Job is gone, read at most once a second so that a wait does not poll the API at its own pace. */
    private final class Gone {
        private final String name;
        private long nextRead = System.nanoTime();
        private boolean gone;

        Gone(String name) {
            this.name = name;
        }

        boolean now() {
            if (!gone && System.nanoTime() - nextRead >= 0) {
                gone = get(name) == null;
                nextRead = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            }
            return gone;
        }
    }

    private static boolean isReady(Pod pod) {
        List<PodCondition> conditions =
                pod.getStatus() == null ? List.of() : pod.getStatus().getConditions();
        return conditions != null
                && conditions.stream()
                        .anyMatch(condition ->
                                "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    private static String nonce(Job job) {
        return job.getSpec().getTemplate().getSpec().getContainers().stream()
                .flatMap(container -> container.getEnv().stream())
                .filter(env -> "TOOLS_NONCE".equals(env.getName()))
                .map(EnvVar::getValue)
                .findFirst()
                .orElseThrow(() -> new SandboxInfrastructureException("A tools Job has no nonce"));
    }

    private List<Job> list(Identity identity) {
        try {
            return client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withLabels(identity.turn(worker))
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not list the tools Jobs of a turn", e);
        }
    }

    private @Nullable Job get(String name) {
        try {
            return client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .get();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not read a tools Job", e);
        }
    }

    /** Deletes the Job and then its Pods, which ends every process the Pod ran; a Job that is gone is no error. */
    private void delete(String name) {
        try {
            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withName(name)
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                    .delete();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not delete a tools Job", e);
        }
    }

    /** With foreground deletion the Job stays until its Pods are gone, so its absence means that nothing of it runs. */
    private void awaitGone(String name, Instant deadline) {
        while (get(name) != null) {
            if (!Instant.now().isBefore(deadline))
                throw new SandboxInfrastructureException("An earlier tools Job of the turn was not gone in time");
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxInfrastructureException("Interrupted while waiting for a tools Job", e);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    /** One tools Job's whole identity; its name is a digest of all of it. */
    record Identity(long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration) {

        String name() {
            return "tools-"
                    + digest(workspaceId + "/" + turnId + "/" + attempt + "/" + leaseId + "/" + contextGeneration)
                            .substring(0, 24);
        }

        /** The labels that select every tools Job of this turn, whatever its attempt, lease or context. */
        Map<String, String> turn(KubernetesWorker worker) {
            Map<String, String> labels = new HashMap<>(worker.selector(KubernetesWorker.KIND_TOOLS));
            labels.put(KubernetesWorker.WORKSPACE_ID, Long.toString(workspaceId));
            labels.put(KubernetesWorker.TURN_ID, turnId.toString());
            return labels;
        }

        Map<String, String> labels(KubernetesWorker worker) {
            Map<String, String> labels = new HashMap<>(worker.labels(KubernetesWorker.KIND_TOOLS));
            labels.putAll(turn(worker));
            labels.put(KubernetesWorker.TURN_ATTEMPT, Integer.toString(attempt));
            labels.put(KubernetesWorker.LEASE_ID, leaseId.toString());
            labels.put(KubernetesWorker.CONTEXT_GENERATION, contextGeneration.toString());
            return labels;
        }
    }
}
