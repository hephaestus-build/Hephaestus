package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an instance-admin mutation that runs only for a caller who signed in recently.
 *
 * <p>The counterpart to {@link RecentSignInExempt}: {@code RecentSignInByDefaultArchTest} requires every
 * instance-admin mutation handler to carry exactly one of the two, so a new administrative action cannot
 * reach main without a recorded decision about re-authentication — the same shape {@link Audited} gives
 * the audit trail.
 *
 * <p>A refusal is recorded on the {@link Audited#type() type} the method already declares for
 * {@link AuditLedger#AUTH_EVENT}, so the attempt lands on the trail under the action it was aimed at.
 * That is why this annotation carries no type of its own, and why the architecture test also requires
 * that declaration.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRecentSignIn {}
