package de.tum.cit.aet.hephaestus.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an instance-admin mutation that deliberately runs without a recent sign-in, with a mandatory
 * reason. The counterpart to {@link RequiresRecentSignIn}: it turns every ungated administrative action
 * into a greppable, reviewed decision instead of an omission nobody chose.
 *
 * <p>Allowed on the controller as well as the handler, because a whole surface can share one reason;
 * a handler-level annotation overrides the controller's.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RecentSignInExempt {
    /** Why this admin mutation does not ask for a fresh sign-in (e.g. "changes presentation, not access"). */
    String reason();
}
