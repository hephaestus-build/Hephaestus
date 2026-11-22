package de.tum.cit.aet.hephaestus.testconfig;

import java.net.URI;
import java.util.Map;

/**
 * Tuple of LLM credentials read from the environment. The {@code HEPHAESTUS_LIVE_LLM_API_KEY}
 * variable is the JUnit gate for {@link LiveLlmTest}. Endpoint, key, and model must all be
 * supplied explicitly so the repository never carries environment-specific defaults.
 *
 * @param baseUrl   OpenAI-compatible base URL; every chat request goes to {@link #chatCompletions()}
 * @param apiKey    bearer token sent as {@code Authorization: Bearer <key>}
 * @param model     model id passed to the runner (forwarded as {@code defaultModel})
 */
public record LiveLlmCredentials(String baseUrl, String apiKey, String model) {
    /**
     * Caller must already be JUnit-gated via {@link LiveLlmTest}; throws rather than skips so a
     * misconfigured override surfaces loudly.
     */
    public static LiveLlmCredentials fromEnv() {
        return from(System.getenv());
    }

    static LiveLlmCredentials from(Map<String, String> environment) {
        String baseUrl = environment.get("HEPHAESTUS_LIVE_LLM_BASE_URL");
        String apiKey = environment.get("HEPHAESTUS_LIVE_LLM_API_KEY");
        String model = environment.get("HEPHAESTUS_LIVE_LLM_MODEL");
        if (baseUrl == null
                || baseUrl.isBlank()
                || apiKey == null
                || apiKey.isBlank()
                || model == null
                || model.isBlank()) {
            throw new IllegalStateException("Live LLM tests require HEPHAESTUS_LIVE_LLM_BASE_URL, "
                    + "HEPHAESTUS_LIVE_LLM_API_KEY, and HEPHAESTUS_LIVE_LLM_MODEL");
        }
        return new LiveLlmCredentials(baseUrl, apiKey, model);
    }

    /**
     * The one chat endpoint of the base URL. A base that ends with the API version takes {@code /chat/completions},
     * and any other base takes {@code /v1/chat/completions}. No caller probes a second path, so every request the
     * endpoint sees is a counted call.
     */
    public URI chatCompletions() {
        String base = baseUrl.replaceAll("/+$", "");
        return URI.create(base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions");
    }

    /** Env vars the Pi SDK reads natively for {@code openai-completions} provider routing. */
    public Map<String, String> asProcessEnv() {
        return Map.of("OPENAI_API_KEY", apiKey, "OPENAI_BASE_URL", baseUrl);
    }
}
