package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutation that runs only for a caller who signed in recently.
 *
 * <p>The counterpart to {@link RecentSignInExempt}: {@code RecentSignInByDefaultArchTest} requires every
 * instance-admin mutation handler, every workspace role mutation and every delete handler to carry exactly one of
 * the two, so a new
 * administrative action cannot reach main without a recorded decision about re-authentication — the same shape
 * {@link Audited} gives the audit trail.
 *
 * <p>For {@link When#EVERY_CALL}, a refusal is recorded on the {@link Audited#type() type} the method already
 * declares for {@link AuditLedger#AUTH_EVENT}, so the attempt lands on the trail under the action it was aimed at.
 * That is why this annotation carries no type of its own, and why the architecture test also requires that
 * declaration.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRecentSignIn {
    /** Which calls of the handler ask for it. */
    When value() default When.EVERY_CALL;

    /**
     * The {@code auth_event} type that a refusal is recorded on, for a handler whose {@link Audited} names another
     * ledger. Empty: the type that the handler declares for {@link AuditLedger#AUTH_EVENT}.
     */
    String refusal() default "";

    enum When {
        /** Every call, asked before the handler runs. */
        EVERY_CALL,
        /**
         * A workspace membership change that grants, demotes or removes an owner. The member's current role and the
         * target role decide together, and the current role is known only under the workspace lock, so
         * {@code MembershipService} asks there, with the same policy, and records a refusal on
         * {@code WORKSPACE_OWNER_CHANGE}. The contributor guide on instance administration owns the rule.
         */
        OWNER_CHANGE,
    }
}
