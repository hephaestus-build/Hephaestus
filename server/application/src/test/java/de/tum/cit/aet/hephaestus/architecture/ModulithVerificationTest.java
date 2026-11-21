package de.tum.cit.aet.hephaestus.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import de.tum.cit.aet.hephaestus.Application;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Tag("architecture")
class ModulithVerificationTest {

    // Vendor DTO architecture tests guard generated transport models separately from application modules. They stay
    // out of the import itself: Modulith ignores classes through a view of its import, which would keep every
    // generated class in the one architecture JVM.
    private static final List<String> VENDOR_MODELS = List.of(
            "/integration/scm/github/graphql/model/",
            "/integration/scm/gitlab/graphql/model/",
            "/integration/outline/client/model/");

    private static final ApplicationModules MODULES = ApplicationModules.of(
            Application.class,
            location -> ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
                    && VENDOR_MODELS.stream().noneMatch(location::contains));

    @Test
    void modulesAreCycleFreeAndRespectNamedInterfaces() {
        MODULES.verify();
    }

    static ApplicationModules applicationModules() {
        return MODULES;
    }
}
