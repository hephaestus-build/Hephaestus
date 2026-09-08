package de.tum.cit.aet.hephaestus.agent.mentor.live;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Production-readiness stress test for the Pi mentor runner. Spawns {@code N} concurrent
 * runner subprocesses (one per simulated user-workspace), drives each through the same
 * JSON-RPC handshake {@link MentorRunnerClient} drives in prod, and captures three classes of
 * metric per session:
 *
 * <ul>
 *   <li><b>Cold-start</b> wall-clock (spawn → {@code runner_ready} → {@code hello.result})</li>
 *   <li><b>Per-runner RSS</b> sampled every 250 ms from {@code /proc/$pid/status} for the
 *       runner's lifetime — captures peak + steady-state.</li>
 *   <li><b>Turn latency</b> (prompt → settled terminal event) against the live LLM.</li>
 * </ul>
 *
 * <p>This bypasses Docker, so it measures direct runner RSS and latency without container overhead.
 *
 * <p>Run with: {@code N=10 ./gradlew :application:liveTest --tests MentorSandboxStressTest}.
 * Defaults to N=5 to stay polite on shared infra.
 */
@LiveLlmTest
@Tag("live")
class MentorSandboxStressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PI_SDK_VERSION = "0.84.4";
    private static final Path SDK_DIR = Path.of("target", "pi-sdk").toAbsolutePath();
    private static final Path RUNNER =
            Path.of("src", "main", "resources", "agent", "pi-mentor-runner.ts").toAbsolutePath();
    /** Per-session deadline: cold-start + handshake + prompt + settlement against live LLM. */
    private static final Duration SESSION_BUDGET = Duration.ofSeconds(120);

    private final List<Path> stagedWorkspaces = new CopyOnWriteArrayList<>();
    private final List<StdioAttachedSandbox> sandboxes = new CopyOnWriteArrayList<>();

    @AfterEach
    void teardown() {
        // Close all sandboxes in parallel — sequential close on 25 runners would serialise 25×
        // graceTimeout (50 s total) for no good reason.
        var closes = sandboxes.stream()
                .map(s -> CompletableFuture.runAsync(() -> s.close(Duration.ofSeconds(2))))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(closes).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort.
        }
        sandboxes.clear();
        for (Path ws : stagedWorkspaces) {
            try (var stream = Files.walk(ws)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
        stagedWorkspaces.clear();
    }

    /**
     * Per-user multi-session scenario. Spawns N runners (one per simulated user) and inside each
     * opens K mentor threads, then drives a serial K-turn round-robin: prompt thread 0 → wait for
     * settlement → prompt thread 1 → … This is the production scenario for a power user with
     * multiple mentor conversations open — sessions multiplex through one
     * {@code AgentSessionRuntime} via {@code switchSession}, not separate containers.
     *
     * <p>The interesting number is the <b>per-session marginal RSS</b>: how much extra memory each
     * extra thread costs once the Pi SDK is loaded. The single-session test gives the floor
     * (~150 MB); this test gives the slope.
     *
     * <p>Run: {@code N=3 K=5 ./gradlew :application:liveTest --tests MentorSandboxStressTest.multiSessionPerRunner}.
     */
    @Test
    void multiSessionPerRunner() throws Exception {
        int n = Integer.parseInt(System.getenv().getOrDefault("N", "3"));
        int k = Integer.parseInt(System.getenv().getOrDefault("K", "5"));
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        ensurePiSdkInstalled();

        ScheduledExecutorService sampler = Executors.newScheduledThreadPool(2);
        try {
            var runners = new ArrayList<MultiSessionRunnerMetrics>(n);
            for (int i = 0; i < n; i++) runners.add(new MultiSessionRunnerMetrics(i, k));

            long t0 = System.nanoTime();
            var futures = new ArrayList<CompletableFuture<Void>>(n);
            for (var r : runners) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        runOneMultiSessionRunner(creds, r, sampler);
                    } catch (Exception e) {
                        r.failure = e;
                    }
                }));
            }
            // Budget: cold-start + K × per-turn ceiling. K=5 → 30s cold + 5×120s = 630s ceiling.
            long budget = 30 + (long) k * SESSION_BUDGET.toSeconds() + 30;
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(budget, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            reportMultiSession(n, k, elapsedMs, runners);

            int failed = (int) runners.stream().filter(r -> r.failure != null).count();
            if (failed > 0) {
                throw new AssertionError("multi-session stress failed: " + failed + "/" + n + " runners errored");
            }
            // Hard regression budgets for multi-session: peak RSS per runner + marginal RSS per
            // extra session. The marginal slope is the headline architectural invariant — if it
            // ever exceeds ~2 MB the per-thread state has bloated and the per-user-container
            // model stops scaling. Both override via env.
            long peakRssBudgetKb = Long.parseLong(System.getenv().getOrDefault("PEAK_RSS_BUDGET_KB", "240000"));
            long marginalBudgetKb = Long.parseLong(System.getenv().getOrDefault("MARGINAL_RSS_BUDGET_KB", "2048"));
            long maxRssKb = runners.stream()
                    .filter(r -> !r.samples.isEmpty())
                    .flatMapToLong(r -> r.samples.stream().mapToLong(arr -> arr[1]))
                    .max()
                    .orElse(0L);
            if (maxRssKb > peakRssBudgetKb) {
                throw new AssertionError("multi-session peak RSS regression: max=" + maxRssKb
                        + " KB > budget="
                        + peakRssBudgetKb
                        + " KB (override via PEAK_RSS_BUDGET_KB)");
            }
            if (k > 1) {
                // Clamp negative deltas at 0 — a runner whose RSS DROPPED between the floor and
                // the K-opens sample (V8 freed memory, jemalloc decay timing) is not a
                // regression. Only growth is. The previous Math.abs() variant treated benign GC
                // timing as a "regression" with no actionable signal; this asserts only the
                // direction we care about.
                long maxMarginalGrowthKb = runners.stream()
                        .filter(r -> r.rssAfterOpenKb > 0 && r.rssOneSessionFloorKb > 0)
                        .mapToLong(r -> Math.max(0L, (r.rssAfterOpenKb - r.rssOneSessionFloorKb) / (k - 1)))
                        .max()
                        .orElse(0L);
                if (maxMarginalGrowthKb > marginalBudgetKb) {
                    throw new AssertionError("marginal RSS growth per extra session: " + maxMarginalGrowthKb
                            + " KB > budget="
                            + marginalBudgetKb
                            + " KB — per-thread state bloated. "
                            + "Override budget via MARGINAL_RSS_BUDGET_KB env.");
                }
            }
        } finally {
            sampler.shutdownNow();
        }
    }

    @Test
    @DisplayName("N concurrent runners: capture cold-start, RSS, turn latency")
    void stressNConcurrentRunners() throws Exception {
        int n = Integer.parseInt(System.getenv().getOrDefault("N", "5"));
        LiveLlmCredentials creds = LiveLlmCredentials.fromEnv();
        ensurePiSdkInstalled();

        ScheduledExecutorService sampler = Executors.newScheduledThreadPool(2);
        try {
            var sessions = new ArrayList<SessionMetrics>(n);
            for (int i = 0; i < n; i++) sessions.add(new SessionMetrics(i));

            // Spawn all N sandboxes in parallel — measures fan-out at burst, not sequential.
            long t0 = System.nanoTime();
            var spawnFutures = new ArrayList<CompletableFuture<Void>>(n);
            for (var session : sessions) {
                spawnFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        runOneSession(creds, session, sampler);
                    } catch (Exception e) {
                        session.failure = e;
                    }
                }));
            }
            CompletableFuture.allOf(spawnFutures.toArray(CompletableFuture[]::new))
                    .get(SESSION_BUDGET.toSeconds() + 30, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            report(n, elapsedMs, sessions);

            int failed = (int) sessions.stream().filter(s -> s.failure != null).count();
            if (failed > 0) {
                throw new AssertionError("stress test failed: " + failed + "/" + n + " sessions errored");
            }
            // Hard regression budgets. Without these the test is a printf with a liveness check —
            // a 10× RSS bloat or 5× cold-start regression would still pass green. Budgets are
            // generous (≥40% headroom over measured steady-state) so they don't flake on
            // shared CI hardware. Override via env for soak runs.
            long peakRssBudgetKb = Long.parseLong(System.getenv().getOrDefault("PEAK_RSS_BUDGET_KB", "240000"));
            long coldStartBudgetMs = Long.parseLong(System.getenv().getOrDefault("COLD_START_BUDGET_MS", "5000"));
            long maxRssKb = sessions.stream()
                    .filter(s -> !s.samples.isEmpty())
                    .flatMapToLong(s -> s.samples.stream().mapToLong(arr -> arr[1]))
                    .max()
                    .orElse(0L);
            long maxColdStartMs = sessions.stream()
                    .filter(s -> s.readyNanos > 0)
                    .mapToLong(s -> (s.readyNanos - s.spawnStartNanos) / 1_000_000)
                    .max()
                    .orElse(0L);
            if (maxRssKb > peakRssBudgetKb) {
                throw new AssertionError("peak RSS regression: max=" + maxRssKb
                        + " KB > budget="
                        + peakRssBudgetKb
                        + " KB (override via PEAK_RSS_BUDGET_KB env)");
            }
            if (maxColdStartMs > coldStartBudgetMs) {
                throw new AssertionError("cold-start regression: max=" + maxColdStartMs
                        + " ms > budget="
                        + coldStartBudgetMs
                        + " ms (override via COLD_START_BUDGET_MS env)");
            }
        } finally {
            sampler.shutdownNow();
        }
    }

    private void runOneSession(LiveLlmCredentials creds, SessionMetrics session, ScheduledExecutorService sampler)
            throws Exception {
        Path ws = stageWorkspace(creds, session.id);
        stagedWorkspaces.add(ws);

        session.spawnStartNanos = System.nanoTime();
        StdioAttachedSandbox sandbox = spawnRunner(creds, ws);
        sandboxes.add(sandbox);
        session.runnerPid = sandbox.process().pid();

        // Sample RSS / Threads every 250 ms while the runner is alive. Stored as a flat list of
        // (timestampMs, rssKb, threads) tuples — small enough to keep in memory for ~120 s × 4 Hz.
        var sampleFuture = sampler.scheduleAtFixedRate(
                () -> {
                    if (!sandbox.process().isAlive()) return;
                    try {
                        long[] sample = readProcStatus(session.runnerPid);
                        session.samples.add(sample);
                    } catch (IOException ignored) {
                    }
                },
                0,
                250,
                TimeUnit.MILLISECONDS);

        try {
            var driver = new RunnerDriver(sandbox);
            driver.expectRunnerReady(Duration.ofSeconds(30));
            session.readyNanos = System.nanoTime();

            driver.helloOk(Duration.ofSeconds(10));
            session.helloNanos = System.nanoTime();

            UUID threadId = UUID.randomUUID();
            driver.openThread(threadId, Duration.ofSeconds(30));
            session.openThreadNanos = System.nanoTime();

            CompletableFuture<Void> turnComplete = new CompletableFuture<>();
            sandbox.subscribe(frame -> {
                if (!"event".equals(frame.path("method").asString())) return;
                if (!threadId.toString()
                        .equals(frame.path("params").path("threadId").asString())) return;
                if ("agent_end"
                        .equals(frame.path("params").path("event").path("type").asString())) {
                    turnComplete.complete(null);
                }
            });

            driver.prompt(
                    threadId, "Answer in exactly one sentence: what is dependency injection?", Duration.ofSeconds(10));
            session.promptAcceptedNanos = System.nanoTime();

            turnComplete.get(SESSION_BUDGET.toSeconds(), TimeUnit.SECONDS);
            session.agentEndNanos = System.nanoTime();
        } finally {
            sampleFuture.cancel(false);
        }
    }

    /**
     * One runner, K threads. Opens all K threads up front (capturing each switchSession cost),
     * then drives K serial turns — one prompt per thread. We do this serially because Pi's
     * single-active-session model means concurrent prompts to different threads would queue on
     * the dispatchQueue anyway; a real user clicking between threads exhibits the same shape.
     *
     * <p>Per-RSS samples capture the growth curve: baseline after open_threadₖ, then plateau
     * during each turn. Reported numbers: RSS at each stage so we can see the marginal cost.
     */
    private void runOneMultiSessionRunner(
            LiveLlmCredentials creds, MultiSessionRunnerMetrics r, ScheduledExecutorService sampler) throws Exception {
        Path ws = stageWorkspace(creds, r.id);
        stagedWorkspaces.add(ws);

        r.spawnStartNanos = System.nanoTime();
        StdioAttachedSandbox sandbox = spawnRunner(creds, ws);
        sandboxes.add(sandbox);
        r.runnerPid = sandbox.process().pid();

        var sampleFuture = sampler.scheduleAtFixedRate(
                () -> {
                    if (!sandbox.process().isAlive()) return;
                    try {
                        r.samples.add(readProcStatus(r.runnerPid));
                    } catch (IOException ignored) {
                    }
                },
                0,
                250,
                TimeUnit.MILLISECONDS);

        try {
            var driver = new RunnerDriver(sandbox);
            driver.expectRunnerReady(Duration.ofSeconds(30));
            r.readyNanos = System.nanoTime();
            driver.helloOk(Duration.ofSeconds(10));

            // Open thread #0 first, drive ONE turn, then capture RSS — this is the true single-
            // session baseline for this exact runner. Per-extra-session marginal then becomes
            // (RSS_after_K_opens - baseline) / (K-1), avoiding the apples-vs-oranges error of
            // using the first /proc sample (before Pi SDK was loaded at all).

            var turnCompletes = new ConcurrentHashMap<UUID, CompletableFuture<Void>>();
            sandbox.subscribe(frame -> {
                if (!"event".equals(frame.path("method").asString())) return;
                String tid = frame.path("params").path("threadId").asString();
                UUID parsed;
                try {
                    parsed = UUID.fromString(tid);
                } catch (Exception e) {
                    return;
                }
                if ("agent_end"
                        .equals(frame.path("params").path("event").path("type").asString())) {
                    CompletableFuture<Void> cf = turnCompletes.get(parsed);
                    if (cf != null) cf.complete(null);
                }
            });

            // Stage 1a — open thread #0 and run one warm-up turn so the Pi runtime is fully
            // initialised. RSS sample taken here is the honest "one-session container floor".
            UUID warmupTid = UUID.randomUUID();
            r.threadIds[0] = warmupTid;
            turnCompletes.put(warmupTid, new CompletableFuture<>());
            driver.openThread(warmupTid, Duration.ofSeconds(30));
            driver.prompt(warmupTid, "Reply with the single word: ready.", Duration.ofSeconds(10));
            turnCompletes.get(warmupTid).get(SESSION_BUDGET.toSeconds(), TimeUnit.SECONDS);
            r.rssOneSessionFloorKb = currentRss(r.runnerPid);

            // Stage 1b — open threads #1..#K-1. switchSession on each.
            for (int i = 1; i < r.k; i++) {
                UUID tid = UUID.randomUUID();
                r.threadIds[i] = tid;
                turnCompletes.put(tid, new CompletableFuture<>());
                driver.openThread(tid, Duration.ofSeconds(30));
            }
            r.allThreadsOpenedNanos = System.nanoTime();
            r.rssAfterOpenKb = currentRss(r.runnerPid);

            // Stage 2 — drive K-1 more serial turns on the remaining threads (thread #0 already
            // warmed up). Total: K turns across K threads.
            for (int i = 1; i < r.k; i++) {
                UUID tid = r.threadIds[i];
                long promptStart = System.nanoTime();
                driver.prompt(
                        tid,
                        "In one sentence: what is dependency injection? (thread #" + (i + 1) + "/" + r.k + ")",
                        Duration.ofSeconds(10));
                var turnComplete = turnCompletes.get(tid);
                org.junit.jupiter.api.Assertions.assertNotNull(turnComplete);
                turnComplete.get(SESSION_BUDGET.toSeconds(), TimeUnit.SECONDS);
                long turnMs = (System.nanoTime() - promptStart) / 1_000_000;
                r.perTurnMs.add(turnMs);
            }
            r.allTurnsDoneNanos = System.nanoTime();
            r.rssAfterAllTurnsKb = currentRss(r.runnerPid);
        } finally {
            sampleFuture.cancel(false);
        }
    }

    private static long currentRss(long pid) {
        try {
            return readProcStatus(pid)[1];
        } catch (IOException e) {
            return -1L;
        }
    }

    private void reportMultiSession(int n, int k, long elapsedMs, List<MultiSessionRunnerMetrics> runners) {
        System.out.println("");
        System.out.println("══════════════════════════════════════════════════════════════════");
        System.out.printf(
                "MULTI-SESSION STRESS — N=%d runners × K=%d sessions each (wall-clock %d ms)%n", n, k, elapsedMs);
        System.out.println("══════════════════════════════════════════════════════════════════");

        long[] coldStarts = runners.stream()
                .filter(r -> r.readyNanos > 0)
                .mapToLong(r -> (r.readyNanos - r.spawnStartNanos) / 1_000_000)
                .sorted()
                .toArray();
        long[] openAllMs = runners.stream()
                .filter(r -> r.allThreadsOpenedNanos > 0)
                .mapToLong(r -> (r.allThreadsOpenedNanos - r.readyNanos) / 1_000_000)
                .sorted()
                .toArray();
        long[] turnMs = runners.stream()
                .flatMapToLong(r -> r.perTurnMs.stream().mapToLong(Long::longValue))
                .sorted()
                .toArray();

        // RSS measurements at three timepoints.
        long[] rssAtOpenAll = runners.stream()
                .filter(r -> r.rssAfterOpenKb > 0)
                .mapToLong(r -> r.rssAfterOpenKb)
                .sorted()
                .toArray();
        long[] rssAfterTurns = runners.stream()
                .filter(r -> r.rssAfterAllTurnsKb > 0)
                .mapToLong(r -> r.rssAfterAllTurnsKb)
                .sorted()
                .toArray();
        long[] peakRssKb = runners.stream()
                .filter(r -> !r.samples.isEmpty())
                .mapToLong(
                        r -> r.samples.stream().mapToLong(arr -> arr[1]).max().orElse(0))
                .sorted()
                .toArray();

        printRow("cold-start (spawn→ready)", "ms", coldStarts);
        printRow("open K threads (total)", "ms", openAllMs);
        printRow("per-turn (prompt→agent_end)", "ms", turnMs);
        printRow("RSS after K opens", "KB", rssAtOpenAll);
        printRow("RSS after K turns", "KB", rssAfterTurns);
        printRow("peak RSS per runner", "KB", peakRssKb);

        // True marginal: extra RSS each ADDITIONAL session costs on top of a fully-warm
        // one-session container. Pi SDK init + V8 baseline + per-runtime state are already
        // amortised into rssOneSessionFloorKb.
        long[] rssOneSessionFloor = runners.stream()
                .filter(r -> r.rssOneSessionFloorKb > 0)
                .mapToLong(r -> r.rssOneSessionFloorKb)
                .sorted()
                .toArray();
        printRow("RSS after 1 warm session", "KB", rssOneSessionFloor);

        long[] marginalKbPerExtraSession = runners.stream()
                .filter(r -> r.rssAfterOpenKb > 0 && r.rssOneSessionFloorKb > 0 && k > 1)
                .mapToLong(r -> {
                    long delta = r.rssAfterOpenKb - r.rssOneSessionFloorKb;
                    return delta > 0 ? delta / (k - 1) : 0L;
                })
                .filter(v -> v > 0)
                .sorted()
                .toArray();
        if (marginalKbPerExtraSession.length > 0) {
            printRow("marginal RSS / extra session", "KB", marginalKbPerExtraSession);
        }

        long totalPeakRssMb =
                (peakRssKb.length == 0 ? 0L : LongStream.of(peakRssKb).sum() / 1024L);
        long avgFloorMb = rssOneSessionFloor.length == 0
                ? 0L
                : (LongStream.of(rssOneSessionFloor).sum() / rssOneSessionFloor.length / 1024L);
        long avgMarginalKb = marginalKbPerExtraSession.length == 0
                ? 0L
                : LongStream.of(marginalKbPerExtraSession).sum() / marginalKbPerExtraSession.length;
        System.out.printf(
                "%n  ▸ aggregate peak RSS across all %d runners (× %d sessions): %d MB%n",
                peakRssKb.length, k, totalPeakRssMb);
        System.out.printf("  ▸ container floor per user (1 warm session): %d MB%n", avgFloorMb);
        if (avgMarginalKb > 0) {
            System.out.printf("  ▸ marginal RSS per extra session multiplexed in: %d KB%n", avgMarginalKb);
        }
        // Naive 1-container-per-session vs our 1-container-per-user multiplexed shape.
        long naiveMb = avgFloorMb * k;
        long actualMb = totalPeakRssMb / Math.max(1L, n);
        if (naiveMb > 0 && actualMb > 0) {
            System.out.printf(
                    "  ▸ multiplexing saving vs naive 1-container-per-session: %.1f× (%d MB → %d MB per user)%n",
                    naiveMb / (double) actualMb, naiveMb, actualMb);
        }

        long failed = runners.stream().filter(r -> r.failure != null).count();
        System.out.printf("  ▸ failures: %d / %d%n", failed, n);
        for (var r : runners) {
            if (r.failure != null) {
                System.out.printf("    [runner %d, pid %d] %s%n", r.id, r.runnerPid, r.failure);
            }
        }
        System.out.println("══════════════════════════════════════════════════════════════════");
    }

    private void report(int n, long elapsedMs, List<SessionMetrics> sessions) {
        System.out.println("");
        System.out.println("══════════════════════════════════════════════════════════════════");
        System.out.printf("STRESS REPORT — N=%d concurrent runners (wall-clock %d ms)%n", n, elapsedMs);
        System.out.println("══════════════════════════════════════════════════════════════════");

        // Cold-start: spawn → ready
        long[] coldStarts = sessions.stream()
                .filter(s -> s.readyNanos > 0)
                .mapToLong(s -> (s.readyNanos - s.spawnStartNanos) / 1_000_000)
                .sorted()
                .toArray();
        // Hello round-trip
        long[] helloMs = sessions.stream()
                .filter(s -> s.helloNanos > 0)
                .mapToLong(s -> (s.helloNanos - s.readyNanos) / 1_000_000)
                .sorted()
                .toArray();
        // open_thread
        long[] openMs = sessions.stream()
                .filter(s -> s.openThreadNanos > 0)
                .mapToLong(s -> (s.openThreadNanos - s.helloNanos) / 1_000_000)
                .sorted()
                .toArray();
        // Turn latency: prompt accepted → agent_end
        long[] turnMs = sessions.stream()
                .filter(s -> s.agentEndNanos > 0)
                .mapToLong(s -> (s.agentEndNanos - s.promptAcceptedNanos) / 1_000_000)
                .sorted()
                .toArray();

        // Per-session peak RSS.
        long[] peakRssKb = sessions.stream()
                .filter(s -> !s.samples.isEmpty())
                .mapToLong(
                        s -> s.samples.stream().mapToLong(arr -> arr[1]).max().orElse(0))
                .sorted()
                .toArray();
        long[] peakThreads = sessions.stream()
                .filter(s -> !s.samples.isEmpty())
                .mapToLong(
                        s -> s.samples.stream().mapToLong(arr -> arr[2]).max().orElse(0))
                .sorted()
                .toArray();

        printRow("cold-start (spawn→ready)", "ms", coldStarts);
        printRow("hello round-trip", "ms", helloMs);
        printRow("open_thread round-trip", "ms", openMs);
        printRow("turn (prompt→agent_end)", "ms", turnMs);
        printRow("peak RSS per runner", "KB", peakRssKb);
        printRow("peak threads per runner", "", peakThreads);

        long totalPeakRssMb =
                (peakRssKb.length == 0 ? 0L : LongStream.of(peakRssKb).sum() / 1024L);
        System.out.printf("%n  ▸ aggregate peak RSS across all %d runners: %d MB%n", peakRssKb.length, totalPeakRssMb);

        long failed = sessions.stream().filter(s -> s.failure != null).count();
        System.out.printf("  ▸ failures: %d / %d%n", failed, n);
        for (var s : sessions) {
            if (s.failure != null) {
                System.out.printf("    [session %d, pid %d] %s%n", s.id, s.runnerPid, s.failure);
            }
        }
        System.out.println("══════════════════════════════════════════════════════════════════");
    }

    /**
     * Percentile printing is gated on n ≥ 20 because below that the index for p95/p99 collapses
     * to {@code max} and printing the label invites people to read the number as statistically
     * meaningful when it isn't (Wish A/B-test percentile guidance). For small samples we print
     * min / median / max only.
     */
    private static final int PERCENTILE_MIN_N = 20;

    private static void printRow(String label, String unit, long[] sortedSamples) {
        if (sortedSamples.length == 0) {
            System.out.printf("  %-30s n=0 (no samples)%n", label);
            return;
        }
        long p50 = sortedSamples[Math.min(sortedSamples.length - 1, sortedSamples.length / 2)];
        long min = sortedSamples[0];
        long max = sortedSamples[sortedSamples.length - 1];
        long mean = LongStream.of(sortedSamples).sum() / sortedSamples.length;
        if (sortedSamples.length < PERCENTILE_MIN_N) {
            System.out.printf(
                    "  %-30s n=%3d  min=%6d  p50=%6d  max=%6d  mean=%6d  %s%n",
                    label, sortedSamples.length, min, p50, max, mean, unit);
            return;
        }
        long p95 = sortedSamples[(int) Math.min(sortedSamples.length - 1L, Math.round(sortedSamples.length * 0.95))];
        long p99 = sortedSamples[(int) Math.min(sortedSamples.length - 1L, Math.round(sortedSamples.length * 0.99))];
        System.out.printf(
                "  %-30s n=%3d  min=%6d  p50=%6d  p95=%6d  p99=%6d  max=%6d  mean=%6d  %s%n",
                label, sortedSamples.length, min, p50, p95, p99, max, mean, unit);
    }

    /** Read VmRSS (KB) and Threads from /proc/$pid/status. Returns [-1, rssKb, threads]. */
    private static long[] readProcStatus(long pid) throws IOException {
        Path status = Path.of("/proc", String.valueOf(pid), "status");
        if (!Files.exists(status)) {
            return new long[] {System.currentTimeMillis(), -1L, -1L};
        }
        long rssKb = -1L;
        long threads = -1L;
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("VmRSS:")) {
                rssKb = Long.parseLong(line.split("\\s+")[1]);
            } else if (line.startsWith("Threads:")) {
                threads = Long.parseLong(line.split("\\s+")[1]);
            }
            if (rssKb > 0 && threads > 0) break;
        }
        return new long[] {System.currentTimeMillis(), rssKb, threads};
    }

    private static void ensurePiSdkInstalled() throws Exception {
        Path marker = SDK_DIR.resolve(".installed-" + PI_SDK_VERSION);
        if (Files.exists(marker)) return;
        throw new IllegalStateException(
                "Pi SDK not installed at " + SDK_DIR + " — run MentorLiveLlmTest first to install it");
    }

    private Path stageWorkspace(LiveLlmCredentials creds, int idx) throws IOException {
        Path tmp = Files.createTempDirectory("hephaestus-mentor-stress-" + idx + "-");
        Files.createDirectories(tmp.resolve(".sessions"));
        Files.createSymbolicLink(tmp.resolve("node_modules"), SDK_DIR.resolve("node_modules"));
        Files.copy(RUNNER, tmp.resolve("pi-mentor-runner.ts"));

        Path systemPromptDir = tmp.resolve("agent").resolve("mentor");
        Files.createDirectories(systemPromptDir);
        Files.writeString(
                systemPromptDir.resolve("system.md"), "You are a software engineering mentor. Answer concisely.\n");

        PiRuntimeFactory factory = new PiRuntimeFactory(MAPPER);
        PiPlanSpec spec = new PiPlanSpec(
                "openai-completions",
                creds.model(),
                null,
                null,
                false,
                "live-test-token",
                // never actually checked — no real proxy sits in front of this test
                true,
                300,
                new MentorRunnerProfile(),
                Map.of(),
                "");
        byte[] settingsBytes = factory.buildPiSettingsJson(spec.upstreamModelId());

        Path piHome = tmp.resolve(".pi-home");
        Files.createDirectories(piHome);
        Files.write(piHome.resolve("settings.json"), settingsBytes);

        // pi-provider.json — written at the real tmp root; the runner-entry shim rewrites the
        // runner's hardcoded "/workspace/..." reads to this real path (see buildRunnerShim).
        byte[] providerConfigBytes = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(Map.of(
                        "apiProtocol",
                        spec.apiProtocol(),
                        "modelId",
                        spec.upstreamModelId(),
                        "supportsReasoning",
                        false));
        Files.write(tmp.resolve("pi-provider.json"), providerConfigBytes);
        return tmp;
    }

    private StdioAttachedSandbox spawnRunner(LiveLlmCredentials creds, Path workspace) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment();
        env.putAll(creds.asProcessEnv());
        // No real proxy sits in front of this stress test either, so LLM_PROXY_URL points straight
        // at the upstream gateway.
        env.put("LLM_PROXY_URL", creds.baseUrl());
        env.put("LLM_PROXY_TOKEN", creds.apiKey());
        env.put("PI_CODING_AGENT_DIR", workspace.resolve(".pi-home").toString());
        pb.directory(workspace.toFile());

        Path shim = workspace.resolve("runner-entry.ts");
        Path stagedRunner = workspace.resolve("pi-mentor-runner.ts");
        Files.writeString(shim, buildRunnerShim(workspace, stagedRunner));
        pb.command("node", shim.toString());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        return new StdioAttachedSandbox(
                UUID.randomUUID(), "stress-user-" + workspace.getFileName(), "stress-ws", process);
    }

    private static String buildRunnerShim(Path workspace, Path runner) {
        // JSON-encode the path literals — workspace.toString() can contain backslashes (Windows),
        // dollar signs, quotes, control chars. Hand-rolled `replace("\\", "\\\\")` covers exactly
        // one of those cases; Jackson's writeValueAsString covers all of them and produces a valid
        // JS string literal in one shot.
        String workspaceLit;
        String runnerLit;
        try {
            workspaceLit = MAPPER.writeValueAsString(workspace.toString());
            runnerLit = MAPPER.writeValueAsString(runner.toUri().toString());
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("failed to encode shim literals", e);
        }
        return ("""
            import path from "node:path";
            import fs from "node:fs";
            const WORKSPACE_REAL = __WORKSPACE__;
            function rewrite(p) {
                if (typeof p !== "string") return p;
                if (p === "/workspace") return WORKSPACE_REAL;
                if (p.startsWith("/workspace/")) return WORKSPACE_REAL + p.substring("/workspace".length);
                return p;
            }
            const origExists = fs.existsSync; fs.existsSync = (p) => origExists(rewrite(p));
            const origMkdir = fs.mkdirSync; fs.mkdirSync = (p, opts) => origMkdir(rewrite(p), opts);
            const origReadFile = fs.readFileSync; fs.readFileSync = (p, opts) => origReadFile(rewrite(p), opts);
            await import(__RUNNER_URL__);
            """).replace("__WORKSPACE__", workspaceLit).replace("__RUNNER_URL__", runnerLit);
    }

    /** Per-runner metric capture for the multi-session test. K threads opened in one runner. */
    private static final class MultiSessionRunnerMetrics {

        final int id;
        final int k;
        long runnerPid;
        long spawnStartNanos;
        long readyNanos;
        long allThreadsOpenedNanos;
        long allTurnsDoneNanos;
        long rssAfterOpenKb;
        long rssAfterAllTurnsKb;
        long rssOneSessionFloorKb;
        final UUID[] threadIds;
        final List<Long> perTurnMs = new CopyOnWriteArrayList<>();
        final List<long[]> samples = new CopyOnWriteArrayList<>();
        volatile @org.jspecify.annotations.Nullable Throwable failure;

        MultiSessionRunnerMetrics(int id, int k) {
            this.id = id;
            this.k = k;
            this.threadIds = new UUID[k];
        }
    }

    /** Per-session metric capture. All times are System.nanoTime() ticks; samples are /proc snapshots. */
    private static final class SessionMetrics {

        final int id;
        long runnerPid;
        long spawnStartNanos;
        long readyNanos;
        long helloNanos;
        long openThreadNanos;
        long promptAcceptedNanos;
        long agentEndNanos;
        final List<long[]> samples = new CopyOnWriteArrayList<>();
        volatile @org.jspecify.annotations.Nullable Throwable failure;

        SessionMetrics(int id) {
            this.id = id;
        }
    }

    /** Mirror of MentorLiveLlmTest.RunnerDriver but with explicit per-call deadlines. */
    private static final class RunnerDriver {

        private final StdioAttachedSandbox sandbox;
        private final ConcurrentLinkedQueue<JsonNode> responses = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<JsonNode> ready = new ConcurrentLinkedQueue<>();
        private final AtomicLong idGen = new AtomicLong();

        RunnerDriver(StdioAttachedSandbox sandbox) {
            this.sandbox = sandbox;
            sandbox.subscribe(frame -> {
                if (frame.has("id") && (frame.has("result") || frame.has("error"))) {
                    responses.add(frame);
                } else if ("event".equals(frame.path("method").asString())
                        && "runner_ready"
                                .equals(frame.path("params")
                                        .path("event")
                                        .path("type")
                                        .asString())) {
                    ready.add(frame);
                }
            });
        }

        void expectRunnerReady(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!ready.isEmpty()) return;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for runner_ready");
                }
            }
            throw new IllegalStateException("runner_ready not received within " + timeout);
        }

        void helloOk(Duration timeout) {
            JsonNode resp = call("hello", MAPPER.createObjectNode(), timeout);
            int version = resp.path("result").path("protocolVersion").asInt(0);
            if (version != 1) {
                throw new IllegalStateException("expected protocolVersion=1, got " + version);
            }
        }

        void openThread(UUID threadId, Duration timeout) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("threadId", threadId.toString());
            JsonNode resp = call("open_thread", params, timeout);
            if (!threadId.toString().equals(resp.path("result").path("threadId").asString())) {
                throw new IllegalStateException("open_thread did not echo threadId");
            }
        }

        void prompt(UUID threadId, String text, Duration timeout) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("threadId", threadId.toString());
            params.put("text", text);
            JsonNode resp = call("prompt", params, timeout);
            if (!resp.path("result").path("accepted").asBoolean()) {
                throw new IllegalStateException("prompt was not accepted");
            }
        }

        private JsonNode call(String method, JsonNode params, Duration timeout) {
            long id = idGen.incrementAndGet();
            ObjectNode req = MAPPER.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.set("params", params);
            sandbox.send(req);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (JsonNode c : responses) {
                    if (c.path("id").asLong(-1) == id) {
                        responses.remove(c);
                        if (c.has("error")) {
                            throw new IllegalStateException("runner error: " + c.path("error"));
                        }
                        return c;
                    }
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for response to " + method);
                }
            }
            throw new IllegalStateException(method + " response not received within " + timeout);
        }
    }
}
