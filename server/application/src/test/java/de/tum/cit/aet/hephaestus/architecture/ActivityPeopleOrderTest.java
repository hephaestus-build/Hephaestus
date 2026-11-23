package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.activity.overview.ActivityNameOrder;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityContributorKind;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCoverageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityHighlightsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonRow;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO.PublicActivityCountsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ActivityPeopleOrderTest extends HephaestusArchitectureTest {

    private static final String ACTIVITY = BASE_PACKAGE + ".activity.";
    private static final Pattern RANK_FIELD =
            Pattern.compile(".*(?:rank|ranking|position|place|standing|score)|mostActive", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANK_PARAMETER = Pattern.compile(
            "rank|ranking|position|score|sort|sortBy|sortByScore|order|orderBy|direction|dir|top|limit",
            Pattern.CASE_INSENSITIVE);

    @Test
    void shouldRequireNameOrderWhenAnActivityResponseListsPeopleWithCounts() {
        assertThat(classes.stream()
                        .filter(type ->
                                type.isAnnotatedWith(Controller.class) || type.isMetaAnnotatedWith(Controller.class))
                        .filter(type -> inActivity(type.getPackageName())
                                || type.getDirectDependenciesFromSelf().stream()
                                        .anyMatch(dependency -> inActivity(
                                                dependency.getTargetClass().getPackageName())))
                        .map(JavaClass::reflect)
                        .flatMap(type -> violations(type, inActivity(type.getPackageName())).stream())
                        .toList())
                .as("Activity responses must use typed DTOs and list people with counts in name order")
                .isEmpty();
    }

    @Test
    void shouldRejectAnUnprotectedListInANewResponse() {
        assertThat(violations(RankedActivityFixture.class, true))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("display", "scores", "untyped", "streamed", "top", "withPositions", "ids");
        assertThat(violations(RankedActivityFixture.class, false))
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("display", "top");
    }

    @Test
    void shouldWriteNamesInTheSameOrderWhenTheCountsChange() {
        var json = JsonMapper.builder().build();
        var byCount = List.of(person(1, "Zoe", 1), person(2, "Ali", 2), person(3, "Bex", 3));
        var changed = List.of(person(3, "Bex", 1), person(2, "Ali", 2), person(1, "Zoe", 3));
        for (var rows : List.of(byCount, byCount.reversed(), changed)) {
            var workspace = new ActivityPeopleDTO(
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1),
                    rows,
                    List.of(),
                    new ActivityCoverageDTO(null, 0, 0),
                    new ActivityHighlightsDTO(List.of()),
                    List.of(),
                    List.of());
            JsonNode members = json.valueToTree(workspace);
            JsonNode published = json.valueToTree(PublicActivityDTO.from("Workspace", null, false, workspace, 0));
            assertThat(members.path("people")
                            .valueStream()
                            .map(row -> row.path("person").path("name").asString()))
                    .containsExactly("Ali", "Bex", "Zoe");
            assertThat(published
                            .path("people")
                            .valueStream()
                            .map(row -> row.path("name").asString()))
                    .containsExactly("Ali", "Bex", "Zoe");
        }
    }

    private static ActivityPersonDTO person(long id, String name, long count) {
        return new ActivityPersonDTO(
                new UserInfoDTO(id, name, "", "", name, ""),
                ActivityContributorKind.PERSON,
                new ActivityCountsDTO(count, count, count, count, count, count, count, count),
                null,
                List.of());
    }

    private static boolean inActivity(String packageName) {
        return packageName.startsWith(ACTIVITY) || packageName.equals(BASE_PACKAGE + ".activity");
    }

    private static List<Method> violations(Class<?> controller, boolean activityController) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .filter(method -> activityController
                        || usesActivityType(ResolvableType.forMethodReturnType(method), new HashSet<>()))
                .filter(method -> hasRankParameter(method)
                        || hasUnorderedPeople(ResolvableType.forMethodReturnType(method), false, true, new HashSet<>()))
                .toList();
    }

    private static boolean usesActivityType(ResolvableType type, Set<String> seen) {
        if (!seen.add(type.toString())) {
            return false;
        }
        Class<?> raw = type.toClass();
        return inActivity(raw.getPackageName())
                || (type.isArray() && usesActivityType(type.getComponentType(), seen))
                || Arrays.stream(type.getGenerics()).anyMatch(child -> usesActivityType(child, seen))
                || (raw.isRecord()
                        && Arrays.stream(raw.getRecordComponents())
                                .anyMatch(field ->
                                        usesActivityType(ResolvableType.forType(field.getGenericType(), type), seen)));
    }

    private static boolean hasRankParameter(Method method) {
        return Arrays.stream(method.getParameters()).anyMatch(parameter -> {
            var query = parameter.getAnnotation(RequestParam.class);
            if (query == null) {
                return parameter.isAnnotationPresent(ParameterObject.class)
                        && parameter.getType().isRecord()
                        && Arrays.stream(parameter.getType().getRecordComponents())
                                .anyMatch(field ->
                                        RANK_PARAMETER.matcher(field.getName()).matches());
            }
            String name = !query.name().isEmpty()
                    ? query.name()
                    : (!query.value().isEmpty() ? query.value() : parameter.getName());
            return RANK_PARAMETER.matcher(name).matches();
        });
    }

    private static boolean hasUnorderedPeople(
            ResolvableType type, boolean repeated, boolean body, Set<String> ancestors) {
        Class<?> raw = type.resolve();
        if (raw == null || (body && scalar(raw))) {
            return true;
        }
        if (raw == Void.class || raw == void.class) {
            return false;
        }
        // The same DTO can reappear through its own nested types with a different Spring type owner.
        String visit = type + "/" + repeated + "/" + body;
        if (!ancestors.add(visit)) {
            return false;
        }
        try {
            if (ResponseEntity.class.isAssignableFrom(raw)) {
                return hasUnorderedPeople(type.getGeneric(0), repeated, true, ancestors);
            }
            if (repeated && countedPerson(raw)) {
                return true;
            }
            if (type.isArray() || Iterable.class.isAssignableFrom(raw)) {
                var item = type.isArray()
                        ? type.getComponentType()
                        : type.as(Iterable.class).getGeneric(0);
                return item.toClass() == String.class
                        || numeric(item.toClass())
                        || hasUnorderedPeople(item, true, false, ancestors);
            }
            if (scalar(raw)) {
                return false;
            }
            return !raw.isRecord()
                    || Arrays.stream(raw.getRecordComponents())
                            .anyMatch(field -> unsafeField(field, type, repeated, ancestors));
        } finally {
            ancestors.remove(visit);
        }
    }

    private static boolean unsafeField(
            RecordComponent field, ResolvableType owner, boolean repeated, Set<String> ancestors) {
        var alias = field.getAccessor().getAnnotation(JsonProperty.class);
        if (RANK_FIELD.matcher(field.getName()).matches()
                || (alias != null && RANK_FIELD.matcher(alias.value()).matches())) {
            return true;
        }
        var type = ResolvableType.forType(field.getGenericType(), owner);
        Class<?> item = type.getGeneric(0).toClass();
        if (!repeated
                && field.isAnnotationPresent(ActivityNameOrder.class)
                && type.toClass() == List.class
                && (ActivityPersonRow.class.isAssignableFrom(item) || item == Long.class || item == String.class)) {
            return hasUnorderedPeople(type.getGeneric(0), false, false, ancestors);
        }
        return hasUnorderedPeople(type, repeated, false, ancestors);
    }

    private static boolean scalar(Class<?> type) {
        return type == String.class
                || type == Boolean.class
                || type == boolean.class
                || type.isEnum()
                || TemporalAccessor.class.isAssignableFrom(type)
                || numeric(type);
    }

    private static boolean numeric(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || (type.isPrimitive() && type != boolean.class && type != char.class && type != void.class);
    }

    private static boolean countedPerson(Class<?> type) {
        if (ActivityPersonRow.class.isAssignableFrom(type)) {
            return true;
        }
        if (!type.isRecord()) {
            return false;
        }
        var fields = List.of(type.getRecordComponents());
        boolean hasPerson = fields.stream()
                .anyMatch(field -> field.getType() == UserInfoDTO.class
                        || (field.getType() == String.class
                                && List.of("login", "name").contains(field.getName())));
        return hasPerson
                && fields.stream()
                        .anyMatch(field -> field.getType() == ActivityCountsDTO.class
                                || field.getType() == PublicActivityCountsDTO.class
                                || (numeric(field.getType())
                                        && !field.getName().equals("id")
                                        && !field.getName().endsWith("Id")
                                        && !field.getName().equals("number")));
    }

    @RestController
    @TestComponent
    static class RankedActivityFixture {
        @GetMapping("/fixture")
        ResponseEntity<RankedPeople> display() {
            return ResponseEntity.ok(new RankedPeople(List.of(person(1, "Zoe", 1), person(2, "Ali", 2))));
        }

        @GetMapping("/fixture-inline")
        List<InlineScore> scores() {
            return List.of(new InlineScore("Zoe", 1), new InlineScore("Ali", 2));
        }

        @GetMapping("/fixture-untyped")
        ResponseEntity<Object> untyped() {
            return ResponseEntity.ok(List.of(person(1, "Zoe", 1), person(2, "Ali", 2)));
        }

        @GetMapping("/fixture-text")
        String streamed() {
            return "[\"Zoe\", \"Ali\"]";
        }

        @GetMapping("/fixture-top")
        ResponseEntity<ActivityPeopleDTO> top(@RequestParam("top") int size) {
            throw new UnsupportedOperationException();
        }

        @GetMapping("/fixture-positions")
        PositionList withPositions() {
            return new PositionList(List.of(new Position("Zoe", 1), new Position("Ali", 2)));
        }

        @GetMapping("/fixture-ids")
        List<Long> ids() {
            return List.of(2L, 1L);
        }
    }

    record RankedPeople(List<ActivityPersonDTO> people) {}

    record InlineScore(String login, long weight) {}

    record PositionList(@ActivityNameOrder List<Position> people) {}

    record Position(String login, int contributionRank) implements ActivityPersonRow {
        @Override
        public String activityName() {
            return login;
        }

        @Override
        public String activityLogin() {
            return login;
        }
    }
}
