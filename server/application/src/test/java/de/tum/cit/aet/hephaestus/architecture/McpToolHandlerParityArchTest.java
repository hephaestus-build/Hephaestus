package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.AdminAccess;
import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * A workspace MCP tool calls the service of one HTTP handler, so it is at least as strict as that handler
 * (ADR 0110 decision 15). Every tool is admin-only, because only an admin can connect a client and a demoted
 * admin's grant outlives the demotion; so its handler may require at most admin. The effective
 * {@code @PreAuthorize} is resolved as the admin passkey advisor resolves it: on the method, else on the class.
 */
class McpToolHandlerParityArchTest extends HephaestusArchitectureTest {

    private static final String ADMIN = "@workspaceSecure.isAdmin()";

    /** A handler with one of these mappings changes the workspace, whatever its role or audit decision. */
    private static final List<Class<? extends Annotation>> MUTATIONS =
            List.of(PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    private static final Set<String> AT_MOST_ADMIN =
            Set.of("@workspaceSecure.isMember()", "@workspaceSecure.isMemberWithoutElevation()", ADMIN);

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
                problems.add(
                        type.getSimpleName() + " names " + target.controller().getSimpleName() + "." + target.method()
                                + ", which is not exactly one method");
                continue;
            }
            Method handler = handlers.getFirst();
            Method call = type.getMethod("call", Map.class);
            Optional<String> handlerRule = authorization(handler, target.controller());
            Optional<String> toolRule = authorization(call, type);
            if (!toolRule.equals(Optional.of(ADMIN))) {
                problems.add(
                        type.getSimpleName() + " carries " + toolRule.orElse("no authorization") + ", not " + ADMIN);
            }
            if (handlerRule.isPresent() && !AT_MOST_ADMIN.contains(handlerRule.get())) {
                problems.add(type.getSimpleName() + " stands for "
                        + target.controller().getSimpleName() + "." + target.method() + ", which requires "
                        + handlerRule.get() + ", more than " + ADMIN);
            }
            AdminAccess access = AnnotatedElementUtils.findMergedAnnotation(handler, AdminAccess.class);
            if (AnnotatedElementUtils.hasAnnotation(handler, RequiresRecentSignIn.class)
                    || (access != null && access.value() == AdminAccess.Scope.INSTANCE)) {
                problems.add(type.getSimpleName() + " stands for an instance-admin or recent-sign-in handler");
            }
        }
        assertThat(problems).isEmpty();
    }

    /**
     * A tool that stands for a mutation carries the same audit decision as its handler and needs {@code practices:write};
     * a tool that stands for a read carries neither (ADR 0110 decision 16, the {@code AuditByDefaultArchTest} rule for tools).
     */
    @Test
    void everyWriteToolCarriesTheAuditDecisionOfTheHandlerItStandsFor() throws Exception {
        List<String> problems = new ArrayList<>();
        for (var tool : classes.stream()
                .filter(type -> type.isAssignableTo(McpTool.class) && !type.isInterface())
                .toList()) {
            Class<?> type = tool.reflect();
            McpToolFor target = type.getAnnotation(McpToolFor.class);
            if (target == null) {
                continue;
            }
            Method handler = Arrays.stream(target.controller().getDeclaredMethods())
                    .filter(method -> method.getName().equals(target.method()))
                    .findFirst()
                    .orElseThrow();
            Method call = type.getMethod("call", Map.class);
            boolean mutation = handler.isAnnotationPresent(Audited.class)
                    || handler.isAnnotationPresent(AuditExempt.class)
                    || MUTATIONS.stream().anyMatch(mapping -> AnnotatedElementUtils.hasAnnotation(handler, mapping));
            boolean writes = Arrays.stream(type.getDeclaredMethods())
                    .anyMatch(method -> method.getName().equals("writes"));
            if (mutation != writes) {
                problems.add(type.getSimpleName()
                        + (mutation
                                ? " stands for a mutation but does not declare that it writes"
                                : " declares that it writes but stands for a read"));
            }
            if (!Objects.equals(handler.getAnnotation(Audited.class), call.getAnnotation(Audited.class))
                    || !Objects.equals(
                            handler.getAnnotation(AuditExempt.class), call.getAnnotation(AuditExempt.class))) {
                problems.add(type.getSimpleName() + ".call does not carry the audit annotation of "
                        + target.controller().getSimpleName() + "." + target.method());
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
