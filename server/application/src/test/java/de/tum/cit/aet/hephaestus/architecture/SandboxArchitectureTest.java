package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Architecture tests for the sandbox module boundaries. */
class SandboxArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void applicationMustNotUseADockerSandboxClient() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.github.dockerjava..")
                .because("sandbox execution uses Kubernetes; Docker is only test/development infrastructure")
                .check(classes);
    }

    @Nested
    class SpiIsolation {

        @Test
        void spiShouldNotDependOnSpring() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.sandbox.spi..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("SPI types must be framework-agnostic — no Spring annotations or imports")
                    .check(classes);
        }
    }

    @Nested
    class KubernetesIsolation {

        @Test
        void kubernetesClientOnlyInKubernetesPackage() {
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..agent.sandbox.kubernetes..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("io.fabric8..")
                    .because("Kubernetes API access must be confined to the kubernetes subpackage")
                    .check(classes);
        }

        @Test
        void kubernetesImplNotImportedOutside() {
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..agent.sandbox..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..agent.sandbox.kubernetes..")
                    .because("Only the SPI should be used outside the sandbox package")
                    .check(classes);
        }
    }

    /**
     * Rules pass trivially when the matched package is empty; the positive fixture test below proves the rule
     * actually catches a violation.
     */
    @Nested
    class HostIsolation {

        @Test
        void mentorMustNotInvokeSandboxManagerExecute() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.mentor..")
                    .should()
                    .callMethod(SandboxManager.class, "execute", SandboxSpec.class)
                    .because(
                            "conversation turns run in the worker's long-lived Hephaestus host; SandboxManager.execute is the "
                                    + "one-shot path of a practice review.")
                    .check(classes);
        }

        /** Guards against TNG/ArchUnit#324: callMethod silently passes when the parameter signature mismatches. */
        @Test
        void mentorPositiveFixtureCatchesViolation() {
            JavaClasses fixtureClasses = importTestFixture("de.tum.cit.aet.hephaestus.agent.mentor");

            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..agent.mentor..")
                    .should()
                    .callMethod(SandboxManager.class, "execute", SandboxSpec.class);

            assertThatThrownBy(() -> rule.check(fixtureClasses))
                    .as("Rule must reject the deliberate violation fixture")
                    .isInstanceOf(AssertionError.class);
        }

        private JavaClasses importTestFixture(String packageName) {
            JavaClasses imported = new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                    .importPackages(packageName, "de.tum.cit.aet.hephaestus.agent.sandbox.spi");
            assertThat(imported)
                    .as("Architecture fixture under %s must be present on the test classpath", packageName)
                    .isNotEmpty();
            return imported;
        }
    }
}
