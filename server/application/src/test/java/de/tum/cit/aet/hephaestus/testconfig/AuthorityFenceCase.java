package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBearing;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a test as the case in which a narrowing of {@link #setting()} refuses work at {@link #boundary()} (ADR
 * 0134). Each {@link AuthorityBearing} setting needs one case per boundary.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorityFenceCase {

    /** The owning row of the setting, marked {@link AuthorityBearing}. */
    Class<?> setting();

    AuthorityBoundary boundary();
}
