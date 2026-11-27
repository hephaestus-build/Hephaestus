package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

/**
 * The mentor reaches a provider only through a port in {@code mentor.spi} that the provider's integration implements,
 * such as {@code MentorSourceEvidence}. So the mentor imports no integration type, and a provider's store and client
 * stay its integration's own. The module declaration of {@code mentor} allows no integration either
 * ({@link ModulithVerificationTest}); this rule names the direction on its own.
 */
class MentorIntegrationDirectionArchitectureTest extends HephaestusArchitectureTest {
    @Test
    void shouldKeepTheMentorFreeOfIntegrationTypes() {
        noClasses()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.mentor..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration..")
                .because("a provider reaches the mentor only through a port in mentor.spi")
                .check(classes);
    }
}
