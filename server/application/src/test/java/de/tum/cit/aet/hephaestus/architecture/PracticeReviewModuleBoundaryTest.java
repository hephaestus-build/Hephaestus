package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest.classes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class PracticeReviewModuleBoundaryTest {
    @Test
    void shouldKeepDomainAndProviderTypesOutOfCoreProducts() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        BASE_PACKAGE + ".core..",
                        BASE_PACKAGE + ".workspace..",
                        BASE_PACKAGE + ".practices..",
                        BASE_PACKAGE + ".agent..",
                        BASE_PACKAGE + ".evidence..",
                        BASE_PACKAGE + ".mentor..",
                        BASE_PACKAGE + ".integration.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..integration.scm..",
                        "..integration.chat..",
                        "..integration.docs..",
                        "..integration.slack..",
                        "..integration.outline..")
                .because("domain adapters resolve provider identity and evidence through neutral ports")
                .check(classes);
    }

    @Test
    void shouldKeepSharedReviewPortsFreeOfPersistenceAndOrchestration() {
        noClasses()
                .that()
                .resideInAPackage(BASE_PACKAGE + ".practices.spi..")
                .and()
                .haveSimpleNameNotEndingWith("package-info")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..", BASE_PACKAGE + ".agent..")
                .check(classes);
    }

    @Test
    void shouldKeepProductProofIndependentOfRetainedJobs() {
        noClasses()
                .that()
                .haveNameMatching(".*\\.(Result|Feedback)")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(BASE_PACKAGE + ".agent..")
                .because("results and feedback retain their review after runtime jobs expire")
                .check(classes);
    }
}
