package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Answers a team-only route of a personal space as a workspace that does not exist. {@link WorkspaceContextFilter}
 * has already let only the owner in; this keeps the owner to the routes that opted in with {@link PersonalAllowed}.
 */
class WorkspaceKindInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        if (context != null
                && context.kind() == WorkspaceKind.PERSONAL
                && handler instanceof HandlerMethod method
                && AnnotatedElementUtils.hasAnnotation(method.getBeanType(), WorkspaceScopedController.class)
                && !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PersonalAllowed.class)) {
            throw new EntityNotFoundException("Workspace", context.slug());
        }
        return true;
    }
}
