package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Store ownership inventory checked against the personal-data map. */
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonDataStores {
    String[] value();
}
