package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * A pool's limit of calls in flight, as its provider taught it: additive increase, multiplicative decrease. Nobody has
 * to guess the provider's capacity. The limit rises while served calls find it binding, and falls on a provider's
 * refusal. An instance admin's maximum is a ceiling on it.
 *
 * <p>While {@code probing}, each served call raises the limit, which doubles it per call duration. Once the provider
 * has refused recently, a whole limit's worth of served calls raises it by one.
 *
 * @param learned the most calls in flight the provider is thought to serve
 * @param credit the served calls that count towards the next increase
 */
public record LlmCallLimit(int learned, int credit) {

    /** One background call beside one conversation call. Below this, a pool could not keep its reserve. */
    static final int FLOOR = 2;

    public LlmCallLimit afterThrottle() {
        return new LlmCallLimit(Math.max(FLOOR, learned * 7 / 10), 0);
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
     * The calls in flight that only conversations may use: a quarter of the limit, rounded up. Only a limit of one call
     * has none, since a pool must leave a background call.
     */
    public static int reserve(int limit) {
        return Math.min((limit + 3) / 4, limit - 1);
    }
}
