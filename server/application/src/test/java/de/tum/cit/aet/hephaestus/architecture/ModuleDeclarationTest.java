package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;

/** ADR 0099 makes every dependency visible in the owning module's declaration. */
@Tag("architecture")
class ModuleDeclarationTest {
    private static final Set<String> OPEN_MODULES = Set.of(
            "integration.core",
            "integration.scm",
            "integration.scm.github",
            "integration.scm.gitlab",
            "integration.slack");

    @Test
    void shouldDeclareEveryModuleAndItsAllowedDependencies() {
        for (var module : ModulithVerificationTest.applicationModules()) {
            var declaration = module.getBasePackage().getAnnotation(ApplicationModule.class);
            assertThat(declaration)
                    .as("Module %s must declare @ApplicationModule", module.getIdentifier())
                    .isPresent();
            assertThat(declaration.orElseThrow().allowedDependencies())
                    .as("Module %s must list its interfaces explicitly; an empty list is valid", module.getIdentifier())
                    .doesNotContain(ApplicationModule.OPEN_TOKEN)
                    .doesNotHaveDuplicates()
                    .allSatisfy(dependency -> assertThat(dependency).doesNotContain("*"));
        }
    }

    @Test
    void shouldExposeOnlyTheSharedLoggingPrimitiveThroughCoreLogging() {
        var core = ModulithVerificationTest.applicationModules()
                .getModuleByName("core")
                .orElseThrow();
        var logging = core.getNamedInterfaces().getByName("logging").orElseThrow();
        assertThat(logging).extracting(JavaClass::getName).containsExactly(LoggingUtils.class.getName());
    }

    @Test
    void shouldKeepOnlyTheIntegrationKernelsAndAdaptersOpenAsAdr0095Decides() {
        var open = ModulithVerificationTest.applicationModules().stream()
                .filter(module -> module.isOpen())
                .map(module -> module.getIdentifier().toString())
                .toList();
        assertThat(open).containsExactlyInAnyOrderElementsOf(OPEN_MODULES);
    }
}
