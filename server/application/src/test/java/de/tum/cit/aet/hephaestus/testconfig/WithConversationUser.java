package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * A signed-in user with no instance authority, for tests that decide access by workspace membership.
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithConversationUserSecurityContextFactory.class)
public @interface WithConversationUser {
    /**
     * The username of the mock conversation user.
     */
    String username() default "conversation";

    /**
     * The authorities/roles for the mock conversation user.
     */
    String[] authorities() default {};

    /**
     * The user ID for the mock conversation user.
     */
    String userId() default "conversation-user-id";
}
