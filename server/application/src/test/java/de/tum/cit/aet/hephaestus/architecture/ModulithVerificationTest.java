package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;

import de.tum.cit.aet.hephaestus.Application;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Tag("architecture")
class ModulithVerificationTest {

    // Vendor DTO architecture tests guard generated transport models separately from application modules.
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
