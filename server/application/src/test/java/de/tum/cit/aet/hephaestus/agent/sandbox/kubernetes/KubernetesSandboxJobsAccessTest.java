package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A worker without the permission to read the sandbox namespace's events, against the client's mock API server. */
@Tag("unit")
@EnableKubernetesMockClient
class KubernetesSandboxJobsAccessTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";

    private @Nullable KubernetesMockServer server;
    private @Nullable KubernetesClient client;

    @Test
    void shouldLeaveTheWaitToItsTimeoutAndStopAskingWhenTheApiDeniesTheEvents() {
        KubernetesMockServer api = Objects.requireNonNull(server);
        api.expect()
                .get()
                .withPath("/apis/batch/v1/namespaces/" + SANDBOXES + "/jobs/review-job")
                .andReturn(
                        200,
                        new JobBuilder()
                                .withNewMetadata()
                                .withName("review-job")
                                .withNamespace(SANDBOXES)
                                .withUid("uid-of-the-job")
                                .endMetadata()
                                .build())
                .always();
        api.expect()
                .get()
                .withPath("/api/v1/namespaces/" + SANDBOXES + "/events")
                .andReturn(
                        403,
                        new StatusBuilder()
                                .withCode(403)
                                .withReason("Forbidden")
                                .withMessage("events is forbidden: cannot list resource \"events\"")
                                .build())
                .always();
        var jobs = new KubernetesSandboxJobs(Objects.requireNonNull(client), KubernetesFixtures.worker(SANDBOXES));

        assertThatCode(() -> jobs.requireRoom("review-job"))
                .as("an unreadable refusal is no capacity verdict")
                .doesNotThrowAnyException();
        int asked = api.getRequestCount();
        jobs.requireRoom("review-job");

        assertThat(api.getRequestCount())
                .as("a denied worker does not ask again")
                .isEqualTo(asked);
    }
}
