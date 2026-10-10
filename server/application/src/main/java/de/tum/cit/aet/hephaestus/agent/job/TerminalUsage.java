package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult.LlmUsage;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageJobType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Retains one observed token vector for an ending attempt. Prompt and output totals select the source,
 * with the proxy winning ties. Incomparable totals remain unverifiable instead of combining buckets.
 * Aggregate totals do not establish that either source observed every call.
 */
record TerminalUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        int totalCalls,
        boolean verifiable,
        UsageProvenance provenance) {
    /**
     * @param runnerUsage what the agent runner reported, or {@code null} when it never produced one
     * @param proxyCounts the {@code agent_job} row's proxy accumulators, read BEFORE any requeue
     *     (which zeroes them) and BEFORE the runner's totals are written over them
     * @param precompute the attempt's precompute totals by model kind. Its chat total is added to the
     *     runner's report: the precompute stage runs before the runner, in another process, so the report
     *     never contains those calls, while the proxy's counters do. The precompute rows keep one prompt
     *     total, so its cached share is counted as ordinary input.
     */
    static TerminalUsage resolve(
            @Nullable LlmUsage runnerUsage,
            @Nullable AgentJobLlmUsage proxyCounts,
            List<PrecomputeKindTotal> precompute) {
        boolean fromRunner = hasTokens(runnerUsage);
        boolean fromProxy = proxyCounts != null && proxyCounts.hasBillableUsage();
        if (!fromRunner && !fromProxy) {
            // Neither record has tokens: keep whichever call count exists as telemetry, but report the
            // spend as unknown.
            int calls = runnerUsage != null && runnerUsage.totalCalls() > 0
                    ? runnerUsage.totalCalls()
                    : proxyCounts != null ? Math.max(0, proxyCounts.totalCalls()) : 0;
            return new TerminalUsage(0L, 0L, 0L, 0L, 0L, calls, false, UsageProvenance.NONE);
        }

        Buckets runner = fromRunner ? Buckets.of(Objects.requireNonNull(runnerUsage)) : Buckets.NONE;
        if (fromRunner) {
            for (PrecomputeKindTotal total : precompute) {
                if (total.modelKind() == ModelKind.CHAT) {
                    runner = runner.plus(total);
                }
            }
        }
        Buckets proxy = fromProxy ? Buckets.of(Objects.requireNonNull(proxyCounts)) : Buckets.NONE;
        boolean proxyCovers = proxy.covers(runner);
        boolean runnerCovers = !proxyCovers && runner.covers(proxy);
        Buckets selected = runnerCovers ? runner : proxy;
        return new TerminalUsage(
                selected.input,
                selected.output,
                selected.cacheRead,
                selected.cacheWrite,
                selected.reasoning,
                // Call counts are telemetry, not coverage. Clamped rather than cast: the count is an int on the
                // row, and an absurd report should store the ceiling rather than wrap to a negative one.
                (int) Math.min(Integer.MAX_VALUE, Math.max(runner.calls, proxy.calls)),
                proxyCovers || runnerCovers,
                runnerCovers ? UsageProvenance.RUNNER : UsageProvenance.PROXY);
    }

    /**
     * An attempt that ended without a runner report. The proxy's counters already hold its precompute
     * chat calls.
     */
    static TerminalUsage fromProxy(@Nullable AgentJobLlmUsage proxyCounts) {
        return resolve(null, proxyCounts, List.of());
    }

    /** An attempt that ended with nothing to bill — no runner report and no proxy accumulation. */
    static TerminalUsage none() {
        return fromProxy(null);
    }

    /**
     * Append this ending attempt to the spend ledger — the ONE way an {@code agent_job} attempt becomes
     * ledger rows: its own row, then one row for each precompute model other than chat that it called,
     * over all its practices. The ledger key holds no practice, so one row per practice would keep only
     * the first.
     * The precompute chat calls are already in this attempt's own row: the proxy's counters hold them, and
     * {@link #resolve} adds them to a runner report. Which of the recorder's two
     * append paths a row takes is derived here, never chosen by the caller: a caller picking it itself
     * could write a PRICED row of invented zeros, the exact failure the ledger exists to prevent.
     *
     * @param workspaceId passed explicitly rather than read off {@code job}: the terminal paths differ
     *     in whether they hold a workspace-scoped id or a job loaded with its workspace
     * @param snapshot the admitted snapshot, which names the models and prices the precompute rows;
     *     {@code null} when it cannot be read, which leaves the precompute rows UNPRICED
     * @param price the price of the attempt's own row
     * @return true when the attempt's own row was billed at its frozen price, false when it was appended
     *     UNPRICED
     */
    boolean appendTo(
            LlmUsageRecorder recorder,
            AgentJobRepository jobs,
            Long workspaceId,
            AgentJob job,
            @Nullable ConfigSnapshot snapshot,
            LlmPriceSnapshot price) {
        // One instant for every row of the attempt, so midnight on the 1st cannot split it across two months.
        Instant occurredAt = Instant.now();
        boolean billed = append(
                recorder,
                workspaceId,
                new LlmUsageRecorder.LlmUsageSample(
                        LlmUsageJobType.from(job.getJobType()),
                        LlmUsageSourceType.AGENT_JOB,
                        job.getId(),
                        job.getRetryCount(),
                        snapshot == null ? null : snapshot.upstreamModelId(),
                        inputTokens,
                        outputTokens,
                        cacheReadTokens,
                        cacheWriteTokens,
                        reasoningTokens,
                        totalCalls,
                        price,
                        provenance,
                        occurredAt),
                verifiable);
        for (PrecomputeKindTotal total : jobs.sumPrecomputeUsageByKind(workspaceId, job.getId(), job.getRetryCount())) {
            if (total.modelKind() == ModelKind.CHAT) {
                continue;
            }
            FrozenModel model = snapshot == null ? null : snapshot.precomputeSlot(total.modelKind());
            LlmPriceSnapshot frozen = model == null ? null : model.priceSnapshot();
            append(
                    recorder,
                    workspaceId,
                    new LlmUsageRecorder.LlmUsageSample(
                            LlmUsageJobType.from(job.getJobType()),
                            AgentJobPrecomputeUsage.ledgerSourceType(total.modelKind()),
                            job.getId(),
                            job.getRetryCount(),
                            model == null ? null : model.upstreamModelId(),
                            total.inputTokens(),
                            total.outputTokens(),
                            0L,
                            0L,
                            0L,
                            (int) Math.min(Integer.MAX_VALUE, total.calls()),
                            frozen != null ? frozen : LlmPriceSnapshot.unpricedInstance(),
                            UsageProvenance.PROXY,
                            occurredAt),
                    total.inputTokens() + total.outputTokens() > 0);
        }
        return billed;
    }

    /**
     * The one billing rule. A declared zero is confirmed without tokens. A rate is charged only on
     * observed tokens. Anything else is appended UNPRICED.
     */
    private static boolean append(
            LlmUsageRecorder recorder, Long workspaceId, LlmUsageRecorder.LlmUsageSample sample, boolean observed) {
        boolean billable =
                switch (sample.price().pricingState()) {
                    case NO_CHARGE -> true;
                    case PRICED -> observed;
                    case UNPRICED -> false;
                };
        if (billable) {
            recorder.record(workspaceId, sample);
        } else {
            recorder.recordUnverifiable(workspaceId, sample);
        }
        return billable;
    }

    /** A report is evidence only if it claims a call AND a non-zero token bucket; calls alone cannot be priced. */
    private static boolean hasTokens(@Nullable LlmUsage usage) {
        return (usage != null
                && usage.totalCalls() > 0
                && (nullToZero(usage.inputTokens()) > 0
                        || nullToZero(usage.outputTokens()) > 0
                        || nullToZero(usage.cacheReadTokens()) > 0
                        || nullToZero(usage.cacheWriteTokens()) > 0
                        || nullToZero(usage.reasoningTokens()) > 0));
    }

    private static long nullToZero(@Nullable Integer value) {
        return value != null ? value : 0L;
    }

    /** One source's buckets, so the two can be compared without writing each bucket name five times. */
    private record Buckets(long input, long output, long cacheRead, long cacheWrite, long reasoning, long calls) {
        static final Buckets NONE = new Buckets(0, 0, 0, 0, 0, 0);

        static Buckets of(LlmUsage usage) {
            return new Buckets(
                    nullToZero(usage.inputTokens()),
                    nullToZero(usage.outputTokens()),
                    nullToZero(usage.cacheReadTokens()),
                    nullToZero(usage.cacheWriteTokens()),
                    nullToZero(usage.reasoningTokens()),
                    Math.max(0, usage.totalCalls()));
        }

        static Buckets of(AgentJobLlmUsage counts) {
            return new Buckets(
                    counts.inputTokens(),
                    counts.outputTokens(),
                    counts.cacheReadTokens(),
                    counts.cacheWriteTokens(),
                    counts.reasoningTokens(),
                    Math.max(0, counts.totalCalls()));
        }

        Buckets plus(PrecomputeKindTotal total) {
            return new Buckets(
                    input + total.inputTokens(),
                    output + total.outputTokens(),
                    cacheRead,
                    cacheWrite,
                    reasoning,
                    calls + total.calls());
        }

        long prompt() {
            return input + cacheRead + cacheWrite;
        }

        /** True when this source's prompt and output totals are both at least {@code other}'s. */
        boolean covers(Buckets other) {
            return prompt() >= other.prompt() && output >= other.output;
        }
    }
}
