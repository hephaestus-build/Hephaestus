package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.core.web.OpenWireCode;

/** Family the integration belongs to. Used to gate practice availability per workspace. */
@OpenWireCode
public enum IntegrationFamily {
    SCM,
    MESSAGING,
    DOCUMENTATION,
}
