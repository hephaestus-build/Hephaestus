package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * A signed-in user with no instance authority, for tests that decide access by workspace membership.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMentorUserSecurityContextFactory.class)
public @interface WithMentorUser {
    /**
     * The username of the mock mentor user.
     */
    String username() default "mentor";

    /**
     * The authorities/roles for the mock mentor user.
     */
    String[] authorities() default {};

    /**
     * The user ID for the mock mentor user.
     */
    String userId() default "mentor-user-id";
}
