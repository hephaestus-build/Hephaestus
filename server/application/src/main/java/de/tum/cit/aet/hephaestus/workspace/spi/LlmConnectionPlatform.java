package de.tum.cit.aet.hephaestus.workspace.spi;

/** The service an admin declares as the model connection, not the model maker or data operator. */
public enum LlmConnectionPlatform {
    AZURE,
    AWS_BEDROCK,
    GOOGLE_VERTEX,
    OPENAI,
    VERCEL_AI_GATEWAY
}
