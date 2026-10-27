package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

class PracticesIntegrationBoundaryTest extends HephaestusArchitectureTest {
    @Test
    void shouldReachIntegrationItemsOnlyThroughNeutralPorts() {
        noClasses()
                .that()
                .resideInAPackage("..practices..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..integration.core.item..", "..integration.core.itemevent..")
                .check(classes);
    }
}
