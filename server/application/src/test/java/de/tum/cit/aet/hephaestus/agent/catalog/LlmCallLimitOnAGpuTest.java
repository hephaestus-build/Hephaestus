package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The learned limit against a model of a GPU server, in virtual time. Like vLLM by default, the server never refuses: it
 * runs a batch of slots, shares its token rate among them, shares prefill among new calls, and queues the rest.
 */
@Tag("unit")
class LlmCallLimitOnAGpuTest {

    private static final double STEP_SECONDS = 0.05;
    private static final double ANSWER_TOKENS = 500;
    private static final int PROMPT_TOKENS = 3_900;

    /** The calls that want to run at once, far past any knee here. */
    private static final int DEMAND = 200;

    /** A GPU server. A batch of more streams gives each less, and more in total, until the batch is full. */
    private static final class Gpu {
        final int slots;
        final double firstStreamRate;
        final double batchCost;
        double prefillRate;
        final Deque<Call> waiting = new ArrayDeque<>();
        final List<Call> running = new ArrayList<>();

        Gpu(int slots, double firstStreamRate, double batchCost, double prefillRate) {
            this.slots = slots;
            this.firstStreamRate = firstStreamRate;
            this.batchCost = batchCost;
            this.prefillRate = prefillRate;
        }

        static Gpu typical() {
            return new Gpu(24, 40, 0.06, 4_000);
        }

        double streamRate(int streams) {
            return firstStreamRate / (1 + batchCost * (streams - 1));
        }
    }

    private static final class Call {
        final double sentAt;
        double prompt;
        double answer;
        final double answerTokens;
        final int markAtStart;
        final boolean ours;
        double firstTokenAt = -1;
        double seconds;

        Call(double sentAt, double prompt, double answer, int mark, boolean ours) {
            this.sentAt = sentAt;
            this.prompt = prompt;
            this.answer = answer;
            this.answerTokens = answer;
            this.markAtStart = mark;
            this.ours = ours;
        }
    }

    /** The decisions of the pool's row, as the permit code makes them. */
    private static final class Pool {
        LlmCallLimit limit = new LlmCallLimit(4, 0);
        LlmCallDelay delay = new LlmCallDelay(null, null, 0);
        double throttledAt = Double.NEGATIVE_INFINITY;
        int mark;
        int decreases;
        int lowest = 4;
        final boolean delaySignal;

        Pool(boolean delaySignal) {
            this.delaySignal = delaySignal;
        }

        int backgroundCap() {
            return limit.learned() - LlmCallLimit.reserve(limit.learned());
        }

        void firstToken(Call call, double now) {
            if (!delaySignal || call.markAtStart != mark) return;
            var step = delay.afterFirstToken((int) ((now - call.sentAt) * 1000), limit.learned());
            delay = step.delay();
            if (step.keep() < 1) {
                limit = limit.afterDelay(step.keep());
                delay = new LlmCallDelay(delay.baselineMillis(), null, 0);
                throttledAt = now;
                mark++;
                decreases++;
                lowest = Math.min(lowest, limit.learned());
            }
        }

        void served(Call call, double now, int active) {
            if (call.markAtStart != mark) return;
            int ms = (int) ((call.firstTokenAt - call.sentAt) * 1000);
            if (delaySignal && delay.queued(ms)) return;
            if (active < limit.learned() && active < backgroundCap()) return;
            limit = limit.afterServedCall(now - throttledAt > 600);
        }
    }

    private record Result(
            double tokensPerSecond,
            double inFlight,
            double limit,
            double firstTokenP50,
            double firstTokenP95,
            double streamRate,
            int lowestLimit,
            int decreases,
            @Nullable Integer baselineMillis) {}

    private interface Scene {
        /** Called each step, for what changes while the run lasts: a slower server, another tenant. */
        void at(double now, Gpu gpu, Random random);
    }

    private static Result run(
            Gpu gpu,
            @Nullable Integer fixed,
            boolean delaySignal,
            double seconds,
            ToDoubleFunction<Random> prompt,
            Scene scene) {
        Random random = new Random(1);
        Pool pool = new Pool(delaySignal);
        List<Call> ours = new ArrayList<>();
        List<Double> firstTokens = new ArrayList<>();
        List<Double> rates = new ArrayList<>();
        double from = seconds / 2;
        double tokens = 0;
        double inFlight = 0;
        double limit = 0;
        int steps = 0;
        for (double now = 0; now < seconds; now += STEP_SECONDS) {
            scene.at(now, gpu, random);
            int wanted = fixed != null ? fixed : Math.min(DEMAND, pool.backgroundCap());
            while (ours.size() < wanted) {
                double answer = Math.max(10, ANSWER_TOKENS * Math.exp(0.8 * random.nextGaussian()));
                Call call = new Call(now, prompt.applyAsDouble(random), answer, pool.mark, true);
                ours.add(call);
                gpu.waiting.add(call);
            }
            while (!gpu.waiting.isEmpty() && gpu.running.size() < gpu.slots) gpu.running.add(gpu.waiting.poll());
            double rate = gpu.streamRate(gpu.running.size());
            long prefilling =
                    gpu.running.stream().filter(call -> call.prompt > 0).count();
            for (Call call : new ArrayList<>(gpu.running)) {
                if (call.prompt > 0) {
                    call.prompt -= STEP_SECONDS * gpu.prefillRate / prefilling;
                    continue;
                }
                if (call.firstTokenAt < 0) {
                    call.firstTokenAt = now;
                    if (call.ours) {
                        pool.firstToken(call, now);
                        if (now >= from) firstTokens.add(now - call.sentAt);
                    }
                }
                call.answer -= rate * STEP_SECONDS;
                call.seconds += STEP_SECONDS;
                if (now >= from && call.ours) tokens += rate * STEP_SECONDS;
                if (call.answer <= 0) {
                    gpu.running.remove(call);
                    if (call.ours) {
                        if (now >= from) rates.add(call.answerTokens / call.seconds);
                        pool.served(call, now, ours.size());
                        ours.remove(call);
                    } else {
                        gpu.waiting.add(new Call(now, PROMPT_TOKENS, ANSWER_TOKENS, 0, false));
                    }
                }
            }
            if (now >= from) {
                inFlight += ours.size();
                limit += pool.limit.learned();
                steps++;
            }
        }
        Collections.sort(firstTokens);
        Collections.sort(rates);
        return new Result(
                tokens / (seconds - from),
                inFlight / steps,
                limit / steps,
                percentile(firstTokens, 0.5),
                percentile(firstTokens, 0.95),
                percentile(rates, 0.5),
                pool.lowest,
                pool.decreases,
                pool.delay.baselineMillis());
    }

    private static double percentile(List<Double> sorted, double share) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (share * sorted.size())));
    }

    private static Result adaptive(Gpu gpu, double seconds) {
        return run(gpu, null, true, seconds, random -> PROMPT_TOKENS, (now, server, random) -> {});
    }

    private static Result fixed(Gpu gpu, int calls) {
        return run(gpu, calls, true, 900, random -> PROMPT_TOKENS, (now, server, random) -> {});
    }

    @Test
    void shouldSettleNearTheKneeAndKeepTotalThroughputNearItsPeak(TestReporter reporter) {
        double peak = fixed(Gpu.typical(), 24).tokensPerSecond();
        Result learned = adaptive(Gpu.typical(), 2_400);

        reporter.publishEntry("knee", learned.toString());
        assertThat(learned.inFlight()).isBetween(20.0, 28.0);
        assertThat(learned.tokensPerSecond()).isGreaterThan(0.9 * peak);
        assertThat(learned.firstTokenP50()).isLessThan(5.0);
    }

    @Test
    void shouldRunAwayFromTheKneeWithoutTheDelaySignalBecauseTheServerNeverRefuses(TestReporter reporter) {
        Result refusalsOnly =
                run(Gpu.typical(), null, false, 2_400, random -> PROMPT_TOKENS, (now, server, random) -> {});

        reporter.publishEntry("refusalsOnly", refusalsOnly.toString());
        assertThat(refusalsOnly.limit()).isGreaterThan(100);
        assertThat(refusalsOnly.firstTokenP50()).isGreaterThan(60);
    }

    @Test
    void shouldReadTheQueueInFirstTokensBecauseAStreamsRateStopsFallingAtTheKnee(TestReporter reporter) {
        Result atTheKnee = fixed(Gpu.typical(), 24);
        Result farPastIt = fixed(Gpu.typical(), 96);

        reporter.publishEntry("signals", atTheKnee + " vs " + farPastIt);
        assertThat(farPastIt.streamRate()).isBetween(0.9 * atTheKnee.streamRate(), 1.1 * atTheKnee.streamRate());
        assertThat(farPastIt.firstTokenP50()).isGreaterThan(10 * atTheKnee.firstTokenP50());
    }

    @Test
    void shouldNeverFallBelowTheFloorAtAnySize(TestReporter reporter) {
        for (int slots : new int[] {1, 2, 4, 8, 64}) {
            Result learned = adaptive(new Gpu(slots, 40, 0.02, 40_000), 2_400);

            reporter.publishEntry("slots" + slots, learned.toString());
            assertThat(learned.lowestLimit()).isGreaterThanOrEqualTo(LlmCallLimit.FLOOR);
            assertThat(learned.inFlight()).isLessThanOrEqualTo(1.3 * slots + 1);
        }
    }

    @Test
    void shouldComeBackWhenTheServerBecomesSlowerForGoodSoThatTheOldBaselineIsWrong(TestReporter reporter) {
        Result full =
                run(new Gpu(24, 40, 0.06, 800), 24, true, 900, random -> PROMPT_TOKENS, (now, server, random) -> {});
        Result slower = run(Gpu.typical(), null, true, 7_200, random -> PROMPT_TOKENS, (now, server, random) -> {
            if (now >= 1_800) server.prefillRate = 800;
        });

        reporter.publishEntry("slowerServer", slower + " full " + full);
        assertThat(slower.lowestLimit()).isEqualTo(LlmCallLimit.FLOOR);
        assertThat(slower.baselineMillis()).isGreaterThan(2_000);
        assertThat(slower.limit()).isGreaterThan(LlmCallLimit.FLOOR);
        // Prefill is this server's limit: more calls would add tokens, at a first token many times later.
        assertThat(slower.tokensPerSecond()).isGreaterThan(0.5 * full.tokensPerSecond());
        assertThat(slower.firstTokenP50()).isLessThan(full.firstTokenP50());
    }

    @Test
    void shouldNotTakeProvidersWithSlowFirstTokensForOverload(TestReporter reporter) {
        Result full =
                run(new Gpu(24, 40, 0.06, 1_000), 24, true, 900, random -> PROMPT_TOKENS, (now, server, random) -> {});
        Result slow = adaptive(new Gpu(24, 40, 0.06, 1_000), 3_600);

        reporter.publishEntry("slowFirstTokens", slow + " full " + full);
        assertThat(slow.limit()).isGreaterThan(LlmCallLimit.FLOOR);
        assertThat(slow.baselineMillis()).isGreaterThan(3_000);
        assertThat(slow.tokensPerSecond()).isGreaterThan(0.5 * full.tokensPerSecond());
        assertThat(slow.firstTokenP50()).isLessThan(full.firstTokenP50());
    }

    @Test
    void shouldNotCollapseWhenSomeLongPromptsMissTheCache(TestReporter reporter) {
        double peak = run(
                        Gpu.typical(), 24, true, 900, LlmCallLimitOnAGpuTest::mixedPrompts, (now, server, random) -> {})
                .tokensPerSecond();
        Result mixed = run(
                Gpu.typical(), null, true, 2_400, LlmCallLimitOnAGpuTest::mixedPrompts, (now, server, random) -> {});

        reporter.publishEntry("coldPrompts", mixed + " peak " + peak);
        assertThat(mixed.tokensPerSecond()).isGreaterThan(0.6 * peak);
    }

    private static double mixedPrompts(Random random) {
        return random.nextDouble() < 0.1 ? 40_000 : PROMPT_TOKENS;
    }

    @Test
    void shouldYieldToAnotherTenantAndComeBackWhenItLeaves(TestReporter reporter) {
        Gpu gpu = Gpu.typical();
        Result shared = run(gpu, null, true, 3_600, random -> PROMPT_TOKENS, new Scene() {
            boolean joined;

            @Override
            public void at(double now, Gpu server, Random random) {
                if (!joined && now >= 600) {
                    joined = true;
                    for (int i = 0; i < 30; i++)
                        server.waiting.add(new Call(now, PROMPT_TOKENS, ANSWER_TOKENS, 0, false));
                }
                if (now >= 1_200) {
                    server.waiting.removeIf(call -> !call.ours);
                    server.running.removeIf(call -> !call.ours);
                }
            }
        });

        reporter.publishEntry("anotherTenant", shared.toString());
        assertThat(shared.lowestLimit()).isGreaterThanOrEqualTo(LlmCallLimit.FLOOR);
        assertThat(shared.inFlight()).isGreaterThan(12);
    }
}
