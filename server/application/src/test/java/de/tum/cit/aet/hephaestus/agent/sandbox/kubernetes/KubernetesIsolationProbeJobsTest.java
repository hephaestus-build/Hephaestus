package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The isolation probe's Pods as Jobs, against the client's CRUD mock API server. The mock runs no Job controller, so
 * the test creates each Job's Pod as the controller would, already failed, and the probe's run ends without a result.
 */
@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesIsolationProbeJobsTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";

    private @Nullable KubernetesClient client;

    @Test
    void shouldRunEachProbePodAsABoundedJobAndLeaveNoJobWhenTheRunEnds() throws InterruptedException {
        KubernetesClient api = Objects.requireNonNull(client);
        var worker = KubernetesFixtures.worker(SANDBOXES);
        var probe = new KubernetesIsolationProbe(
                api,
                new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, worker),
                worker,
                "agent",
                9090);
        Map<String, Job> created = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread controller = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                for (Job job :
                        api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems()) {
                    if (created.putIfAbsent(job.getMetadata().getName(), job) == null) {
                        api.pods()
                                .inNamespace(SANDBOXES)
                                .resource(failedPod(job))
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

        try {
            probe.run();
        } finally {
            running.set(false);
            controller.join();
        }

        assertThat(created).hasSize(3);
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
                        quantity(KubernetesIsolationProbe.PROBE_MEMORY),
                        quantity(KubernetesIsolationProbe.PROBE_MEMORY));
        assertThat(api.batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .as("the probe deletes each of its Jobs, also when its Pod did not run")
                .isEmpty();
    }

    /** The Pod that the Job controller would create, already ended without a container that ran. */
    private static Pod failedPod(Job job) {
        String name = job.getMetadata().getName();
        return new PodBuilder()
                .withNewMetadata()
                .withName(name + "-pod")
                .withLabels(Map.of(KubernetesSandboxJobs.JOB_NAME, name))
                .endMetadata()
                .withSpec(job.getSpec().getTemplate().getSpec())
                .withNewStatus()
                .withPhase("Failed")
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
