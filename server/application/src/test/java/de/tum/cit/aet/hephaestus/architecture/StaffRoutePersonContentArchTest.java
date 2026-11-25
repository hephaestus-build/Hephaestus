package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/**
 * No staff route returns a person's conversation (ADR 0125, ADR 0131 decision 5): an instance admin's route or a
 * workspace admin's or owner's route carries no conversation type in its response, and its only raw bodies are ones
 * whose content is known. The person's own export answers a data-subject request (#4966).
 */
class StaffRoutePersonContentArchTest extends HephaestusArchitectureTest {

    private static final List<String> CONVERSATION_PACKAGES = List.of(
            ArchitectureTestConstants.BASE_PACKAGE + ".conversation",
            ArchitectureTestConstants.BASE_PACKAGE + ".agent.conversation");

    /** Raw bodies that read no conversation: the audit log as CSV, and one consented, pseudonymous research dataset. */
    private static final Set<String> KNOWN_RAW_BODIES =
            Set.of("AuthAuditController.export", "ResearchDisclosureController.release");

    private static final Set<Class<?>> RAW_BODIES = Set.of(
            byte[].class, String.class, Object.class, JsonNode.class, Resource.class, StreamingResponseBody.class);

    @Test
    void noStaffRouteReturnsAConversationType() {
        assertThat(staffRoutes()
                        .filter(route -> reachableTypes(route.getGenericReturnType()).stream()
                                .anyMatch(StaffRoutePersonContentArchTest::isConversationType))
                        .map(StaffRoutePersonContentArchTest::name)
                        .toList())
                .as("staff routes whose response carries a conversation type")
                .isEmpty();
    }

    @Test
    void aStaffRouteReturnsARawBodyOnlyWhenItsContentIsKnown() {
        assertThat(staffRoutes()
                        .filter(route -> RAW_BODIES.contains(body(route.getGenericReturnType())))
                        .map(StaffRoutePersonContentArchTest::name)
                        .toList())
                .as("staff routes with a raw body; the removed admin person-data export was one")
                .containsExactlyInAnyOrderElementsOf(KNOWN_RAW_BODIES);
    }

    private static Stream<Method> staffRoutes() {
        return classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .map(JavaClass::reflect)
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                        .filter(method -> isStaff(controller, method)));
    }

    private static boolean isStaff(Class<?> controller, Method method) {
        return Stream.of(controller, method)
                        .anyMatch(element -> paths(element).stream().anyMatch(path -> path.startsWith("/admin")))
                || Stream.of(controller, method).anyMatch(StaffRoutePersonContentArchTest::requiresStaff);
    }

    private static boolean requiresStaff(AnnotatedElement element) {
        if (AnnotatedElementUtils.hasAnnotation(element, RequireAtLeastWorkspaceAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(element, RequireWorkspaceOwner.class)) return true;
        var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        return preAuthorize != null && preAuthorize.value().contains("app_admin");
    }

    private static List<String> paths(AnnotatedElement element) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        return mapping == null ? List.of() : List.of(mapping.path());
    }

    /** The response body's type: the type argument of a {@link HttpEntity}, or the return type itself. */
    private static Type body(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && HttpEntity.class.isAssignableFrom(raw)) return parameterized.getActualTypeArguments()[0];
        return type;
    }

    /** Every class the type reaches through type arguments and the fields of the application's own types. */
    private static Set<Class<?>> reachableTypes(Type root) {
        Set<Class<?>> seen = new HashSet<>();
        var pending = new ArrayDeque<Type>(List.of(root));
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            switch (type) {
                case Class<?> raw when raw.isArray() -> pending.push(raw.getComponentType());
                case Class<?> raw -> {
                    if (seen.add(raw) && raw.getName().startsWith(ArchitectureTestConstants.BASE_PACKAGE))
                        Arrays.stream(raw.getDeclaredFields())
                                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                .forEach(field -> pending.push(field.getGenericType()));
                }
                case ParameterizedType parameterized -> {
                    pending.push(parameterized.getRawType());
                    Arrays.stream(parameterized.getActualTypeArguments()).forEach(pending::push);
                }
                case GenericArrayType array -> pending.push(array.getGenericComponentType());
                case WildcardType wildcard ->
                    Arrays.stream(wildcard.getUpperBounds()).forEach(pending::push);
                default -> {}
            }
        }
        return seen;
    }

    private static boolean isConversationType(Class<?> type) {
        return CONVERSATION_PACKAGES.stream()
                .anyMatch(root -> type.getName().equals(root) || type.getName().startsWith(root + "."));
    }

    private static String name(Method route) {
        return route.getDeclaringClass().getSimpleName() + "." + route.getName();
    }
}
