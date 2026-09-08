package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;

import de.tum.cit.aet.hephaestus.Application;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the Spring Modulith application-module structure.
 *
 * <p>Runs in the {@code architectureTest} Gradle task on every PR. Fails the build if:
 * <ul>
 *   <li>Two modules form a source-level dependency cycle</li>
 *   <li>A module reaches into another module's internal (non-API) packages without a
 *       {@link org.springframework.modulith.NamedInterface} grant</li>
 *   <li>A module declares {@code allowedDependencies} that don't match its actual imports</li>
 * </ul>
 *
 * <p>When this test fails in CI, read the error carefully — Modulith reports the exact
 * source/target packages and suggests the named-interface or {@code allowedDependencies}
 * fix. Resist the urge to "break the cycle" via events; prefer named interfaces or moving
 * code.
 *
 * <p>Generated GraphQL/OpenAPI wire models are excluded because they contain no application module
 * behavior or dependencies. Their isolation from production boundaries is enforced separately by
 * the vendor DTO architecture tests.
 *
 * @see org.springframework.modulith.core.ApplicationModules#verify()
 * @see <a href="https://docs.spring.io/spring-modulith/reference/verification.html">Modulith Verification</a>
 */
@Tag("architecture")
class ModulithVerificationTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(
            Application.class,
            resideInAnyPackage(
                    "..integration.scm.github.graphql.model..",
                    "..integration.scm.gitlab.graphql.model..",
                    "..integration.outline.client.model.."));

    @Test
    void modulesAreCycleFreeAndRespectNamedInterfaces() {
        MODULES.verify();
    }

    static ApplicationModules applicationModules() {
        return MODULES;
    }
}
