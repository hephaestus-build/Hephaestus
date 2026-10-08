package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.AdminAccess;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceAdminAssurance;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.web.bind.annotation.GetMapping;

/** Adds assurance to existing role checks without changing the meaning of a role. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
public class AdminPasskeyAuthorizationConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor adminPasskeyAuthorizationAdvisor(
            ObjectProvider<PasskeyAssurancePolicy> policy, ObjectProvider<WorkspaceAdminAssurance> workspace) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return scope(method, targetClass) != null;
            }
        };
        var interceptor = new AuthorizationManagerBeforeMethodInterceptor(pointcut, (authentication, invocation) -> {
            Method method = invocation.getMethod();
            Object target = invocation.getThis();
            AdminAccess.@Nullable Scope scope =
                    scope(method, target == null ? method.getDeclaringClass() : target.getClass());
            boolean sensitive = !AnnotatedElementUtils.hasAnnotation(method, GetMapping.class)
                    || AnnotatedElementUtils.hasAnnotation(method, RequiresRecentSignIn.class);
            if (scope == AdminAccess.Scope.INSTANCE) {
                policy.getObject().requireInstanceAdmin(authentication.get(), sensitive);
            }
            if (scope == AdminAccess.Scope.WORKSPACE) {
                workspace.getObject().require(sensitive);
            }
            return new AuthorizationDecision(true);
        });
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
        return interceptor;
    }

    static AdminAccess.@Nullable Scope scope(Method method, Class<?> targetClass) {
        // A method-level authorization declaration replaces the class-level requirement.
        AdminAccess access = AnnotatedElementUtils.findMergedAnnotation(method, AdminAccess.class);
        if (access == null && !AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)) {
            access = AnnotatedElementUtils.findMergedAnnotation(targetClass, AdminAccess.class);
        }
        return access == null ? null : access.value();
    }
}
