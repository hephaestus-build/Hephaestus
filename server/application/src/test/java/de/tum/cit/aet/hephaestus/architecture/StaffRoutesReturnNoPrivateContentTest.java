package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.core.PrivateContent;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR 0125 and ADR 0164: no staff route returns a person's private content. Staff are instance admins, workspace admins
 * and workspace owners.
 *
 * <p>Private content is found from the routes that serve it, not listed by hand. Each type that a route of the person's
 * own conversation, Practice profile or support shares returns is private, and so is each {@link PrivateContent} type.
 * Then the response of every staff handler is walked, field by field, and must reach none of them. A new type on a
 * person's route is private from its first route.
 *
 * <p>One staff route returns a person's words: the read of a support share, which the person addressed to that admin and
 * can revoke ({@link #SANCTIONED}). A correction is not private content: the person writes it to ask staff to correct
 * a result, and it shows for 30 days (ADR 0125 decision 3).
 */
class StaffRoutesReturnNoPrivateContentTest extends HephaestusArchitectureTest {

    /** The routes that only their person reads, as their controllers map them. */
    private static final List<String> PRIVATE_PATHS = List.of(
            "/conversation",
            "/practice-profile",
            "/practices/standings",
            "/practice-groups/standings",
            "/practices/results",
            "/practices/feedback/practice-page",
            "/support-shares",
            "/user/support-shares");

    /** The staff handlers that return a person's content, each with the act of the person that allows it. */
    private static final Map<String, String> SANCTIONED = Map.of(
            "SupportShareAdminController.list",
            "lists the shares that people addressed to this admin; it shows no messages",
            "SupportShareAdminController.read",
            "reads one turn that its person shared with this admin, while the person lets it run");

    /**
     * Shapes that a person's route returns and that carry nothing of the person: the route makes them private by
     * reading only the person's own rows. Each says why a staff route may return the same shape.
     */
    private static final Map<Class<?>, String> SHARED_SHAPES = Map.of(
            ResultListDTO.class,
            "one result on one item, with no person; a scan report lists the results of its items (ADR 0125 decision 3,"
                    + " #4988)");

    private static final String INSTANCE_ADMIN_GATE = "hasAuthority('app_admin')";
    private static final Pattern WORKSPACE_STAFF_GATE = Pattern.compile("@workspaceSecure\\.(isAdmin|isOwner)\\b");

    @Test
    void shouldReturnNoPrivateContentFromAStaffRoute() {
        Set<Class<?>> privateTypes = privateTypes();
        assertThat(privateTypes)
                .as("the person's conversation must be private content, or the rule finds nothing")
                .contains(ConversationEntryDTO.class);

        Map<String, String> found = new TreeMap<>();
        Set<String> sanctionedAndFound = new TreeSet<>();
        staffHandlers().forEach(handler -> {
            String route = handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
            reached(ResolvableType.forMethodReturnType(handler), privateTypes).ifPresent(type -> {
                if (SANCTIONED.containsKey(route)) sanctionedAndFound.add(route);
                else found.put(route, type.getSimpleName());
            });
        });

        assertThat(found)
                .as("staff routes that return a person's private content, with the private type each reaches")
                .isEmpty();
        assertThat(sanctionedAndFound)
                .as("sanctioned routes that no longer return private content: remove them from SANCTIONED")
                .containsExactlyInAnyOrderElementsOf(SANCTIONED.keySet());
    }

    /** The removed staff views of a person (ADR 0125 decision 2): none may come back under its old name. */
    private static final Pattern REMOVED_VIEW = Pattern.compile(
            ".*(ViewAsUser|UserViewRead|UserViewContext|UserViewSession|ViewedAccount|PerPersonResults|Impersonat).*");

    @Test
    void shouldHaveNoClassOfARemovedStaffView() {
        assertThat(classes.stream().map(JavaClass::getSimpleName).filter(REMOVED_VIEW.asMatchPredicate()))
                .as("classes named for View as user, Per-person results for admins or impersonation")
                .isEmpty();
    }

    /** A staff route that tries to hand over a person's conversation, inside another shape. */
    @RestController
    @RequestMapping("/fixture")
    @RequireInstanceAdmin
    static class LeakingFixture {
        record SupportCaseDTO(String title, List<ConversationEntryDTO> messages) {}

        @GetMapping("/case")
        Optional<SupportCaseDTO> supportCase() {
            return Optional.empty();
        }
    }

    @Test
    void shouldFindAConversationInsideAStaffResponse() throws NoSuchMethodException {
        Method handler = LeakingFixture.class.getDeclaredMethod("supportCase");
        assertThat(isStaff(handler)).isTrue();
        assertThat(reached(ResolvableType.forMethodReturnType(handler), privateTypes()))
                .contains(ConversationEntryDTO.class);
    }

    private static Set<Class<?>> privateTypes() {
        Set<Class<?>> types = new HashSet<>();
        classes.stream()
                .filter(type -> type.isAnnotatedWith(PrivateContent.class))
                .map(JavaClass::reflect)
                .forEach(types::add);
        handlers()
                .filter(StaffRoutesReturnNoPrivateContentTest::isPrivateRoute)
                .filter(handler -> !isStaff(handler))
                .forEach(handler -> contentTypes(ResolvableType.forMethodReturnType(handler), types));
        assertThat(types)
                .as("shared shapes that no person's route returns any more: remove them from SHARED_SHAPES")
                .containsAll(SHARED_SHAPES.keySet());
        types.removeAll(SHARED_SHAPES.keySet());
        return types;
    }

    /** The project types a person's route returns: generic pages and containers are unwrapped to what they hold. */
    private static void contentTypes(ResolvableType type, Set<Class<?>> into) {
        Class<?> raw = type.resolve();
        if (raw == null) return;
        if (raw.isArray()) {
            contentTypes(type.getComponentType(), into);
            return;
        }
        if (isContainer(raw) || (isProjectType(raw) && type.hasGenerics())) {
            Arrays.stream(type.getGenerics()).forEach(generic -> contentTypes(generic, into));
            if (isContainer(raw)) return;
        }
        if (isProjectType(raw) && !raw.isEnum() && raw.getTypeParameters().length == 0) into.add(raw);
    }

    /** The first private type that a response reaches through its fields, containers and subtypes. */
    private static Optional<Class<?>> reached(ResolvableType type, Set<Class<?>> privateTypes) {
        return reached(type, privateTypes, new HashSet<>());
    }

    private static Optional<Class<?>> reached(ResolvableType type, Set<Class<?>> privateTypes, Set<String> seen) {
        Class<?> raw = type.resolve();
        if (raw == null || !seen.add(type.toString())) return Optional.empty();
        if (privateTypes.contains(raw)) return Optional.of(raw);
        if (raw.isArray()) return reached(type.getComponentType(), privateTypes, seen);
        Stream<ResolvableType> next = Arrays.stream(type.getGenerics());
        if (isProjectType(raw) && !raw.isEnum()) {
            Stream<ResolvableType> fields = Stream.<Class<?>>iterate(
                            raw, declaring -> declaring != null && isProjectType(declaring), Class::getSuperclass)
                    .flatMap(declaring -> Arrays.stream(declaring.getDeclaredFields()))
                    .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                    .map((Field field) -> ResolvableType.forField(field, type));
            Class<?>[] permitted = raw.getPermittedSubclasses();
            Stream<ResolvableType> subtypes = permitted == null
                    ? Stream.empty()
                    : Arrays.stream(permitted).map(ResolvableType::forClass);
            next = Stream.concat(next, Stream.concat(fields, subtypes));
        }
        return next.map(child -> reached(child, privateTypes, seen))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Stream<Method> handlers() {
        return classes.stream()
                .map(JavaClass::reflect)
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, RestController.class))
                .flatMap(controller -> Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(controller)))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
    }

    private static Stream<Method> staffHandlers() {
        return handlers().filter(StaffRoutesReturnNoPrivateContentTest::isStaff);
    }

    private static boolean isPrivateRoute(Method handler) {
        return Stream.concat(paths(handler.getDeclaringClass()), paths(handler))
                .anyMatch(path -> PRIVATE_PATHS.stream().anyMatch(path::startsWith));
    }

    private static boolean isStaff(Method handler) {
        return isStaff((AnnotatedElement) handler) || isStaff(handler.getDeclaringClass());
    }

    private static boolean isStaff(AnnotatedElement element) {
        if (AnnotatedElementUtils.hasAnnotation(element, RequireInstanceAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(element, RequireAtLeastWorkspaceAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(element, RequireWorkspaceOwner.class)) {
            return true;
        }
        PreAuthorize gate = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        return gate != null
                && (gate.value().contains(INSTANCE_ADMIN_GATE)
                        || WORKSPACE_STAFF_GATE.matcher(gate.value()).find());
    }

    private static Stream<String> paths(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        return mapping == null ? Stream.empty() : Arrays.stream(mapping.path());
    }

    private static boolean isProjectType(Class<?> type) {
        return type.getName().startsWith(BASE_PACKAGE);
    }

    private static boolean isContainer(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || Stream.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type)
                || HttpEntity.class.isAssignableFrom(type);
    }
}
