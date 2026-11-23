package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.AdminAccess;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No staff route shows a count of fewer than five people. Generated from the routes: every handler that only a
 * workspace admin, a workspace owner or an instance admin can call has its response type walked, and every number
 * whose name counts people must be listed here. A count that names nobody is {@link Reason#FLOORED}: it goes through
 * {@code SmallCounts} and so is nullable. A unit test of its producer shows the floor applies. Anything else must say
 * why a floor would protect nobody.
 */
class StaffRoutePeopleCountTest extends HephaestusArchitectureTest {

    private static final Pattern NAMES_PEOPLE = Pattern.compile(
            "(?i).*(people|person|developer|member|contributor|participant|author|reviewer|user|account|admin|owner"
                    + "|respondent|assignee).*");

    /** A number that identifies or describes one person or thing, not a count of people. */
    private static final Pattern NOT_A_COUNT = Pattern.compile("(?i).*(id|ids|at|url|login|logins|name|names|key)");

    private static final Pattern STAFF_GATE = Pattern.compile("isAdmin\\(\\)|isOwner\\(\\)|'app_admin'");

    private enum Reason {
        /** Shown through {@code SmallCounts}: null for one to four. */
        FLOORED,
        /** The same people are listed by name to this reader, so a floor would hide nothing. */
        NAMED_ELSEWHERE
    }

    private static final Map<String, Reason> KNOWN = Map.of(
            "SlackMonitoredChannelDTO.optedOutMemberCount", Reason.FLOORED,
            // Admins hide people by name, and the activity list names everyone the public page leaves out, so a
            // floor on this count comes back by subtraction.
            "PublicActivityHiddenCountDTO.hiddenPeople", Reason.NAMED_ELSEWHERE,
            // The review scope is chosen by account, and the member list names who is in it.
            "PracticeReviewScopeSummaryDTO.eligiblePeople", Reason.NAMED_ELSEWHERE,
            "PracticeReviewScopeSummaryDTO.selectedPeople", Reason.NAMED_ELSEWHERE,
            // The workspace's members are listed by name on its member page.
            "AdminWorkspaceViewDTO.memberCount", Reason.NAMED_ELSEWHERE,
            // A team lists its members beside its count.
            "TeamInfoDTO.membershipCount", Reason.NAMED_ELSEWHERE);

    @Test
    void shouldListEveryCountOfPeopleThatAStaffRouteReturns() {
        Map<String, String> found = new TreeMap<>();
        classes.stream()
                .map(JavaClass::reflect)
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, RestController.class))
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods())
                        .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                        .filter(method -> isStaffOnly(controller, method)))
                .forEach(handler -> walk(
                        ResolvableType.forMethodReturnType(handler),
                        handler.getDeclaringClass().getSimpleName() + "." + handler.getName(),
                        new HashSet<>(),
                        found));

        assertThat(found.keySet())
                .as("counts of people on staff routes; route each through SmallCounts or say why not: %s", found)
                .containsExactlyInAnyOrderElementsOf(KNOWN.keySet());
    }

    @Test
    void shouldLetEveryFlooredCountBeWithheld() {
        Map<String, Boolean> nullable = new TreeMap<>();
        classes.stream()
                .map(JavaClass::reflect)
                .forEach(type -> fields(type)
                        .filter(field -> KNOWN.get(type.getSimpleName() + "." + field.getName()) == Reason.FLOORED)
                        .forEach(field -> nullable.put(
                                type.getSimpleName() + "." + field.getName(),
                                field.getAnnotatedType().isAnnotationPresent(Nullable.class))));

        assertThat(nullable)
                .hasSize((int)
                        KNOWN.values().stream().filter(Reason.FLOORED::equals).count())
                .allSatisfy((count, canBeWithheld) ->
                        assertThat(canBeWithheld).as(count).isTrue());
    }

    /** The gate that decides: the handler's own, else its controller's. Staff when it is an admin gate. */
    private static boolean isStaffOnly(Class<?> controller, Method handler) {
        boolean handlerGated = AnnotatedElementUtils.hasAnnotation(handler, PreAuthorize.class);
        AnnotatedElement decides = handlerGated ? handler : controller;
        PreAuthorize gate = AnnotatedElementUtils.findMergedAnnotation(decides, PreAuthorize.class);
        return AnnotatedElementUtils.hasAnnotation(decides, AdminAccess.class)
                || (gate != null && STAFF_GATE.matcher(gate.value()).find());
    }

    private static void walk(ResolvableType type, String route, Set<String> seen, Map<String, String> found) {
        Class<?> raw = type.resolve();
        if (raw == null || !seen.add(type.toString())) {
            return;
        }
        if (raw.isArray()) {
            walk(type.getComponentType(), route, seen, found);
            return;
        }
        if (isContainer(raw)) {
            Arrays.stream(type.getGenerics()).forEach(generic -> walk(generic, route, seen, found));
            return;
        }
        if (!raw.getName().startsWith(BASE_PACKAGE) || raw.isEnum()) {
            return;
        }
        for (Class<?> declaring = raw;
                declaring != null && declaring.getName().startsWith(BASE_PACKAGE);
                declaring = declaring.getSuperclass()) {
            fields(declaring).forEach(field -> {
                ResolvableType fieldType = ResolvableType.forField(field, type);
                if (carriesNumbers(fieldType) && namesACountOfPeople(field.getName())) {
                    found.merge(raw.getSimpleName() + "." + field.getName(), route, (first, next) -> first);
                }
                walk(fieldType, route, seen, found);
            });
        }
        Class<?>[] permitted = raw.getPermittedSubclasses();
        if (permitted != null) {
            Arrays.stream(permitted).forEach(sub -> walk(ResolvableType.forClass(sub), route, seen, found));
        }
    }

    private static boolean isContainer(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || Stream.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type)
                || HttpEntity.class.isAssignableFrom(type);
    }

    /** A number, or a list, map or array whose values are numbers. */
    private static boolean carriesNumbers(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (raw.isArray()) {
            return carriesNumbers(type.getComponentType());
        }
        if (isContainer(raw)) {
            ResolvableType[] generics = type.getGenerics();
            return generics.length > 0 && carriesNumbers(generics[generics.length - 1]);
        }
        return Number.class.isAssignableFrom(raw) || (raw.isPrimitive() && raw != boolean.class && raw != char.class);
    }

    private static boolean namesACountOfPeople(String name) {
        return NAMES_PEOPLE.matcher(name).matches()
                && !NOT_A_COUNT.matcher(name).matches();
    }

    private static Stream<Field> fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic());
    }
}
