package de.tum.cit.aet.hephaestus.core.tenancy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Makes a method-level {@link WorkspaceAgnostic} load-bearing at runtime: while the annotated method is on the stack,
 * {@link TenancyBypass} reports active and {@link WorkspaceStatementInspector} treats emitted SQL as exempt.
 *
 * <p>A marker on a type opens no bypass (ADR 0077, decision 5). An exemption names the one method whose statements
 * must reach every workspace, so that every other method of the type stays under the check. To exempt an inherited
 * Spring Data method, override it on the repository and mark the override.
 *
 * <p>Spring AOP proxy model limitations the aspect inherits:
 * <ul>
 *   <li>Static methods are not advised.</li>
 *   <li>Self-invocation ({@code this.foo()} inside the same bean, including a default repository method that calls
 *       another) bypasses the proxy. Mark the method that callers reach through the proxy.</li>
 *   <li>{@code @Async} hops onto a worker thread; the bypass scope does not propagate
 *       unless the worker explicitly re-opens it.</li>
 * </ul>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkspaceAgnosticAspect {

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Around("@annotation(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic)")
    public Object aroundAnnotatedMethod(ProceedingJoinPoint pjp) throws Throwable {
        WorkspaceAgnostic annotation =
                ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(WorkspaceAgnostic.class);
        try (TenancyBypass.Scope ignored = TenancyBypass.open(annotation != null ? annotation.value() : "anonymous")) {
            return pjp.proceed();
        }
    }
}
