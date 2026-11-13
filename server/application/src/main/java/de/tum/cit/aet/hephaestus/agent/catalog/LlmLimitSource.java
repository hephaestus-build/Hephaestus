package de.tum.cit.aet.hephaestus.agent.catalog;

/** Where the value of a model's token limit comes from. An earlier constant wins over a later one. */
public enum LlmLimitSource {
    /** An admin set it. Discovery never overwrites it. */
    ADMIN,
    /** The provider's model metadata reported it ({@link ProviderModelLimits}). */
    DISCOVERED,
    /** The provider publishes it in its documentation, and Hephaestus ships it ({@link ModelLimitCatalog}). */
    CATALOG,
    /** Only the maximum output: a quarter of the known context window, at most {@link ModelLimits#DERIVED_OUTPUT_CAP}. */
    DERIVED
}
