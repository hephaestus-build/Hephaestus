package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Factory to create a mock security context with regular user authentication for tests.
 */
public class WithUserSecurityContextFactory implements WithSecurityContextFactory<WithUser> {

    @Override
    public SecurityContext createSecurityContext(WithUser annotation) {
        return MockSecurityContextUtils.createSecurityContext(
                annotation.userId(), annotation.authorities(), "mock-jwt-token-for-test-user");
    }
}
