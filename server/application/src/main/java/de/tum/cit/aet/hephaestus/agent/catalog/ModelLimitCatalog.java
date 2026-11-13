package de.tum.cit.aet.hephaestus.agent.catalog;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Limits of models whose provider lists no limits in its model listing. OpenAI's {@code GET /v1/models} returns only
 * {@code id}, {@code created}, {@code object}, {@code owned_by} and {@code shutdown_date}
 * (https://developers.openai.com/api/reference/resources/models/methods/list). The values are those of each model's page
 * under https://developers.openai.com/api/docs/models. Anthropic's listing carries the limits, so it has no entries here.
 * Add a model when its provider publishes it; an admin's value or the provider's metadata wins over an entry.
 */
final class ModelLimitCatalog {
    private ModelLimitCatalog() {}

    record Entry(int contextWindow, int maxOutputTokens) {}

    private static final Map<String, Entry> ENTRIES = Map.of(
            "gpt-6-astra", new Entry(1_050_000, 128_000),
            "gpt-6.1-sol", new Entry(1_050_000, 128_000),
            "gpt-6.1-terra", new Entry(1_050_000, 128_000),
            "gpt-6-luna", new Entry(1_050_000, 128_000));

    /** {@code null} for a model it has no entry for, also for one not yet given an upstream id. */
    static @Nullable Entry find(@Nullable String upstreamModelId) {
        return upstreamModelId == null ? null : ENTRIES.get(upstreamModelId);
    }
}
