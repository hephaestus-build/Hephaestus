package de.tum.cit.aet.hephaestus.core.auth.spi;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;

public interface PeopleRegistry {
    long ensureHuman(PersonIdentity identity, String displayName);

    long claim(PersonIdentity identity);
}
