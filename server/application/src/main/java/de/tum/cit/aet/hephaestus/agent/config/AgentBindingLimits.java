package de.tum.cit.aet.hephaestus.agent.config;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The range a workspace administrator may set a binding's per-run timeout to, and the smallest model Heph works with.
 * The timeout ceiling bounds how long any single agent run may last, so anything that must outlive a run derives its
 * window from it.
 */
public final class AgentBindingLimits {

    public static final int MIN_TIMEOUT_SECONDS = 30;

    public static final int MAX_TIMEOUT_SECONDS = 10800;

    /**
     * The smallest context window Heph works in. Its fixed prompt (instructions, tools and the situation) takes about
     * 9,000 tokens. At 10,000 to 12,000 tokens, every turn overflowed and nothing could be compacted.
     */
    public static final int MIN_HEPH_CONTEXT_WINDOW = 32_768;

    /** The smallest output limit Heph works with: the summary of a long conversation is one reply of the model. */
    public static final int MIN_HEPH_OUTPUT_TOKENS = 4_096;

    private AgentBindingLimits() {}

    /**
     * Why a model is too small for Heph, worded for an administrator. A limit the model does not state is not checked.
     *
     * @return empty when the model is large enough
     */
    public static Optional<String> tooSmallForHeph(@Nullable Integer contextWindow, @Nullable Integer maxOutputTokens) {
        if (contextWindow != null && contextWindow < MIN_HEPH_CONTEXT_WINDOW) {
            return Optional.of("Heph needs a model with a context window of at least %s tokens. This model has %s."
                    .formatted(tokens(MIN_HEPH_CONTEXT_WINDOW), tokens(contextWindow)));
        }
        if (maxOutputTokens != null && maxOutputTokens < MIN_HEPH_OUTPUT_TOKENS) {
            return Optional.of(
                    "Heph needs a model that can write at least %s tokens in one reply, to summarize long conversations. This model allows %s."
                            .formatted(tokens(MIN_HEPH_OUTPUT_TOKENS), tokens(maxOutputTokens)));
        }
        return Optional.empty();
    }

    private static String tokens(int count) {
        return String.format(Locale.ENGLISH, "%,d", count);
    }
}
