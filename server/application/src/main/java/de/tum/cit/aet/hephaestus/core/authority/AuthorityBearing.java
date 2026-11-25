package de.tum.cit.aet.hephaestus.core.authority;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the owning row of a setting whose change can narrow authority (ADR 0134 decision 4). A narrowing of the setting
 * raises {@link #generation()}, so work requested under the old authority is refused at its next boundary. The setting
 * registers one case per {@link AuthorityBoundary} in the tests ({@code AuthorityBearingRegistryTest}). An issue that
 * adds such a setting marks its row and registers its cases.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorityBearing {

    /** The generation that a narrowing of the setting raises. */
    Generation generation();

    /** The authorization generations of ADR 0134 decision 2. */
    enum Generation {
        /** {@code workspace.authority_generation}. */
        WORKSPACE
    }
}
