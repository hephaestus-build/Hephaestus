package de.tum.cit.aet.hephaestus.agent.sandbox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Architecture tests enforcing sandbox module boundaries.
 *
 * <ul>
 *   <li>{@code spi/} has zero external dependencies (no Spring, no docker-java)
 *   <li>{@code docker/} is not imported outside {@code agent.sandbox}
 *   <li>{@code DockerClient} stays inside {@code docker/}
 * </ul>
 */
class SandboxArchitectureTest extends HephaestusArchitectureTest {

    private static final String SANDBOX_SPI = "..agent.sandbox.spi..";
    private static final String SANDBOX_DOCKER = "..agent.sandbox.docker..";

    @Nested
    class SpiBoundary {

        @Test
        void spiHasNoSpringDeps() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(SANDBOX_SPI)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("SPI types must be framework-agnostic for portability");
            rule.check(classes);
        }

        @Test
        void spiHasNoDockerDeps() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(SANDBOX_SPI)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.github.dockerjava..")
                    .because("SPI types must not leak implementation details");
            rule.check(classes);
        }
    }

    @Nested
    class DockerBoundary {

        @Test
        void neutralSandboxConfigurationDoesNotDependOnDocker() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.sandbox")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SANDBOX_DOCKER)
                    .because("backend-neutral sandbox settings must not own Docker adapter configuration")
                    .check(classes);
        }

        @Test
        void dockerNotImportedOutsideSandbox() {
            ArchRule rule = noClasses()
                    .that()
                    .resideOutsideOfPackage("..agent.sandbox..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SANDBOX_DOCKER)
                    .because("Docker implementation is an internal detail of the sandbox module");
            rule.check(classes);
        }

        @Test
        void dockerClientEncapsulated() {
            ArchRule rule = noClasses()
                    .that()
                    .resideOutsideOfPackage(SANDBOX_DOCKER)
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("com.github.dockerjava.api.DockerClient")
                    .because("DockerClient must be encapsulated within the docker implementation package");
            rule.check(classes);
        }
    }
}
