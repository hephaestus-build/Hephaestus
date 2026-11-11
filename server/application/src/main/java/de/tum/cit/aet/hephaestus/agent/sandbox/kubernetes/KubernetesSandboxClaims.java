package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.net.HttpURLConnection;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Claims through the generic resource API: agent-sandbox ships no Java client, and the claim is the only one of
 * its kinds that the server writes. A claim names its kind's warm pool, which holds no sandboxes, so every claim starts its sandbox cold from the
 * kind's template with the claim's environment (ADR 0079 § Subject and sandbox).
 */
public final class KubernetesSandboxClaims implements SandboxClaims {

    static final String API_VERSION = "extensions.agents.x-k8s.io/v1beta1";
    static final String KIND = "SandboxClaim";

    /** Named in full, so no call discovers the API first. */
    static final ResourceDefinitionContext CLAIMS = new ResourceDefinitionContext.Builder()
            .withGroup("extensions.agents.x-k8s.io")
            .withVersion("v1beta1")
            .withKind(KIND)
            .withPlural("sandboxclaims")
            .withNamespaced(true)
            .build();

    /** The label of the claims this owner creates. Other claims in the namespace, such as the isolation gate's, are not its. */
    static final String SANDBOX_LABEL = "hephaestus.build/sandbox-id";

    private final KubernetesClient client;
    private final String namespace;
    private final AgentSandboxProperties.WarmPools warmPools;

    public KubernetesSandboxClaims(
            KubernetesClient client, String namespace, AgentSandboxProperties.WarmPools warmPools) {
        this.client = client;
        this.namespace = namespace;
        this.warmPools = warmPools;
    }

    @Override
    public void create(Claim claim) {
        try {
            claims().resource(resource(claim)).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != HttpURLConnection.HTTP_CONFLICT) throw e;
        }
    }

    @Override
    public void delete(String name) {
        // Foreground deletion keeps the claim until its sandbox and Pod are gone. Erasure cannot acknowledge sooner.
        var claim = claims().withName(name);
        claim.withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        claim.waitUntilCondition(Objects::isNull, 30, TimeUnit.SECONDS);
    }

    @Override
    public List<String> names() {
        // A claim that is being deleted is gone for its owner; listing it again would delete it again.
        return claims().withLabel(SANDBOX_LABEL).list().getItems().stream()
                .filter(claim -> claim.getMetadata().getDeletionTimestamp() == null)
                .map(claim -> claim.getMetadata().getName())
                .toList();
    }

    GenericKubernetesResource resource(Claim claim) {
        var env = claim.env().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of("name", entry.getKey(), "value", entry.getValue()))
                .toList();
        var spec = new LinkedHashMap<String, Object>();
        spec.put("warmPoolRef", Map.of("name", warmPools.of(claim.kind())));
        spec.put(
                "lifecycle",
                Map.of(
                        "shutdownTime",
                        DateTimeFormatter.ISO_INSTANT.format(claim.shutdownAt().truncatedTo(ChronoUnit.SECONDS)),
                        "shutdownPolicy",
                        "Delete"));
        spec.put("env", env);
        return new GenericKubernetesResourceBuilder()
                .withApiVersion(API_VERSION)
                .withKind(KIND)
                .withNewMetadata()
                .withName(claim.name())
                .withNamespace(namespace)
                .addToLabels(SANDBOX_LABEL, claim.sandboxId().toString())
                .addToLabels("hephaestus.build/sandbox-kind", claim.kind().value())
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }

    private NonNamespaceOperation<
                    GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
            claims() {
        return client.genericKubernetesResources(CLAIMS).inNamespace(namespace);
    }
}
