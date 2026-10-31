package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The isolation probe's Pods as Jobs, against the client's CRUD mock API server. The mock runs no Job controller, so
 * each test plays it: it creates a Pod whose runtime refuses user namespaces, or records the quota's refusal.
 */
@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesIsolationProbeJobsTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";

    private @Nullable KubernetesMockServer server;
    private @Nullable KubernetesClient client;

    @Test
    void shouldRunEachProbePodAsABoundedJobAndLeaveNoJobWhenTheRunEnds() throws InterruptedException {
        KubernetesClient api = Objects.requireNonNull(client);
        Objects.requireNonNull(server)
                .expect()
                .post()
                .withPath("/api/v1/namespaces/" + SANDBOXES + "/pods?dryRun=All")
                .andReturn(
                        403,
                        new StatusBuilder()
                                .withCode(403)
                                .withMessage("violates PodSecurity \"restricted:latest\"")
                                .build())
                .once();
        var worker = KubernetesFixtures.worker(SANDBOXES);
        var probe = new KubernetesIsolationProbe(
                api,
                new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, worker),
                worker,
                "agent",
                9090);
        worker.proved(new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "", 248));
        Map<String, Job> created = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread controller = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                for (Job job :
                        api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems()) {
                    if (created.putIfAbsent(job.getMetadata().getName(), job) == null) {
                        api.pods()
                                .inNamespace(SANDBOXES)
                                .resource(unstartablePod(job))
                                .create();
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        KubernetesIsolationProbe.Proof proof;
        try {
            proof = probe.run();
        } finally {
            running.set(false);
            controller.join();
        }

        assertThat(proof.passed()).isFalse();
        assertThat(worker.isolated()).isFalse();
        assertThat(proof.failures())
                .hasSize(2)
                .allSatisfy(failure -> assertThat(failure).contains("did not start: CreateContainerError"));
        assertThat(created).hasSize(2);
        assertThat(created.values()).allSatisfy(job -> {
            assertThat(job.getSpec().getBackoffLimit()).isZero();
            assertThat(job.getSpec().getTtlSecondsAfterFinished())
                    .isEqualTo((int) KubernetesSandboxJobs.TTL_AFTER_FINISHED.toSeconds());
            assertThat(job.getSpec().getActiveDeadlineSeconds()).isPositive();
            assertThat(job.getMetadata().getLabels()).containsEntry(KubernetesWorker.PROBE, "true");
        });
        assertThat(created.values().stream().map(KubernetesIsolationProbeJobsTest::memory))
                .containsExactlyInAnyOrder(
                        quantity(KubernetesIsolationProbe.TRIAL_MEMORY),
                        quantity(KubernetesIsolationProbe.PROBE_MEMORY));
        assertThat(api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .as("the probe deletes each of its Jobs, also when its Pod did not run")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepOnlyAnExistingProofWhenTheQuotaRefusesThePodsOfTheProbeJobs(boolean previousProof)
            throws InterruptedException {
        KubernetesClient api = Objects.requireNonNull(client);
        // The admission gate holds, so only the Pods that did not run can end the probe's run.
        Objects.requireNonNull(server)
                .expect()
                .post()
                .withPath("/api/v1/namespaces/" + SANDBOXES + "/pods?dryRun=All")
                .andReturn(
                        403,
                        new StatusBuilder()
                                .withCode(403)
                                .withReason("Forbidden")
                                .withMessage("pods \"probe\" is forbidden: violates PodSecurity \"restricted:latest\"")
                                .build())
                .once();
        var worker = KubernetesFixtures.worker(SANDBOXES);
        var probe = new KubernetesIsolationProbe(
                api,
                new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, worker),
                worker,
                "agent",
                9090,
                Duration.ofSeconds(1));
        var passed = new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "", 248);
        if (previousProof) {
            worker.proved(passed);
        }
        Map<String, Job> refused = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);
        // The Job controller under a full quota: a FailedCreate event for each Job, and no Pod. The probe's wait ends
        // when no Pod comes within the start wait; a reader of these events can end it sooner, as the same outcome.
        Thread controller = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                for (Job job :
                        api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems()) {
                    if (refused.putIfAbsent(job.getMetadata().getName(), job) == null) {
                        api.v1()
                                .events()
                                .inNamespace(SANDBOXES)
                                .resource(refusedByTheQuota(job))
                                .create();
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        KubernetesIsolationProbe.Proof proof;
        try {
            proof = probe.run();
        } finally {
            running.set(false);
            controller.join();
        }

        assertThat(refused).hasSize(2);
        if (previousProof) {
            assertThat(proof)
                    .as("a quota without room proves nothing about isolation")
                    .isSameAs(passed);
        } else {
            assertThat(proof.passed()).isFalse();
            assertThat(proof.failures())
                    .allSatisfy(failure -> assertThat(failure).contains("probe Pod"));
        }
        assertThat(worker.isolated()).isEqualTo(previousProof);
        assertThat(api.pods().inNamespace(SANDBOXES).list().getItems()).isEmpty();
        assertThat(api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .as("the probe deletes each Job that created no Pod")
                .isEmpty();
    }

    /** The event that the Job controller records when the sandbox quota refuses the Job's Pod. */
    private static Event refusedByTheQuota(Job job) {
        String name = job.getMetadata().getName();
        return new EventBuilder()
                .withNewMetadata()
                .withName(name + ".refused")
                .endMetadata()
                .withNewInvolvedObject()
                .withKind("Job")
                .withName(name)
                .withNamespace(SANDBOXES)
                .withUid(job.getMetadata().getUid())
                .endInvolvedObject()
                .withType("Warning")
                .withReason("FailedCreate")
                .withMessage("Error creating: pods \"" + name + "-x\" is forbidden: exceeded quota: sandboxes,"
                        + " requested: requests.memory=128Mi, used: requests.memory=4Gi, limited: requests.memory=4Gi")
                .build();
    }

    /** A scheduled Pod whose runtime cannot create the required user namespace. */
    private static Pod unstartablePod(Job job) {
        String name = job.getMetadata().getName();
        return new PodBuilder()
                .withNewMetadata()
                .withName(name + "-pod")
                .withLabels(Map.of(KubernetesSandboxJobs.JOB_NAME, name))
                .endMetadata()
                .withSpec(job.getSpec().getTemplate().getSpec())
                .editSpec()
                .withNodeName("sandbox-node")
                .endSpec()
                .withNewStatus()
                .withPhase("Pending")
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName(KubernetesPods.RUNTIME)
                        .withNewState()
                        .withNewWaiting()
                        .withReason("CreateContainerError")
                        .withMessage("cannot create the required user namespace")
                        .endWaiting()
                        .endState()
                        .build())
                .endStatus()
                .build();
    }

    private static Quantity memory(Job job) {
        return Objects.requireNonNull(job.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .getFirst()
                .getResources()
                .getRequests()
                .get("memory"));
    }

    private static Quantity quantity(long bytes) {
        return new Quantity(Long.toString(bytes));
    }
}
