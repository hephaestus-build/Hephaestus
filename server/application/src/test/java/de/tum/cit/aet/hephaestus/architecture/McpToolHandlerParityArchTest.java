package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.AdminAccess;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * A workspace MCP tool calls the service of one HTTP handler, so it must carry that handler's authorization
 * (ADR 0110 decision 15). The effective {@code @PreAuthorize} is resolved as the admin passkey advisor
 * resolves it: on the method, else on the class.
 */
class McpToolHandlerParityArchTest extends HephaestusArchitectureTest {

    @Test
    void everyMcpToolCarriesTheAuthorizationOfTheHandlerItStandsFor() throws Exception {
        List<String> problems = new ArrayList<>();
        var tools = classes.stream()
                .filter(type -> type.isAssignableTo(McpTool.class) && !type.isInterface())
                .toList();
        assertThat(tools).as("the tool set is not empty").isNotEmpty();
        for (var tool : tools) {
            Class<?> type = tool.reflect();
            McpToolFor target = type.getAnnotation(McpToolFor.class);
            if (target == null) {
                problems.add(type.getSimpleName() + " declares no @McpToolFor");
                continue;
            }
            List<Method> handlers = Arrays.stream(target.controller().getDeclaredMethods())
                    .filter(method -> method.getName().equals(target.method()))
                    .toList();
            if (handlers.size() != 1) {
                problems.add(type.getSimpleName() + " names " + target.controller().getSimpleName() + "." + target.method()
                        + ", which is not exactly one method");
                continue;
            }
            Method handler = handlers.getFirst();
            Method call = type.getMethod("call", Map.class);
            Optional<String> expected = authorization(handler, target.controller());
            Optional<String> actual = authorization(call, type);
            if (!Objects.equals(expected, actual)) {
                problems.add(type.getSimpleName() + " carries " + actual.orElse("no authorization") + " but its handler "
                        + target.controller().getSimpleName() + "." + target.method() + " carries "
                        + expected.orElse("no authorization"));
            }
            AdminAccess access = AnnotatedElementUtils.findMergedAnnotation(handler, AdminAccess.class);
            if (AnnotatedElementUtils.hasAnnotation(handler, RequiresRecentSignIn.class)
                    || (access != null && access.value() == AdminAccess.Scope.INSTANCE)) {
                problems.add(type.getSimpleName() + " stands for an instance-admin or recent-sign-in handler");
            }
        }
        assertThat(problems).isEmpty();
    }

    private static Optional<String> authorization(Method method, Class<?> owner) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (onMethod != null) {
            return Optional.of(onMethod.value());
        }
        return Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(owner, PreAuthorize.class))
                .map(PreAuthorize::value);
    }
}
