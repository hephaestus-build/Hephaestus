package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PracticeReviewModuleBoundaryTest extends HephaestusArchitectureTest {

    @Test
    void shouldKeepScmPersistenceOutOfObservationAdmission() {
        noClasses()
                .that()
                .haveSimpleName("PracticeDetectionDeliveryService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..integration.scm.domain..", "..integration.scm.github..", "..integration.scm.gitlab..")
                .because("observation admission reads live SCM identity through ReviewTargetQuery")
                .check(classes);
    }

    @Test
    void shouldKeepReviewTargetContractFreeOfPersistenceAndOrchestration() {
        noClasses()
                .that()
                .haveNameMatching(".*\\.ReviewTargetQuery(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.persistence..", "org.springframework..", "..integration.scm.domain..", "..agent..")
                .check(classes);
    }

    @Test
    void shouldRecordAgentDependenciesUsingModulith() {
        var modules = ModulithVerificationTest.applicationModules();
        var agent = modules.getModuleByName("agent").orElseThrow();
        assertThat(agent.getDirectDependencies(modules)
                        .uniqueModules()
                        .map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "config",
                        "core",
                        "evidence",
                        "integration.core",
                        "integration.scm",
                        "mentor",
                        "practices",
                        "workspace");
    }
}
