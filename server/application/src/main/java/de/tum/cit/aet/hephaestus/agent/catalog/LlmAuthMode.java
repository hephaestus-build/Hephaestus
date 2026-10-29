package de.tum.cit.aet.hephaestus.agent.catalog;

/** Credential shapes for OpenAI-compatible APIs. Native Messages uses {@code x-api-key}. */
public enum LlmAuthMode {
    /** {@code Authorization: Bearer <key>} */
    BEARER,
    /** {@code api-key: <key>} */
    API_KEY,
}
