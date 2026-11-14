package de.tum.cit.aet.hephaestus.architecture.conditions;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * Which parameter names the workspace. A raw number never does by its type alone: only the exact name
 * {@code workspaceId} marks one, so an item id or a page size cannot pass as the workspace.
 */
public final class WorkspaceParameters {
    private WorkspaceParameters() {}

    /** The repository rule: a {@link Workspace}, or a {@code long} or {@code Long} named {@code workspaceId}. */
    public static boolean namesWorkspace(Class<?> type, String name) {
        return type.equals(Workspace.class)
                || (name.equals("workspaceId") && (type.equals(long.class) || type.equals(Long.class)));
    }

    /**
     * The service rule also accepts the request's {@link WorkspaceContext}, and a value that belongs to one workspace
     * and says which: a type with a public {@code getWorkspaceId()} or {@code workspaceId()}, such as a review.
     */
    public static boolean scopesToWorkspace(Parameter parameter) {
        Class<?> type = parameter.getType();
        return namesWorkspace(type, parameter.getName())
                || type.equals(WorkspaceContext.class)
                || Arrays.stream(type.getMethods()).anyMatch(WorkspaceParameters::isWorkspaceIdAccessor);
    }

    private static boolean isWorkspaceIdAccessor(Method method) {
        return !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0
                && (method.getName().equals("getWorkspaceId")
                        || method.getName().equals("workspaceId"))
                && (method.getReturnType().equals(long.class)
                        || method.getReturnType().equals(Long.class));
    }
}
