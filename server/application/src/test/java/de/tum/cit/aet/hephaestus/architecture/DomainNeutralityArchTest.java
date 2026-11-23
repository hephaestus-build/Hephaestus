package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import org.junit.jupiter.api.Test;

/**
 * The mentoring modules speak about people, records and threads, never about source control or one provider (ADR
 * 0138). They reach a provider only through the neutral ports of {@code integration.core}, and the adapters translate.
 */
class DomainNeutralityArchTest extends HephaestusArchitectureTest {

    @Test
    void shouldKeepTheMentoringModulesFreeOfProviderAdapters() {
        noClasses()
                .that()
                .resideInAnyPackage(MentoringModules.PACKAGES)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        BASE_PACKAGE + ".integration.scm..",
                        BASE_PACKAGE + ".integration.slack..",
                        BASE_PACKAGE + ".integration.outline..",
                        "org.kohsuke.github..",
                        "org.gitlab4j..",
                        "com.slack.api..")
                .because("an adapter maps its provider onto the neutral ports, so a mentoring module names no provider")
                .allowEmptyShould(true)
                .check(classes);
    }
}
