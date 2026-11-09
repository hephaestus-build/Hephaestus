package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Tools Pods in memory: records what was created and removed, and refuses starts as a test chooses. */
public final class FakeToolsPods implements ToolsPods {

    private final AtomicInteger created = new AtomicInteger();

    /** The nonce of each running tools Pod, by its Job name. */
    public final Map<String, String> running = new ConcurrentHashMap<>();

    public final List<String> removed = new CopyOnWriteArrayList<>();

    /** Jobs that {@link #list} reports besides the running ones, as Jobs that no row may name. */
    public final List<Listed> others = new CopyOnWriteArrayList<>();

    public volatile boolean failCreate;
    public volatile boolean quotaFull;

    /** Starts that a full quota refused. */
    public final AtomicInteger quotaRefusals = new AtomicInteger();

    /** What {@link #ready} answers. */
    public volatile boolean ready = true;

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public Started create(String sandboxId, String nonce, ResourceLimits limits) {
        if (quotaFull) {
            quotaRefusals.incrementAndGet();
            throw new SandboxCapacityException("Tools quota full", SandboxCapacityException.Reason.QUOTA_EXCEEDED);
        }
        if (failCreate) {
            throw new SandboxException("no Kubernetes here");
        }
        int number = created.incrementAndGet();
        String name = ToolsPods.name(sandboxId);
        running.put(name, nonce);
        return new Started(name, "10.0." + (number / 250) + "." + (number % 250 + 1), PORT, nonce);
    }

    @Override
    public void remove(String name) {
        running.remove(name);
        others.removeIf(listed -> listed.name().equals(name));
        removed.add(name);
    }

    @Override
    public List<Listed> list() {
        List<Listed> all = new ArrayList<>(others);
        running.keySet().forEach(name -> all.add(new Listed(name, Duration.ZERO)));
        return all;
    }

    public int createdCount() {
        return created.get();
    }
}
