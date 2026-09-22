package de.tum.cit.aet.hephaestus.agent.catalog;

/**
 * How hard a reasoning model is asked to think, on OpenAI's scale, which the other providers that
 * take an effort share. A model with no effort set sends none, and the provider's own default applies
 * (medium for OpenAI's GPT-5 family); {@link #NONE} asks a reasoning model not to reason at all.
 *
 * <p>The runner maps each value onto Pi's thinking levels and back onto the provider's wire value in
 * {@code pi-provider.ts}; nothing on the server interprets it.
 */
public enum ReasoningEffort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public static final int MAX_LENGTH = 16;
}
