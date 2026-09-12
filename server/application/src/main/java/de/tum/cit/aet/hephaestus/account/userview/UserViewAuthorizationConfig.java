package de.tum.cit.aet.hephaestus.account.userview;

import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Parameter;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Ordered after the step-up gate, so a refused confirmation never leaves a {@code USER_VIEW} success behind. */
@ConditionalOnServerRole
@Configuration(proxyBeanMethods = false)
public class UserViewAuthorizationConfig {

    public static final String REASON_HEADER = "X-User-View-Reason";

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor userViewAuthorizationAdvisor(
            ObjectProvider<ViewedUserService> viewedUsers, ObjectProvider<UserViewAccess> access) {
        AuthorizationManager<MethodInvocation> manager = (authentication, invocation) -> {
            HttpServletRequest request =
                    ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            String reason = request.getHeader(REASON_HEADER);
            if (reason == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + REASON_HEADER + " header");
            }
            long workspaceId = WorkspaceContextHolder.getContext().id();
            long userId = viewedUserId(invocation);
            var user = viewedUsers.getObject().requireUser(workspaceId, userId);
            String query = request.getQueryString();
            access.getObject()
                    .record(
                            workspaceId,
                            userId,
                            user.accountId(),
                            reason,
                            query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query);
            return new AuthorizationDecision(true);
        };
        AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                new AnnotationMatchingPointcut(null, UserViewRead.class, true), manager);
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 2);
        return interceptor;
    }

    /** Spring has already converted the {@code userId} path variable by the time the advisor runs. */
    private static long viewedUserId(MethodInvocation invocation) {
        Parameter[] parameters = invocation.getMethod().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals("userId") && invocation.getArguments()[i] instanceof Long userId) {
                return userId;
            }
        }
        throw new IllegalStateException(invocation.getMethod() + " carries @UserViewRead but has no userId");
    }
}
