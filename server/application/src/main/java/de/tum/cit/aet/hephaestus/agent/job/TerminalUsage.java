package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult.LlmUsage;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageJobType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import java.time.Instant;
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
     */
    static TerminalUsage resolve(@Nullable LlmUsage runnerUsage, @Nullable AgentJobLlmUsage proxyCounts) {
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

    /** An attempt that ended with nothing to bill — no runner report and no proxy accumulation. */
    static TerminalUsage none() {
        return resolve(null, null);
    }

    /**
     * Append this ending attempt to the spend ledger — the ONE way an {@code agent_job} becomes a ledger
     * row. Which of the recorder's two append paths runs is derived here, never chosen by the caller: a
     * caller picking it itself could write a PRICED row of invented zeros, the exact failure the ledger
     * exists to prevent.
     *
     * @param workspaceId passed explicitly rather than read off {@code job}: the terminal paths differ
     *     in whether they hold a workspace-scoped id or a job loaded with its workspace
     * @param upstreamModelId the model from the admitted snapshot — never a provider-reported name
     * @return true when the attempt was billed at its frozen price, false when it was appended UNPRICED
     */
    boolean appendTo(
            LlmUsageRecorder recorder,
            Long workspaceId,
            AgentJob job,
            @Nullable String upstreamModelId,
            LlmPriceSnapshot price) {
        LlmUsageRecorder.LlmUsageSample sample = new LlmUsageRecorder.LlmUsageSample(
                LlmUsageJobType.from(job.getJobType()),
                LlmUsageSourceType.AGENT_JOB,
                job.getId(),
                job.getRetryCount(),
                upstreamModelId,
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                reasoningTokens,
                totalCalls,
                price,
                provenance,
                Instant.now());
        if (verifiable && price.pricingState() != PricingState.UNPRICED) {
            recorder.record(workspaceId, sample);
            return true;
        }
        recorder.recordUnverifiable(workspaceId, sample);
        return false;
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

        long prompt() {
            return input + cacheRead + cacheWrite;
        }

        /** True when this source's prompt and output totals are both at least {@code other}'s. */
        boolean covers(Buckets other) {
            return prompt() >= other.prompt() && output >= other.output;
        }
    }
}
