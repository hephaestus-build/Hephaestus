package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ADR 0047: the Practice profile has one reader. Each of its reads serves the signed-in developer, or the account an
 * instance admin views through View as user, and never takes a person to read on behalf of. A handler parameter, a
 * bound filter field or a path variable that names a person would let a caller ask for someone else's profile.
 */
class PracticeProfileOneReaderArchitectureTest extends HephaestusArchitectureTest {

    /** The paths of the Practice profile reads: the profile itself, its standings, results and page feedback. */
    private static final List<String> PROFILE_PATHS = List.of(
            "/practice-profile",
            "/practices/standings",
            "/practice-groups/standings",
            "/practices/results",
            "/practices/feedback/practice-page");

    private static final Pattern NAMES_A_PERSON = Pattern.compile(
            "(?i)(user|login|developer|account|person|member|author|subject|recipient)(id|ids|login|logins|name)?");

    @Test
    void noPracticeProfileReadTakesAPersonToReadOnBehalfOf() {
        var controllers = profileControllers().toList();
        assertThat(controllers)
                .extracting(JavaClass::getSimpleName)
                .contains(
                        "PracticeProfileOverviewController",
                        "PracticeProfileReviewController",
                        "PracticeStandingController",
                        "PracticeGroupStandingController",
                        "ResultController",
                        "PracticePageFeedbackController");

        List<String> violations = controllers.stream()
                .flatMap(controller -> Arrays.stream(controller.reflect().getDeclaredMethods()))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .flatMap(PracticeProfileOneReaderArchitectureTest::personInputs)
                .sorted()
                .toList();
        assertThat(violations).isEmpty();
    }

    private static Stream<JavaClass> profileControllers() {
        return classes.stream()
                .filter(c -> paths(c.reflect())
                        .anyMatch(path -> PROFILE_PATHS.stream().anyMatch(path::startsWith)));
    }

    private static Stream<String> personInputs(Method handler) {
        String where = handler.getDeclaringClass().getSimpleName() + "." + handler.getName();
        var fromPaths = paths(handler)
                .flatMap(path -> Pattern.compile("\\{([^}:]+)").matcher(path).results())
                .map(match -> match.group(1))
                .filter(PracticeProfileOneReaderArchitectureTest::namesAPerson)
                .map(variable -> where + " has the path variable {" + variable + "}");
        var fromParameters =
                Arrays.stream(handler.getParameters()).flatMap(parameter -> personInputs(where, parameter));
        return Stream.concat(fromPaths, fromParameters);
    }

    private static Stream<String> personInputs(String where, Parameter parameter) {
        assertThat(parameter.isNamePresent())
                .as("%s is compiled without parameter names, so this rule cannot read them", where)
                .isTrue();
        var names = Stream.of(
                        parameter.getName(),
                        boundName(parameter.getAnnotation(PathVariable.class)),
                        boundName(parameter.getAnnotation(RequestParam.class)))
                .filter(name -> !name.isEmpty());
        var direct = names.filter(PracticeProfileOneReaderArchitectureTest::namesAPerson)
                .map(name -> where + " takes the parameter " + name);
        // A record bound from the query string, such as a filter object, exposes each component as a parameter.
        var bound = parameter.getType().isRecord()
                ? Arrays.stream(parameter.getType().getRecordComponents())
                        .map(RecordComponent::getName)
                        .filter(PracticeProfileOneReaderArchitectureTest::namesAPerson)
                        .map(name -> where + " binds " + parameter.getType().getSimpleName() + "." + name)
                : Stream.<String>empty();
        return Stream.concat(direct, bound);
    }

    private static String boundName(@Nullable PathVariable annotation) {
        return annotation == null ? "" : annotation.name().isEmpty() ? annotation.value() : annotation.name();
    }

    private static String boundName(@Nullable RequestParam annotation) {
        return annotation == null ? "" : annotation.name().isEmpty() ? annotation.value() : annotation.name();
    }

    private static boolean namesAPerson(String name) {
        return NAMES_A_PERSON.matcher(name).matches();
    }

    private static Stream<String> paths(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        return mapping == null ? Stream.empty() : Arrays.stream(mapping.path());
    }
}
