package de.tum.cit.aet.hephaestus.agent.practice.live;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import java.util.Arrays;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The model settings a live practice run sends. Each optional system property replaces one harness default, so the
 * same cases can be measured with reasoning or a larger output allowance. Without them a run is unchanged.
 */
record LiveModelSettings(
        int contextWindow, int maxOutputTokens, @Nullable ReasoningEffort reasoningEffort) {

    static final String CONTEXT_WINDOW_PROPERTY = "hephaestus.live.criteriaContextWindow";
    static final String MAX_OUTPUT_TOKENS_PROPERTY = "hephaestus.live.criteriaMaxOutputTokens";
    static final String REASONING_EFFORT_PROPERTY = "hephaestus.live.criteriaReasoningEffort";

    /**
     * The settings of a run that names none. The table in {@code docs/contributor/testing.mdx} § Live external-service
     * tests states these values as the contract.
     */
    static final LiveModelSettings DEFAULTS = new LiveModelSettings(131_072, 4096, null);

    static LiveModelSettings fromSystemProperties() {
        return from(System::getProperty);
    }

    /** The settings that {@code property} names; an invalid value fails before any model request. */
    static LiveModelSettings from(Function<String, @Nullable String> property) {
        int contextWindow = LiveProperty.positiveInt(CONTEXT_WINDOW_PROPERTY, property.apply(CONTEXT_WINDOW_PROPERTY))
                .orElse(DEFAULTS.contextWindow());
        int maxOutputTokens = LiveProperty.positiveInt(
                        MAX_OUTPUT_TOKENS_PROPERTY, property.apply(MAX_OUTPUT_TOKENS_PROPERTY))
                .orElse(DEFAULTS.maxOutputTokens());
        if (maxOutputTokens >= contextWindow) {
            throw new IllegalArgumentException(MAX_OUTPUT_TOKENS_PROPERTY + " (" + maxOutputTokens
                    + ") must be less than the context window (" + contextWindow + ")");
        }
        return new LiveModelSettings(
                contextWindow, maxOutputTokens, reasoningEffort(property.apply(REASONING_EFFORT_PROPERTY)));
    }

    private static @Nullable ReasoningEffort reasoningEffort(@Nullable String value) {
        if (value == null) {
            return DEFAULTS.reasoningEffort();
        }
        try {
            return ReasoningEffort.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    REASONING_EFFORT_PROPERTY + " must be one of " + Arrays.toString(ReasoningEffort.values())
                            + ", not '" + value + "'",
                    e);
        }
    }
}
