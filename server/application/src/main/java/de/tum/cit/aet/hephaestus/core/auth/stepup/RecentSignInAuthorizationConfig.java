package de.tum.cit.aet.hephaestus.core.auth.stepup;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.Authentication;

/**
 * Enforces {@link RequiresRecentSignIn} through Spring Security's method-security pipeline, so the
 * requirement is declared on the handler rather than called for by hand in each service.
 *
 * <p>Ordered after {@code @PreAuthorize} so a caller who is not an instance admin is refused as
 * forbidden rather than invited to confirm access — and so the refusal we do record is always one an
 * administrator actually hit.
 */
@ConditionalOnServerRole
@Configuration(proxyBeanMethods = false)
public class RecentSignInAuthorizationConfig {

    /**
     * Static so the advisor is available while the AOP infrastructure is built; the policy is resolved
     * per invocation through the provider, which keeps that early wiring from pulling the auth module's
     * beans up with it.
     */
    @Bean
    static Advisor recentSignInAuthorizationAdvisor(ObjectProvider<RecentSignInPolicy> policy) {
        AuthorizationManager<MethodInvocation> manager = (authentication, invocation) -> {
            Authentication current = authentication.get();
            policy.getObject().require(current, auditType(invocation), actingAccountId(current));
            return new AuthorizationDecision(true);
        };
        AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                new AnnotationMatchingPointcut(null, RequiresRecentSignIn.class, true), manager);
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
        return interceptor;
    }

    /**
     * The event type the handler already declares for its audit row. Keeping one declaration means a
     * refused attempt and a completed action can never be filed under different names.
     */
    private static AuthEvent.EventType auditType(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Audited audited = AnnotatedElementUtils.findMergedAnnotation(method, Audited.class);
        if (audited == null || audited.ledger() != AuditLedger.AUTH_EVENT) {
            throw new IllegalStateException("@RequiresRecentSignIn needs @Audited(ledger = AUTH_EVENT) on "
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName());
        }
        return AuthEvent.EventType.valueOf(audited.type());
    }

    /** The JWT's {@code sub} is the account id; anything else means no account to name on the trail. */
    @Nullable
    private static Long actingAccountId(@Nullable Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
