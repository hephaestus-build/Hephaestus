package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Sandboxes in memory: records what was created, staged, run, cancelled and removed, and answers commands as a test
 * chooses.
 */
public final class FakeExecSandboxes implements ExecSandboxes {

    public static final String SELF = "worker-self";

    private final AtomicInteger created = new AtomicInteger();

    /** The runtime id of each running sandbox, and its sandbox id. */
    public final Map<String, String> running = new ConcurrentHashMap<>();

    public final List<String> removed = new CopyOnWriteArrayList<>();
    public final List<Command> commands = new CopyOnWriteArrayList<>();
    public final List<Staging> stagings = new CopyOnWriteArrayList<>();

    /** The sandboxes whose commands were cancelled, once for each cancellation. */
    public final List<String> cancelled = new CopyOnWriteArrayList<>();

    /** Sandboxes of other workers that {@link #list} reports besides this worker's. */
    public final List<Listed> others = new CopyOnWriteArrayList<>();

    public volatile boolean failCreate;

    /** When set, a create waits for it: a test runs a release or a close while a sandbox starts. */
    public volatile @Nullable CountDownLatch createGate;

    /** The worker's own id; null for a worker that cannot name itself. */
    public volatile @Nullable String self = SELF;

    /** What {@link #ready} answers. */
    public volatile boolean ready = true;

    public volatile Function<Command, Execution> answer = command -> new Execution(0, new byte[0], new byte[0], false);

    /** Sees each staging while its archive is still open; a staging fails when it throws a {@link SandboxException}. */
    public volatile Consumer<Staging> onStage = staging -> {};

    /** Runs at the start of each {@link #list}: a test makes the list wait, as a stalled Kubernetes API does. */
    public volatile Runnable onList = () -> {};

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public String create(String sandboxId, ResourceLimits limits) {
        if (failCreate) {
            throw new SandboxException("no Docker here");
        }
        CountDownLatch gate = createGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        String id = "container-" + created.incrementAndGet();
        running.put(id, sandboxId);
        return id;
    }

    @Override
    public Execution exec(String sandbox, Command command) {
        requireRunning(sandbox);
        commands.add(command);
        return answer.apply(command);
    }

    @Override
    public void stage(String sandbox, Staging staging) {
        requireRunning(sandbox);
        stagings.add(staging);
        onStage.accept(staging);
    }

    @Override
    public void cancelCommands(String sandbox) {
        requireRunning(sandbox);
        cancelled.add(sandbox);
    }

    private void requireRunning(String sandbox) {
        if (!running.containsKey(sandbox)) {
            throw new SandboxException("The turn sandbox is gone");
        }
    }

    @Override
    public void remove(String sandbox) {
        running.remove(sandbox);
        others.removeIf(listed -> listed.id().equals(sandbox));
        removed.add(sandbox);
    }

    @Override
    public List<Listed> list() {
        onList.run();
        List<Listed> all = new ArrayList<>(others);
        running.forEach((id, sandboxId) -> all.add(new Listed(id, sandboxId, SELF, false)));
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
