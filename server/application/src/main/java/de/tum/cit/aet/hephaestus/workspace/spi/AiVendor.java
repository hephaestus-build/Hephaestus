package de.tum.cit.aet.hephaestus.workspace.spi;

import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The company whose mark a developer recognises on an answer card: who made the model, and where it
 * runs. Presentation only. It never decides data handling, which is the admin's declared fact; a
 * vendor that cannot be recognised is simply absent and the card shows a generic mark.
 */
public enum AiVendor {
    OPENAI,
    AZURE,
    ANTHROPIC,
    GEMINI,
    GEMMA,
    META,
    MISTRAL,
    QWEN,
    DEEPSEEK,
    OLLAMA;

    /** The model family from the upstream model id, such as {@code gpt-5} or {@code meta-llama/Llama-3.3}. */
    public static @Nullable AiVendor ofModel(String upstreamModelId) {
        String id = upstreamModelId.toLowerCase(Locale.ROOT);
        String name = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
        if (name.startsWith("gpt") || name.startsWith("chatgpt") || name.matches("o[1-9].*")) return OPENAI;
        if (name.startsWith("claude")) return ANTHROPIC;
        if (name.startsWith("gemini")) return GEMINI;
        if (name.startsWith("gemma")) return GEMMA;
        if (name.startsWith("llama") || id.startsWith("meta-llama/")) return META;
        if (name.matches("(mistral|mixtral|codestral|magistral|devstral|ministral).*")) return MISTRAL;
        if (name.startsWith("qwen") || name.startsWith("qwq")) return QWEN;
        if (name.startsWith("deepseek")) return DEEPSEEK;
        return null;
    }

    /** The platform the model runs on, from the connection's host. Never sent to a browser itself. */
    public static @Nullable AiVendor ofHost(String baseUrl) {
        String host;
        int port;
        try {
            URI uri = URI.create(baseUrl.trim());
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            port = uri.getPort();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (host.endsWith(".azure.com") || host.endsWith(".azure-api.net")) return AZURE;
        if (host.equals("api.openai.com")) return OPENAI;
        if (host.equals("api.anthropic.com")) return ANTHROPIC;
        if (host.equals("generativelanguage.googleapis.com")) return GEMINI;
        if (host.equals("api.mistral.ai")) return MISTRAL;
        if (host.equals("api.deepseek.com")) return DEEPSEEK;
        if (host.contains("ollama") || port == 11434) return OLLAMA;
        return null;
    }
}
