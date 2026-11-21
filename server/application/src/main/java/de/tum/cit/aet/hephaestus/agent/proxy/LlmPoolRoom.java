package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;

/**
 * The room of a model pool: what one admission round may grant from the pool's limit, its reserve and its rate budgets.
 */
final class LlmPoolRoom {
    private LlmPoolRoom() {}

    /**
     * What one round may grant. An interactive call may use the reserve, also when the calls in flight fill the limit.
     * A call that is not interactive may not: calls that a lowered limit left in flight must not cost a person the
     * reserve. The slots that lapsed permits still hold count as in flight, except against the reserve.
     */
    static LlmCallPermitRepository.Room room(
            LlmCallPermitRepository rows, long poolId, LlmCallPoolRepository.Admission pool) {
        int limit = limit(pool);
        int reserve = LlmCallLimit.reserve(limit);
        long lapsedSlots = (long) Math.floor(rows.lapsedHold(poolId));
        long active = rows.countActive(poolId) + lapsedSlots;
        long notInteractive = rows.countNotInteractive(poolId) + lapsedSlots;
        var used = rows.rates(poolId);
        long bound = tokenBound(pool);
        Integer perMinute = pool.getRequestsPerMinute();
        Long requests = perMinute == null ? null : perMinute.longValue();
        Long tokens = pool.getTokensPerMinute();
        Long input = pool.getInputTokensPerMinute();
        Long output = pool.getOutputTokensPerMinute();
        // An output bucket counts tokens as a model writes them, so a call needs no room in it before it starts.
        long rate = LongStream.of(
                        fit(requests, used.getRequests(), 1),
                        fit(tokens, used.getTokens(), bound),
                        fit(input, used.getInputTokens(), bound),
                        fit(output, used.getOutputTokens(), 0))
                .min()
                .orElseThrow();
        long notInteractiveRate = LongStream.of(
                        fitShare(requests, used.getNotInteractiveRequests(), pool, 1),
                        fitShare(tokens, used.getNotInteractiveTokens(), pool, bound),
                        fitShare(input, used.getNotInteractiveInputTokens(), pool, bound),
                        fitShare(output, used.getNotInteractiveOutputTokens(), pool, 0))
                .min()
                .orElseThrow();
        return new LlmCallPermitRepository.Room(
                Math.max(0, Math.max(limit - active, reserve - (active - notInteractive))),
                Math.max(0, limit - active),
                Math.max(0, limit - reserve - notInteractive),
                Math.max(0, 1 - rows.countActiveProductFeedback(poolId)),
                rate,
                notInteractiveRate);
    }

    static int limit(LlmCallPoolRepository.Admission pool) {
        return LlmCallLimit.effective(pool.getLearnedLimit(), pool.getMaxConcurrentCalls());
    }

    /**
     * How many calls fit in one rate budget, each of which debits {@code perCall} at its grant: {@link Long#MAX_VALUE}
     * for no budget. A budget that debits nothing at a grant fits every call while it has room.
     */
    private static long fit(@Nullable Long budget, long used, long perCall) {
        if (budget == null) return Long.MAX_VALUE;
        if (perCall == 0) return used <= budget ? Long.MAX_VALUE : 0;
        long slack = budget - perCall - used;
        return slack < 0 ? 0 : slack / perCall + 1;
    }

    private static long fitShare(@Nullable Long budget, long used, LlmCallPoolRepository.Admission pool, long perCall) {
        return budget == null ? Long.MAX_VALUE : fit(notInteractiveShare(budget, pool, perCall), used, perCall);
    }

    /** Calls that are not interactive get the rate's share that the limit gives them, and always at least one call. */
    private static long notInteractiveShare(long budget, LlmCallPoolRepository.Admission pool, long perCall) {
        int limit = limit(pool);
        long share = (long) limit - LlmCallLimit.reserve(limit);
        long proportional = (budget / limit) * share + (budget % limit) * share / limit;
        return Math.max(perCall, proportional);
    }

    static long tokenBound(LlmCallPoolRepository.Admission pool) {
        Long bound = pool.getTokensPerCall();
        return bound == null ? 0 : bound;
    }
}
