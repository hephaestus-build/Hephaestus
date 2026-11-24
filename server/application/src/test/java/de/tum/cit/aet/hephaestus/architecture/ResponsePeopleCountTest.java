package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.PeopleCount;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.ShownCount;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * No route shows a count of fewer than five people. Generated from the routes: the response type of every handler is
 * walked, and every number in it must say what it is, by its type.
 *
 * <ul>
 *   <li>A count of people that the floor decides is a {@link ShownCount}, which only {@code SmallCounts} builds, so it
 *       can never hold 1 to 4.
 *   <li>A count of people that may be exact, or a figure read from people such as a range, carries {@link PeopleCount}.
 *       An exact count says why; a {@code PeopleCount} on a plain number without a reason fails.
 *   <li>Every other number, and every shape whose content the walk cannot see ({@code Object}, a JSON tree, a map of
 *       objects), is listed in {@value #INVENTORY}, so a person decided what it carries.
 * </ul>
 *
 * <p>A new number fails whatever its name, until someone classifies it.
 */
class ResponsePeopleCountTest extends HephaestusArchitectureTest {

    private static final String INVENTORY = "/architecture/numbers-without-people.txt";

    @Test
    void shouldClassifyEveryNumberThatARouteReturns() throws IOException {
        Set<String> inventory = inventory();
        Map<String, String> unclassified = new TreeMap<>();
        Set<String> listedAndFound = new TreeSet<>();
        controllers()
                .forEach(controller -> findings(controller).forEach((finding, route) -> {
                    if (inventory.contains(finding)) {
                        listedAndFound.add(finding);
                    } else {
                        unclassified.put(finding, route);
                    }
                }));

        assertThat(unclassified)
                .as("numbers and opaque shapes that a route returns, not classified by type or in %s", INVENTORY)
                .isEmpty();
        assertThat(inventory)
                .as("entries in %s that no route returns any more", INVENTORY)
                .containsExactlyInAnyOrderElementsOf(listedAndFound);
    }

    @Test
    void shouldLetEveryShownCountBeWithheld() {
        Map<String, Boolean> nullable = new TreeMap<>();
        classes.stream()
                .map(JavaClass::reflect)
                .forEach(type -> fields(type)
                        .filter(field -> field.getType() == ShownCount.class)
                        .forEach(field -> nullable.put(
                                type.getSimpleName() + "." + field.getName(),
                                field.getAnnotatedType().isAnnotationPresent(Nullable.class))));

        assertThat(nullable)
                .as("response fields that the floor decides")
                .isNotEmpty()
                .allSatisfy((count, canBeWithheld) ->
                        assertThat(canBeWithheld).as(count).isTrue());
    }

    /** Admin-only routes that each try to send a small count in a way the guard must catch. */
    @RestController
    @RequestMapping("/fixture")
    @RequireInstanceAdmin
    static class SmallCountFixtures {
        /** A count of people under a name that says nothing about people. */
        record ConsentSummaryDTO(int optedOut) {}

        /** Marked as a people count, but a plain number can still carry 2. */
        record MarkedSummaryDTO(@PeopleCount @Nullable Integer optedOut) {}

        /** Named like an identifier, which earns no exemption. */
        record OwnerSummaryDTO(long ownersId) {}

        /** A shape whose numbers the walk cannot see. */
        record OpaqueSummaryDTO(Map<String, Object> summary) {}

        /** A property that Jackson writes from a method, with no field behind it. */
        record ComputedSummaryDTO(String name) {
            @JsonProperty("optedOut")
            public int optedOut() {
                return 2;
            }
        }

        /** A people count on a shape whose numbers the walk cannot see. */
        record MarkedOpaqueSummaryDTO(@PeopleCount Map<String, Object> summary) {}

        /** A property that Jackson writes from a private method. */
        record PrivateSummaryDTO(String name) {
            @JsonProperty("optedOut")
            @SuppressWarnings("UnusedMethod") // Jackson calls it.
            private int optedOut() {
                return 2;
            }
        }

        @GetMapping("/consent")
        ConsentSummaryDTO consent() {
            return new ConsentSummaryDTO(2);
        }

        @GetMapping("/marked")
        MarkedSummaryDTO marked() {
            return new MarkedSummaryDTO(2);
        }

        @GetMapping("/owner")
        OwnerSummaryDTO owner() {
            return new OwnerSummaryDTO(2);
        }

        @GetMapping("/opaque")
        OpaqueSummaryDTO opaque() {
            return new OpaqueSummaryDTO(Map.of("optedOut", 2));
        }

        @GetMapping("/computed")
        ComputedSummaryDTO computed() {
            return new ComputedSummaryDTO("Slack");
        }

        @GetMapping("/marked-opaque")
        MarkedOpaqueSummaryDTO markedOpaque() {
            return new MarkedOpaqueSummaryDTO(Map.of("optedOut", 2));
        }

        @GetMapping("/private")
        PrivateSummaryDTO privateProperty() {
            return new PrivateSummaryDTO("Slack");
        }

        @GetMapping("/shadowed")
        ShadowedSummaryDTO shadowed() {
            return new ShadowedSummaryDTO();
        }
    }

    /** A field and a marked method that share a name: Jackson writes the method's numbers, not the field's names. */
    static final class ShadowedSummaryDTO {
        @SuppressWarnings("UnusedVariable") // The walk reads it; Jackson does not write a private field.
        private final List<String> optedOut = List.of("Ada");

        @JsonProperty("optedOut")
        public List<Integer> counts() {
            return List.of(2);
        }
    }

    /** A handler that a controller inherits. */
    static class InheritedRoutes {
        record InheritedSummaryDTO(int optedOut) {}

        @GetMapping("/inherited")
        InheritedSummaryDTO inherited() {
            return new InheritedSummaryDTO(2);
        }
    }

    @RestController
    @RequestMapping("/fixture")
    @RequireInstanceAdmin
    static class InheritingFixture extends InheritedRoutes {}

    @Test
    void shouldFailOnEveryWayToSendASmallCount() throws IOException {
        Set<String> inventory = inventory();

        assertThat(findings(SmallCountFixtures.class).keySet())
                .filteredOn(finding -> !inventory.contains(finding))
                .containsExactlyInAnyOrder(
                        "ConsentSummaryDTO.optedOut",
                        "MarkedSummaryDTO.optedOut needs ShownCount",
                        "OwnerSummaryDTO.ownersId",
                        "OpaqueSummaryDTO.summary",
                        "ComputedSummaryDTO.optedOut",
                        "MarkedOpaqueSummaryDTO.summary",
                        "PrivateSummaryDTO.optedOut",
                        "ShadowedSummaryDTO.optedOut");
        // The private method is a real property on the wire, not a guess of the walk.
        assertThat(new JsonMapper().writeValueAsString(new SmallCountFixtures.PrivateSummaryDTO("Slack")))
                .contains("\"optedOut\":2");
        assertThat(new JsonMapper().writeValueAsString(new ShadowedSummaryDTO()))
                .isEqualTo("{\"optedOut\":[2]}");
        assertThat(findings(InheritingFixture.class).keySet()).containsExactly("InheritedSummaryDTO.optedOut");
    }

    private static Stream<Class<?>> controllers() {
        return classes.stream()
                .map(JavaClass::reflect)
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, RestController.class));
    }

    /** Each unclassified number or opaque shape in one controller's responses, with the first route that returns it. */
    private static Map<String, String> findings(Class<?> controller) {
        Map<String, String> found = new TreeMap<>();
        Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(controller))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .forEach(handler -> {
                    String route = controller.getSimpleName() + "." + handler.getName();
                    ResolvableType returned = ResolvableType.forMethodReturnType(handler);
                    if (carriesNumbers(returned) || isOpaque(returned)) {
                        found.putIfAbsent(route + "()", route);
                    }
                    walk(returned, route, new HashSet<>(), found);
                });
        return found;
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
        if (!raw.getName().startsWith(BASE_PACKAGE) || raw.isEnum() || raw == ShownCount.class) {
            return;
        }
        for (Class<?> declaring = raw;
                declaring != null && declaring.getName().startsWith(BASE_PACKAGE);
                declaring = declaring.getSuperclass()) {
            fields(declaring).forEach(field -> {
                String name = raw.getSimpleName() + "." + field.getName();
                ResolvableType fieldType = ResolvableType.forField(field, type);
                PeopleCount marked = field.getAnnotation(PeopleCount.class);
                if (fieldType.resolve() == ShownCount.class) {
                    return;
                }
                if (marked != null && carriesNumbers(fieldType)) {
                    if (marked.exactBecause().isEmpty()) {
                        found.putIfAbsent(name + " needs ShownCount", route);
                    }
                    return;
                }
                // A people count on an object classifies none of its numbers: the walk goes on into it.
                if ((marked == null && carriesNumbers(fieldType)) || isOpaque(fieldType)) {
                    found.putIfAbsent(name, route);
                }
                walk(fieldType, route, seen, found);
            });
            computedProperties(declaring).forEach(method -> {
                ResolvableType returned = ResolvableType.forMethodReturnType(method, raw);
                if (carriesNumbers(returned) || isOpaque(returned)) {
                    found.putIfAbsent(raw.getSimpleName() + "." + propertyName(method), route);
                }
                walk(returned, route, seen, found);
            });
        }
        Class<?>[] permitted = raw.getPermittedSubclasses();
        if (permitted != null) {
            Arrays.stream(permitted).forEach(sub -> walk(ResolvableType.forClass(sub), route, seen, found));
        }
    }

    /**
     * The methods Jackson writes as properties that no field of the type backs: one marked as a property, or, outside a
     * record, a getter.
     */
    private static Stream<Method> computedProperties(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0 && method.getReturnType() != void.class)
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(method -> !method.isAnnotationPresent(JsonIgnore.class))
                // Jackson writes a marked method whatever its visibility, and a getter only when it is public.
                .filter(method -> isMarkedProperty(method)
                        || (Modifier.isPublic(method.getModifiers())
                                && !type.isRecord()
                                && GETTER.matcher(method.getName()).matches()))
                .filter(method -> !isComponentAccessor(type, method));
    }

    private static boolean isMarkedProperty(Method method) {
        return method.isAnnotationPresent(JsonProperty.class) || method.isAnnotationPresent(JsonGetter.class);
    }

    /**
     * A record's component accessor writes its component, which the field walk already classified. Any other method
     * is classified on its own, even when a field shares its name: the two can hold different values.
     */
    private static boolean isComponentAccessor(Class<?> type, Method method) {
        return type.isRecord()
                && Arrays.stream(type.getRecordComponents())
                        .anyMatch(component -> component.getAccessor().equals(method));
    }

    private static final Pattern GETTER = Pattern.compile("(get|is)[A-Z].*");

    private static String propertyName(Method method) {
        JsonProperty property = method.getAnnotation(JsonProperty.class);
        if (property != null && !property.value().isEmpty()) {
            return property.value();
        }
        String name = method.getName();
        if (method.isAnnotationPresent(JsonProperty.class) || method.isAnnotationPresent(JsonGetter.class)) {
            return name;
        }
        String bare = name.startsWith("is") ? name.substring(2) : name.substring(3);
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    private static boolean isContainer(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || Stream.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type)
                || HttpEntity.class.isAssignableFrom(type);
    }

    /** A number, or a list, map or array whose values are numbers. Bytes are file content, not numbers. */
    private static boolean carriesNumbers(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (raw == byte[].class) {
            return false;
        }
        if (raw.isArray()) {
            return carriesNumbers(type.getComponentType());
        }
        if (isContainer(raw)) {
            ResolvableType[] generics = type.getGenerics();
            return generics.length > 0 && carriesNumbers(generics[generics.length - 1]);
        }
        return Number.class.isAssignableFrom(raw)
                || (raw.isPrimitive() && raw != boolean.class && raw != char.class && raw != void.class);
    }

    /** A shape that can hold numbers the walk cannot see: an object, a JSON tree, or a container of either. */
    private static boolean isOpaque(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class || isJsonTree(raw)) {
            return true;
        }
        if (raw.isArray()) {
            return isOpaque(type.getComponentType());
        }
        if (isContainer(raw)) {
            ResolvableType[] generics = type.getGenerics();
            return generics.length == 0 || isOpaque(generics[generics.length - 1]);
        }
        return false;
    }

    private static boolean isJsonTree(Class<?> type) {
        return type.getName().endsWith(".JsonNode")
                || Stream.<Class<?>>iterate(type, Objects::nonNull, Class::getSuperclass)
                        .anyMatch(ancestor -> ancestor.getName().endsWith(".JsonNode"));
    }

    private static Stream<Field> fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic());
    }

    /** The inventory's entries: one {@code Type.field} per line, after which {@code #} starts a comment. */
    private static Set<String> inventory() throws IOException {
        try (InputStream in = Objects.requireNonNull(ResponsePeopleCountTest.class.getResourceAsStream(INVENTORY))) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
            return lines.stream()
                    .map(line -> line.contains("#") ? line.substring(0, line.indexOf('#')) : line)
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
