package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Factory to create a mock security context with conversation user authentication for tests.
 */
public class WithConversationUserSecurityContextFactory implements WithSecurityContextFactory<WithConversationUser> {

    @Override
    public SecurityContext createSecurityContext(WithConversationUser annotation) {
        return MockSecurityContextUtils.createSecurityContext(
                annotation.userId(), annotation.authorities(), "mock-jwt-token-for-conversation-user");
    }
}
