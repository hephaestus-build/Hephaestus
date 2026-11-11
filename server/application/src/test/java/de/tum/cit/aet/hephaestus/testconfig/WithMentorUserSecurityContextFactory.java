package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Factory to create a mock security context with mentor user authentication for tests.
 */
public class WithMentorUserSecurityContextFactory implements WithSecurityContextFactory<WithMentorUser> {

    @Override
    public SecurityContext createSecurityContext(WithMentorUser annotation) {
        return MockSecurityContextUtils.createSecurityContext(
                annotation.userId(), annotation.authorities(), "mock-jwt-token-for-mentor-user");
    }
}
