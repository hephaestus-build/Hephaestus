package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
}
