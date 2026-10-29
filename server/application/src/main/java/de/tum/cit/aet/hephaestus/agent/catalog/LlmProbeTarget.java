package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * What the "test connection" probe needs from a stored LLM connection. A projection rather than the
 * entity because the probe's multi-second outbound call must run with no transaction and no JDBC
 * connection held, or admins probing a stalled provider pin the pool.
 *
 * @param apiKey {@code null} for a deliberately keyless connection (self-hosted vLLM/Ollama gateway)
 */
public record LlmProbeTarget(
        String baseUrl, LlmAuthMode authMode, @Nullable String apiKey, LlmApiProtocol apiProtocol) {}
