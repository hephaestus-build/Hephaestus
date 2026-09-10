package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Family the integration belongs to. Used to gate practice availability per workspace. */
public enum IntegrationFamily {
    SCM,
    MESSAGING,
    DOCUMENTATION,
    DIRECTORY,
    ACCESS,
}
