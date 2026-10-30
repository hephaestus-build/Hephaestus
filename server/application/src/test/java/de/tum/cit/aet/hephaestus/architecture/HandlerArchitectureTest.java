package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Architecture tests for the handler module.
 *
 * <p>Enforces module boundaries:
 * <ul>
 *   <li>SPI package has no Spring or integration.scm dependencies
 *   <li>Handler implementations do not reach into sandbox internals
 * </ul>
 *
 * <p>Analogous to {@link SandboxArchitectureTest} for sandbox SPI isolation.
 */
class HandlerArchitectureTest extends HephaestusArchitectureTest {

    @Nested
    class SpiIsolation {

        @Test
        void spiShouldNotDependOnSpring() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.handler.spi..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("SPI types must be framework-agnostic — no Spring annotations or imports")
                    .check(classes);
        }

        @Test
        void spiShouldNotDependOnHandlerImpl() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.handler.spi..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..agent.handler")
                    .because("SPI must not reference implementation details")
                    .check(classes);
        }
    }

    @Nested
    class HandlerImplIsolation {

        @Test
        void handlersShouldNotDependOnKubernetesInternals() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.handler..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..agent.sandbox.kubernetes..")
                    .because("Handlers prepare context but never interact with Kubernetes directly")
                    .check(classes);
        }

        @Test
        void handlersShouldNotDependOnPracticeAgent() {
            noClasses()
                    .that()
                    .resideInAPackage("..agent.handler..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..agent.practice..")
                    .because("Handlers and the practice agent are independent modules — the executor bridges them")
                    .check(classes);
        }
    }
}
