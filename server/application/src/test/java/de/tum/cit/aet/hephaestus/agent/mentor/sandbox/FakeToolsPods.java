package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** Tools Pods in memory: records what was created and removed, and refuses starts as a test chooses. */
public final class FakeToolsPods implements ToolsPods {

    public static final String SELF = "worker-self";

    private final AtomicInteger created = new AtomicInteger();

    /** The id of each running tools Pod, and its sandbox id. */
    public final Map<String, String> running = new ConcurrentHashMap<>();

    public final List<String> removed = new CopyOnWriteArrayList<>();

    /** Tools Pods of other workers that {@link #list} reports besides this worker's. */
    public final List<Listed> others = new CopyOnWriteArrayList<>();

    /** The ids of this worker's tools Pods that have ended; {@link #list} reports them as ended. */
    public final Set<String> ended = ConcurrentHashMap.newKeySet();

    public volatile boolean failCreate;
    public volatile boolean quotaFull;

    /** When set, a create waits for it: a test runs a release or a close while a tools Pod starts. */
    public volatile @Nullable CountDownLatch createGate;

    /** The worker's own id; null for a worker that cannot name itself. */
    public volatile @Nullable String self = SELF;

    /** What {@link #ready} answers. */
    public volatile boolean ready = true;

    /** Runs at the start of each {@link #list}: a test makes the list wait, as a stalled Kubernetes API does. */
    public volatile Runnable onList = () -> {};

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public Started create(String sandboxId, ResourceLimits limits) {
        if (quotaFull)
            throw new SandboxCapacityException("Tools quota full", SandboxCapacityException.Reason.QUOTA_EXCEEDED);
        if (failCreate) {
            throw new SandboxException("no Kubernetes here");
        }
        CountDownLatch gate = createGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int number = created.incrementAndGet();
        String id = "hephaestus-tools-" + number;
        running.put(id, sandboxId);
        return new Started(id, "10.0.0." + number, PORT, "nonce-" + number);
    }

    @Override
    public void remove(String id) {
        running.remove(id);
        others.removeIf(listed -> listed.id().equals(id));
        removed.add(id);
    }

    @Override
    public List<Listed> list() {
        onList.run();
        List<Listed> all = new ArrayList<>(others);
        running.forEach((id, sandboxId) -> all.add(new Listed(id, sandboxId, SELF, false, ended.contains(id))));
        return all;
    }

    @Override
    public @Nullable String workerInstance() {
        return self;
    }

    public int createdCount() {
        return created.get();
    }
}
