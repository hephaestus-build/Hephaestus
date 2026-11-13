package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * The token limits a model runs with, each with its source. For each limit, an admin's value wins over the provider's
 * metadata, and that wins over {@link ModelLimitCatalog}. The proxy bounds every generative call by {@link
 * #maxOutputTokens()}, so a model without one cannot run.
 *
 * <p>A model whose context window is known, but whose maximum output is not, gets a derived maximum output: a quarter of
 * the context window, at most {@link #DERIVED_OUTPUT_CAP}. So does a model whose admin set only the context window, and a
 * model whose reported maximum output does not fit in its window. The prompt then keeps three quarters of the window. A server
 * such as vLLM refuses a request whose prompt and output limit together exceed the window, so the whole window is no
 * usable output limit.
 */
public record ModelLimits(
        @Nullable Integer contextWindow,
        @Nullable LlmLimitSource contextWindowSource,
        @Nullable Integer maxOutputTokens,
        @Nullable LlmLimitSource maxOutputTokensSource) {

    /** The largest derived maximum output. Above it, an admin sets the value. */
    public static final int DERIVED_OUTPUT_CAP = 16_384;

    private static final int DERIVED_OUTPUT_DIVISOR = 4;

    public static final ModelLimits NONE = new ModelLimits(null, null, null, null);

    public static ModelLimits of(
            @Nullable String upstreamModelId,
            @Nullable Integer adminContextWindow,
            @Nullable Integer adminMaxOutputTokens,
            @Nullable Integer discoveredContextWindow,
            @Nullable Integer discoveredMaxOutputTokens) {
        ModelLimitCatalog.Entry catalog = ModelLimitCatalog.find(upstreamModelId);
        Integer catalogContext = catalog == null ? null : catalog.contextWindow();
        Integer catalogOutput = catalog == null ? null : catalog.maxOutputTokens();

        Integer context = first(adminContextWindow, discoveredContextWindow, catalogContext);
        LlmLimitSource contextSource = sourceOf(adminContextWindow, discoveredContextWindow, catalogContext);
        Integer output = first(adminMaxOutputTokens, discoveredMaxOutputTokens, catalogOutput);
        LlmLimitSource outputSource = sourceOf(adminMaxOutputTokens, discoveredMaxOutputTokens, catalogOutput);
        // An admin's context window caps the output that another source reports, and an output that does not fit in
        // the window cannot be a usable limit: both fall back to the derived maximum output.
        boolean adminOutput = outputSource == LlmLimitSource.ADMIN;
        boolean capped = context != null
                && output != null
                && !adminOutput
                && (contextSource == LlmLimitSource.ADMIN || output >= context);
        if (output == null || capped) {
            output = derivedMaxOutputTokens(context);
            outputSource = output == null ? null : LlmLimitSource.DERIVED;
        }
        return new ModelLimits(context, contextSource, output, outputSource);
    }

    /** The derived maximum output for a context window; {@code null} when the window is unknown. */
    public static @Nullable Integer derivedMaxOutputTokens(@Nullable Integer contextWindow) {
        if (contextWindow == null || contextWindow < DERIVED_OUTPUT_DIVISOR) return null;
        return Math.min(contextWindow / DERIVED_OUTPUT_DIVISOR, DERIVED_OUTPUT_CAP);
    }

    /** Whether the proxy can bound the model's calls: false means its calls are refused until an admin sets a limit. */
    public boolean bounded() {
        return maxOutputTokens != null;
    }

    private static @Nullable Integer first(
            @Nullable Integer admin, @Nullable Integer discovered, @Nullable Integer catalog) {
        if (positive(admin)) return admin;
        if (positive(discovered)) return discovered;
        return positive(catalog) ? catalog : null;
    }

    private static @Nullable LlmLimitSource sourceOf(
            @Nullable Integer admin, @Nullable Integer discovered, @Nullable Integer catalog) {
        if (positive(admin)) return LlmLimitSource.ADMIN;
        if (positive(discovered)) return LlmLimitSource.DISCOVERED;
        return positive(catalog) ? LlmLimitSource.CATALOG : null;
    }

    private static boolean positive(@Nullable Integer value) {
        return value != null && value > 0;
    }
}
