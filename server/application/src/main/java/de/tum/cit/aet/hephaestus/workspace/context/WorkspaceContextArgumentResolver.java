package de.tum.cit.aet.hephaestus.workspace.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Spring MVC argument resolver that injects {@link WorkspaceContext} into controller method parameters.
 * <p>
 * Retrieves the context from the ThreadLocal populated by {@link WorkspaceContextFilter}.
 * For workspace-scoped controllers (annotated with {@link WorkspaceScopedController}),
 * the filter guarantees the context is set before the controller is invoked.
 * <p>
 * If the context is not set (e.g., the filter was bypassed), this resolver throws
 * an {@link IllegalStateException} to fail fast rather than returning null.
 */
public class WorkspaceContextArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * Returns true if the parameter is of type {@link WorkspaceContext}.
     *
     * @param parameter the method parameter to check
     * @return true if this resolver supports the parameter type
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(WorkspaceContext.class);
    }

    /**
     * Resolves the {@link WorkspaceContext} from the current thread's context holder.
     *
     * @param parameter the method parameter
     * @param mavContainer the ModelAndViewContainer (unused)
     * @param webRequest the current web request (unused)
     * @param binderFactory the data binder factory (unused)
     * @return the current WorkspaceContext, never null
     * @throws IllegalStateException if no workspace context is set
     */
    @Override
    public Object resolveArgument(
            @NonNull MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            @NonNull NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        if (context == null) {
            throw new IllegalStateException(
                    "WorkspaceContext is not set. Make sure the endpoint is within a workspace-scoped controller "
                            + "or the WorkspaceContextFilter is properly configured.");
        }
        return context;
    }
}
