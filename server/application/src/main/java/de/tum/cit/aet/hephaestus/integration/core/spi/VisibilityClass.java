package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Who on the provider can see a monitored resource, as far as Hephaestus has proven it. Mentoring reads only what each
 * reader can see on the provider, so it waits on a resource whose readers are not proven.
 */
public enum VisibilityClass {
    /** Everyone in the workspace can see it on the provider, such as a public repository. */
    WORKSPACE,
    /** Only some people can see it; its readers are listed and rechecked. */
    RESTRICTED,
    /** Its readers are not proven yet. */
    UNPROVEN
}
