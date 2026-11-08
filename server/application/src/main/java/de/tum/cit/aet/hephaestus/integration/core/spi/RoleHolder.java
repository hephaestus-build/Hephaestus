package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;

/** Confirmed identity facts retained with the work; an unknown identity has neither flag set. */
public record RoleHolder(AboutRole role, PersonIdentity identity, boolean human, boolean bot) {
    public RoleHolder {
        if (human && bot) throw new IllegalArgumentException("A role holder cannot be both human and a bot");
    }
}
