package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import de.tum.cit.aet.hephaestus.Application;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

@Tag("architecture")
class ModulithVerificationTest {

    // Vendor DTO architecture tests guard generated transport models separately from application modules. They stay
    // out of the import itself: Modulith ignores classes through a view of its import, which would keep every
    // generated class in the one architecture JVM.
    private static final List<String> VENDOR_MODELS = List.of(
            "/integration/scm/github/graphql/model/",
            "/integration/scm/gitlab/graphql/model/",
            "/integration/outline/client/model/");

    private static final ApplicationModules MODULES = ApplicationModules.of(
            Application.class,
            location -> ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
                    && VENDOR_MODELS.stream().noneMatch(location::contains));

    @Test
    void modulesAreCycleFreeAndRespectNamedInterfaces() {
        MODULES.verify();
    }

    /**
     * Verification refuses a code dependency that a module does not declare. This rule refuses the declaration itself
     * when it points up the layers of the mentoring modules.
     */
    @Test
    void shouldDeclareNoDependencyUpTheMentoringLayers() {
        assertThat(layerViolations(declaredDependencies())).isEmpty();
    }

    @Test
    void shouldRefuseADependencyOfPracticesOnMentoring() {
        assertThat(layerViolations(Map.of("practices", List.of("core", "mentoring::spi"))))
                .containsExactly("practices -> mentoring::spi");
        assertThat(layerViolations(Map.of("mentoring", List.of("relationship", "practices"))))
                .isEmpty();
    }

    static ApplicationModules applicationModules() {
        return MODULES;
    }

    /** Each module's declared dependencies, as {@code module} or {@code module::interface}. */
    static Map<String, List<String>> declaredDependencies() {
        Map<String, List<String>> declared = new TreeMap<>();
        for (var module : MODULES) {
            declared.put(
                    module.getIdentifier().toString(),
                    module.getBasePackage()
                            .getAnnotation(ApplicationModule.class)
                            .map(declaration -> List.of(declaration.allowedDependencies()))
                            .orElse(List.of()));
        }
        return declared;
    }

    /** The module that a declared dependency names: {@code mentoring::spi} names {@code mentoring}. */
    static String moduleOf(String dependency) {
        int named = dependency.indexOf("::");
        return named < 0 ? dependency : dependency.substring(0, named);
    }

    private static List<String> layerViolations(Map<String, List<String>> declared) {
        var violations = new ArrayList<String>();
        declared.forEach((module, dependencies) -> dependencies.stream()
                .filter(dependency -> !MentoringModules.mayDependOn(module, moduleOf(dependency)))
                .forEach(dependency -> violations.add(module + " -> " + dependency)));
        return violations;
    }
}
