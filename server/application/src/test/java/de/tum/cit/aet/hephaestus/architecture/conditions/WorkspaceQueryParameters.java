package de.tum.cit.aet.hephaestus.architecture.conditions;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data's explicit parameter name takes precedence over the Java source name. */
public final class WorkspaceQueryParameters {
    private WorkspaceQueryParameters() {}

    public static boolean bindsWorkspaceParameter(Method method) {
        Query query = method.getAnnotation(Query.class);
        if (query == null && !method.isDefault()) return false;
        return Arrays.stream(method.getParameters()).anyMatch(parameter -> {
            Param annotation = parameter.getAnnotation(Param.class);
            String name = annotation == null ? parameter.getName() : annotation.value();
            boolean workspace = WorkspaceParameters.namesWorkspace(parameter.getType(), name);
            return workspace
                    && (query == null
                            || Pattern.compile(Pattern.quote(":" + name) + "(?![A-Za-z0-9_])")
                                    .matcher(query.value())
                                    .find());
        });
    }
}
