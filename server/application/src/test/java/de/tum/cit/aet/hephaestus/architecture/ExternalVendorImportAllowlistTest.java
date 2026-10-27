package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Zero external-module classes may import from a vendor adapter package. Reachers
 * go through {@code integration.core.spi} ports or Spring {@code ApplicationEvent}s.
 */
class ExternalVendorImportAllowlistTest extends HephaestusArchitectureTest {

    @Test
    @DisplayName("no class outside integration/ imports a vendor adapter package")
    void noExternalVendorImporter() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("de.tum.cit.aet.hephaestus.integration..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "de.tum.cit.aet.hephaestus.integration.scm.github..",
                        "de.tum.cit.aet.hephaestus.integration.scm.gitlab..",
                        "de.tum.cit.aet.hephaestus.integration.slack..",
                        "de.tum.cit.aet.hephaestus.integration.outline..")
                .because("External modules reach vendor adapters through SPI ports in integration.core.spi "
                        + "or Spring ApplicationEvents — never via direct import.");
        rule.check(classes);
    }
}
