package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The claims API in memory: each claim created and not yet deleted, by its name. */
final class FakeSandboxClaims implements SandboxClaims {
    final Map<String, Claim> created = new ConcurrentHashMap<>();
    final List<String> creates = new CopyOnWriteArrayList<>();
    volatile boolean failCreates;
    volatile boolean failDeletes;

    @Override
    public void create(Claim claim) {
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
}
