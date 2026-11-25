package de.tum.cit.aet.hephaestus.core.release.kubernetes;

import de.tum.cit.aet.hephaestus.core.release.Rollout;
import de.tum.cit.aet.hephaestus.core.release.RolloutReader;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads this server's Pod, its owning ReplicaSet and that ReplicaSet's revision. The chart's Role grants {@code get}
 * on Pods and ReplicaSets of the release namespace, so the reader neither lists nor watches.
 */
class KubernetesRolloutReader implements RolloutReader, AutoCloseable {
    static final String REVISION = "deployment.kubernetes.io/revision";

    private final KubernetesClient client;
    private final String namespace;
    private final String pod;

    KubernetesRolloutReader(KubernetesClient client, String namespace, String pod) {
        this.client = client;
        this.namespace = namespace;
        this.pod = pod;
    }

    @Override
    public Rollout read() {
        Pod self = client.pods().inNamespace(namespace).withName(pod).get();
        if (self == null) throw new IllegalStateException("Pod " + pod + " is not in namespace " + namespace);
        String replicaSetName = controller(self, "ReplicaSet").getName();
        ReplicaSet replicaSet = client.apps()
                .replicaSets()
                .inNamespace(namespace)
                .withName(replicaSetName)
                .get();
        if (replicaSet == null) throw new IllegalStateException("ReplicaSet " + replicaSetName + " is gone");
        Map<String, String> annotations = replicaSet.getMetadata().getAnnotations();
        String revision = annotations == null ? null : annotations.get(REVISION);
        if (revision == null) throw new IllegalStateException("ReplicaSet " + replicaSetName + " has no " + REVISION);
        return new Rollout(controller(replicaSet, "Deployment").getUid(), Long.parseLong(revision));
    }

    private static OwnerReference controller(HasMetadata resource, String kind) {
        @Nullable List<OwnerReference> owners = resource.getMetadata().getOwnerReferences();
        return (owners == null ? List.<OwnerReference>of() : owners)
                .stream()
                        .filter(owner -> Boolean.TRUE.equals(owner.getController()) && kind.equals(owner.getKind()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(resource.getKind() + " "
                                + resource.getMetadata().getName() + " is not owned by a " + kind));
    }

    @Override
    public void close() {
        client.close();
    }
}
