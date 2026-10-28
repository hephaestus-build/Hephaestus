package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Keycloak is removed (ADR 0017). No production or test code may import {@code org.keycloak.*}
 * again — authentication is Hephaestus-native ({@code core.auth}).
 *
 * <p>NB: {@code com.auth0:java-jwt} is intentionally NOT banned — it backs the worker-control-
 * channel JWT ({@code agent.attempt.*}) and the GitHub App token service, which are
 * unrelated to user authentication.
 */
@Tag("architecture")
class NoKeycloakImportTest extends HephaestusArchitectureTest {

    @Test
    @DisplayName("no class imports org.keycloak.* (Keycloak removed — ADR 0017)")
    void noKeycloakImports() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.keycloak..")
                .because("Keycloak was removed in favour of Hephaestus-native auth (core.auth, ADR 0017)");
        rule.check(classes);
    }
}
