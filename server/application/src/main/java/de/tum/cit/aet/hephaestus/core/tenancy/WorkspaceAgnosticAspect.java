package de.tum.cit.aet.hephaestus.core.tenancy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

/**
 * Makes {@link WorkspaceAgnostic} load-bearing at runtime: while an annotated method (or a
 * method on an annotated class) is on the stack, {@link TenancyBypass} reports active and
 * {@link WorkspaceStatementInspector} treats emitted SQL as exempt.
 *
 * <p>Three advice points, in precedence order:
 * <ul>
 *   <li>{@code @annotation} — method-level annotation (any bean).</li>
 *   <li>{@code @within} — annotation on the declaring type of the method being invoked.
 *       This catches custom interface methods on annotated repositories and methods on
 *       annotated services. It does <b>not</b> catch inherited Spring Data methods
 *       ({@code findAll}, {@code findById}, …) because their declaring type is
 *       {@code CrudRepository}/{@code JpaRepository}, not the user's annotated interface.</li>
 *   <li>Spring Data {@code Repository+} catch-all — runs for every repository method
 *       and walks the proxy's implemented interfaces with {@link AnnotationUtils#findAnnotation}.
 *       If any user interface carries {@code @WorkspaceAgnostic}, the bypass opens.
 *       Without this third advice, inherited methods on annotated repositories would
 *       silently bypass the aspect and trigger {@link TenancyViolationException}.</li>
 * </ul>
 *
 * <p>Spring AOP proxy model limitations the aspect inherits:
 * <ul>
 *   <li>Static methods are not advised.</li>
 *   <li>Self-invocation ({@code this.foo()} inside the same bean) bypasses the proxy.</li>
 *   <li>{@code @Async} hops onto a worker thread; the bypass scope does not propagate
 *       unless the worker explicitly re-opens it.</li>
 * </ul>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkspaceAgnosticAspect {

    @Around("@annotation(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic)")
    public Object aroundAnnotatedMethod(ProceedingJoinPoint pjp) throws Throwable {
        WorkspaceAgnostic annotation =
                ((MethodSignature) pjp.getSignature()).getMethod().getAnnotation(WorkspaceAgnostic.class);
        return proceedWithBypass(pjp, annotation);
    }

    @Around("@within(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic) "
            + "&& !@annotation(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic)")
    public Object aroundAnnotatedType(ProceedingJoinPoint pjp) throws Throwable {
        Class<?> declaringType = pjp.getSignature().getDeclaringType();
        WorkspaceAgnostic annotation = declaringType.getAnnotation(WorkspaceAgnostic.class);
        return proceedWithBypass(pjp, annotation);
    }

    /**
     * Catches inherited Spring Data methods on {@code @WorkspaceAgnostic}-annotated
     * repositories. Spring AOP's {@code @within} matches by the method's declaring type
     * (e.g. {@code JpaRepository}), so for {@code findAll}/{@code findById} the
     * type-level advice above does not fire. Here we match every Spring Data repository
     * call and walk the proxy's interfaces for the annotation — only opening a bypass if
     * we find one (so non-annotated repos pay just a one-method-lookup tax).
     *
     * <p>Excludes methods already covered by the previous advices to keep depth counting
     * honest (an inherited method whose declaring type is also annotated would otherwise
     * open the bypass twice).
     */
    @Around("execution(* org.springframework.data.repository.Repository+.*(..)) "
            + "&& !@annotation(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic) "
            + "&& !@within(de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic)")
    public Object aroundInheritedRepositoryMethod(ProceedingJoinPoint pjp) throws Throwable {
        Object target = pjp.getTarget();
        if (target == null) {
            return pjp.proceed();
        }
        WorkspaceAgnostic annotation = findAnnotationOnInterfaces(target.getClass());
        if (annotation == null) {
            return pjp.proceed();
        }
        return proceedWithBypass(pjp, annotation);
    }

    /**
     * Walks all interfaces implemented by {@code clazz} (and its supertypes) looking for
     * {@link WorkspaceAgnostic}. We cannot rely on the JDK proxy class itself because
     * proxy classes do not inherit interface annotations; we must inspect each interface.
     */
    private static @Nullable WorkspaceAgnostic findAnnotationOnInterfaces(Class<?> clazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (!Repository.class.isAssignableFrom(iface)) continue;
            WorkspaceAgnostic ann = AnnotationUtils.findAnnotation(iface, WorkspaceAgnostic.class);
            if (ann != null) return ann;
        }
        return null;
    }

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    private static Object proceedWithBypass(ProceedingJoinPoint pjp, WorkspaceAgnostic ann) throws Throwable {
        String reason = ann != null ? ann.value() : "anonymous";

        try (TenancyBypass.Scope ignored = TenancyBypass.open(reason)) {
            return pjp.proceed();
        }
    }
}
