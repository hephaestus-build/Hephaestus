package de.tum.cit.aet.hephaestus.agent.catalog;

/**
 * Provider reasoning-effort values. Null configuration omits the parameter; {@link #NONE}
 * requests no reasoning. {@code pi-provider.ts} maps these values to the provider request.
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
