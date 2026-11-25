package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBearing;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.testconfig.AuthorityFenceCase;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every setting that can narrow authority ({@link AuthorityBearing}) has one case per boundary of ADR 0134: a narrowing
 * refuses the next context read, model dispatch and send. A setting added without its cases could narrow authority
 * while work goes on under it at a boundary that nobody tested.
 */
class AuthorityBearingRegistryTest extends HephaestusArchitectureTest {

    @Test
    void shouldRegisterACaseAtEveryBoundaryForEachAuthorityBearingSetting() {
        var settings = classes.stream()
                .filter(type -> type.isAnnotatedWith(AuthorityBearing.class))
                .map(JavaClass::reflect)
                .toList();
        var cases = classesWithTests.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(AuthorityFenceCase.class))
                .map(AuthorityBearingRegistryTest::fenceCase)
                .toList();

        assertThat(settings).as("authority-bearing settings").isNotEmpty();
        assertThat(missing(settings, cases)).as("boundaries without a case").isEmpty();
        assertThat(cases)
                .as("cases name an authority-bearing setting")
                .allSatisfy(registered -> assertThat(registered.setting()).isIn(settings));
    }

    @Test
    void shouldFindTheMissingBoundariesOfASettingWithoutItsCases() {
        assertThat(missing(List.of(Unregistered.class), List.of()))
                .containsExactly(
                        "Unregistered at CONTEXT_READ", "Unregistered at MODEL_DISPATCH", "Unregistered at SEND");
    }

    /** Each boundary of each setting without a registered case, as "setting at boundary". */
    private static Set<String> missing(Collection<Class<?>> settings, Collection<Registered> cases) {
        var missing = new TreeSet<String>();
        for (Class<?> setting : settings)
            for (AuthorityBoundary boundary : AuthorityBoundary.values())
                if (cases.stream()
                        .noneMatch(registered -> registered.setting() == setting && registered.boundary() == boundary))
                    missing.add(setting.getSimpleName() + " at " + boundary);
        return missing;
    }

    private static Registered fenceCase(JavaMethod method) {
        var annotation = method.getAnnotationOfType(AuthorityFenceCase.class);
        assertThat(method.isAnnotatedWith(Test.class))
                .as("%s is a test", method.getFullName())
                .isTrue();
        return new Registered(annotation.setting(), annotation.boundary());
    }

    private record Registered(Class<?> setting, AuthorityBoundary boundary) {}

    @AuthorityBearing(generation = AuthorityBearing.Generation.WORKSPACE)
    private static final class Unregistered {}
}
