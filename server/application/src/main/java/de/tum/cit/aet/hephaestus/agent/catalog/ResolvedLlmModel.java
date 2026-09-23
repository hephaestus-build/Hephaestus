package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * The runtime shape of an LLM model after a binding is resolved, whichever catalog it came from.
 *
 * <p>Deliberately carries NO credential: a record's generated {@code toString()} covers every
 * component, so an API key here would leak into any log line. The key is resolved live and separately
 * by {@link LlmModelResolver#resolveCredential}.
 *
 * <p>{@code apiProtocol} is Pi's own {@code api} token, passed through verbatim.
 */
public record ResolvedLlmModel(
        String baseUrl,
        String apiProtocol,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort) {}
