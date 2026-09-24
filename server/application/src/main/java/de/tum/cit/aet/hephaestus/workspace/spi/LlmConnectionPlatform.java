package de.tum.cit.aet.hephaestus.workspace.spi;

/** The service an admin declares as the model connection, not the model maker or data operator. */
public enum LlmConnectionPlatform {
    LOGOS,
    VLLM,
    OLLAMA,
    AZURE,
    AWS_BEDROCK,
    GOOGLE_VERTEX,
    OPENAI,
    GOOGLE_AI_STUDIO,
    ALIBABA_CLOUD,
    GROQ,
    FIREWORKS,
    TOGETHER_AI,
    DEEPINFRA,
    NEBIUS,
    OPENROUTER,
    CLOUDFLARE_AI_GATEWAY,
    VERCEL_AI_GATEWAY
}
