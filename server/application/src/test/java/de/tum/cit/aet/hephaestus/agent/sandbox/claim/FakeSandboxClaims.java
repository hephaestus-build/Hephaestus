package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The claims API in memory: each claim created and not yet deleted, by its name. */
final class FakeSandboxClaims implements SandboxClaims {
    final Map<String, Claim> created = new ConcurrentHashMap<>();
    final List<String> creates = new CopyOnWriteArrayList<>();
    volatile boolean failCreates;
    volatile boolean failDeletes;
    /** Each claim's Pod status; a claim without one has no Pod. */
    final Map<String, PodEnd> pods = new ConcurrentHashMap<>();

    volatile boolean failPodReads;

    /** Runs as a create starts, before the claim exists. */
    volatile Runnable beforeCreate = () -> {};

    @Override
    public void create(Claim claim) {
        beforeCreate.run();
        if (failCreates) throw new IllegalStateException("the API refused the claim");
        creates.add(claim.name());
        created.put(claim.name(), claim);
    }

    @Override
    public void delete(String name) {
        if (failDeletes) throw new IllegalStateException("the API refused the delete");
        created.remove(name);
    }

    @Override
    public List<String> names() {
        return List.copyOf(Set.copyOf(created.keySet()));
    }

    @Override
    public Optional<PodEnd> podEnd(String name) {
        if (failPodReads) throw new PodReadException(403, "io.fabric8.kubernetes.client.KubernetesClientException");
        return Optional.ofNullable(pods.get(name));
    }
}
