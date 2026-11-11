package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * A pool's limit of calls in flight, as its provider taught it: additive increase, multiplicative decrease. Nobody has
 * to guess the provider's capacity. The limit rises while served calls find it binding, and falls on a provider's
 * refusal or on {@link LlmCallDelay queueing}. An instance admin's maximum is a ceiling on it.
 *
 * <p>While {@code probing}, each served call raises the limit, which doubles it per call duration. Once the provider
 * has refused recently, a whole limit's worth of served calls raises it by one.
 *
 * @param learned the most calls in flight the provider is thought to serve
 * @param credit the served calls that count towards the next increase
 */
public record LlmCallLimit(int learned, int credit) {

    /**
     * One call outside the reserve beside one interactive call. Below this, a pool could not keep its reserve. No
     * higher value is safe, as a server with one slot queues from the second call on, and a delay from elsewhere cannot
     * hold a pool here: at this floor, {@link LlmCallDelay} takes a new baseline.
     */
    public static final int FLOOR = 2;

    /**
     * Keeps 70 %, from RFC 9438's balance of throughput and convergence. A sawtooth down to this share runs at about 85 %
     * of the capacity, while a halving loses a quarter, and a slow start's overshoot still falls away in two refusals.
     */
    public LlmCallLimit afterThrottle() {
        return new LlmCallLimit(Math.max(FLOOR, learned * 7 / 10), 0);
    }

    /** Lowers the limit by what the delay asks for, by one call at least and never by more than a refusal. */
    public LlmCallLimit afterDelay(double keep) {
        int asked = (int) (learned * keep);
        return new LlmCallLimit(Math.max(afterThrottle().learned, Math.min(learned - 1, asked)), 0);
    }

    public LlmCallLimit afterServedCall(boolean probing) {
        return probing || credit + 1 >= learned
                ? new LlmCallLimit(learned + 1, 0)
                : new LlmCallLimit(learned, credit + 1);
    }

    /** The limit that applies: what was learned, and never more than the instance admin's maximum. */
    public static int effective(int learned, @Nullable Integer maximum) {
        return maximum == null ? learned : Math.min(learned, maximum);
    }

    /**
     * The calls in flight that only interactive calls, such as a conversation's, may use: a quarter of the limit, rounded
     * up. Only a limit of one call has none, since a pool must leave one call outside the reserve.
     */
    public static int reserve(int limit) {
        return Math.min((limit + 3) / 4, limit - 1);
    }
}
