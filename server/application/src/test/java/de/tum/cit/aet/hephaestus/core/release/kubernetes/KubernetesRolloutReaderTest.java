package de.tum.cit.aet.hephaestus.core.release.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.NoRequestRetries;
import de.tum.cit.aet.hephaestus.core.release.Rollout;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@EnableKubernetesMockClient(kubernetesClientBuilderCustomizer = NoRequestRetries.class)
class KubernetesRolloutReaderTest {
    private static final String NAMESPACE = "hephaestus";
    private static final String POD = "hephaestus-server-7d9f8c6b5-x2k4p";
    private static final String REPLICA_SET = "hephaestus-server-7d9f8c6b5";
    private static final String DEPLOYMENT_UID = "0f6f1e0e-2a4b-4c5d-8e9f-0a1b2c3d4e5f";
    private static final String POD_PATH = "/api/v1/namespaces/" + NAMESPACE + "/pods/" + POD;
    private static final String REPLICA_SET_PATH =
            "/apis/apps/v1/namespaces/" + NAMESPACE + "/replicasets/" + REPLICA_SET;

    private @Nullable KubernetesClient client;
    private @Nullable KubernetesMockServer server;

    private Rollout read() {
        return new KubernetesRolloutReader(Objects.requireNonNull(client), NAMESPACE, POD).read();
    }

    private void answers(String path, Object resource) {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(path)
                .andReturn(200, resource)
                .once();
    }

    private static Pod pod() {
        return new PodBuilder()
                .withNewMetadata()
                .withName(POD)
                .withNamespace(NAMESPACE)
                .addNewOwnerReference()
                .withApiVersion("apps/v1")
                .withKind("ReplicaSet")
                .withName(REPLICA_SET)
                .withUid("5d1c3a7e-0000-4000-8000-000000000001")
                .withController(true)
                .endOwnerReference()
                .endMetadata()
                .build();
    }

    private static ReplicaSetBuilder replicaSet() {
        return new ReplicaSetBuilder()
                .withNewMetadata()
                .withName(REPLICA_SET)
                .withNamespace(NAMESPACE)
                .addNewOwnerReference()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withName("hephaestus-server")
                .withUid(DEPLOYMENT_UID)
                .withController(true)
                .endOwnerReference()
                .endMetadata();
    }

    @Test
    void shouldReadTheRevisionOfThePodsReplicaSetAndItsDeployment() throws InterruptedException {
        answers(POD_PATH, pod());
        ReplicaSet owned = replicaSet()
                .editMetadata()
                .addToAnnotations(KubernetesRolloutReader.REVISION, "7")
                .endMetadata()
                .build();
        answers(REPLICA_SET_PATH, owned);

        assertThat(read()).isEqualTo(new Rollout(DEPLOYMENT_UID, 7));
        // Two GETs by name: the Role grants get on Pods and ReplicaSets, not list or watch.
        assertThat(Objects.requireNonNull(server).getRequestCount()).isEqualTo(2);
        assertThat(Objects.requireNonNull(server).getLastRequest().getPath()).isEqualTo(REPLICA_SET_PATH);
    }

    @Test
    void shouldFailWhenTheReplicaSetCarriesNoRevision() {
        answers(POD_PATH, pod());
        answers(REPLICA_SET_PATH, replicaSet().build());

        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("revision");
    }

    @Test
    void shouldFailWhenTheReadIsRefused() {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(POD_PATH)
                .andReturn(403, "")
                .once();

        assertThatThrownBy(this::read).isInstanceOf(KubernetesClientException.class);
    }
}
