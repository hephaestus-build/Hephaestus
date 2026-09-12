package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin-gated mutation endpoint whose action is recorded on the audit trail.
 *
 * <p>The counterpart to {@link AuditExempt}: {@code AuditByDefaultArchTest} requires every admin
 * mutation handler to carry exactly one of the two, so a new administrative action cannot reach main
 * without a recorded decision about auditing it. That makes the trail's completeness a property of the
 * build rather than of anyone's memory.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    AuditLedger ledger();

    /**
     * Which row type within {@link #ledger()}'s {@link AuditLedger#vocabulary() vocabulary}. Empty only
     * for a ledger that has no vocabulary; any other combination fails the architecture test.
     */
    String type() default "";
}
