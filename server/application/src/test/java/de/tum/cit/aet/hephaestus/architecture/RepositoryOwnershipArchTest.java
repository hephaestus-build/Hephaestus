package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * A module reaches another module's persistence only through that module's interface (ADR 0099). On an edge that
 * touches a mentoring module, this covers entities too, so no JPA association crosses that edge (ADR 0138).
 */
@Tag("architecture")
class RepositoryOwnershipArchTest {
    @Test
    void shouldReachNoRepositoryOfAnotherModule() {
        var modules = ModulithVerificationTest.applicationModules();
        var violations = new ArrayList<String>();
        for (var module : modules) {
            String source = module.getIdentifier().toString();
            module.getDirectDependencies(modules).stream().forEach(dependency -> {
                String target = dependency.getTargetModule().getIdentifier().toString();
                // The GitHub and GitLab adapters share the SCM mirror (ADR 0095).
                boolean sharedMirror = target.equals("integration.scm")
                        && (source.equals("integration.scm.github") || source.equals("integration.scm.gitlab"));
                // A DTO named Repository is not a Spring Data repository.
                if (!source.equals(target)
                        && !sharedMirror
                        && dependency.getTargetType().isAssignableTo(Repository.class)) {
                    violations.add(dependency.getSourceType().getName() + " -> "
                            + dependency.getTargetType().getName());
                }
            });
        }
        assertThat(violations)
                .as("Reach another module's persistence through its interface, such as a port in its spi package")
                .isEmpty();
    }

    @Test
    void shouldReachNoEntityOfAnotherModuleAcrossAMentoringModule() {
        var modules = ModulithVerificationTest.applicationModules();
        var violations = new ArrayList<String>();
        for (var module : modules) {
            String source = module.getIdentifier().toString();
            module.getDirectDependencies(modules).stream().forEach(dependency -> {
                String target = dependency.getTargetModule().getIdentifier().toString();
                if (!source.equals(target)
                        && MentoringModules.touches(source, target)
                        && dependency.getTargetType().isAnnotatedWith(Entity.class)) {
                    violations.add(dependency.getSourceType().getName() + " -> "
                            + dependency.getTargetType().getName());
                }
            });
        }
        assertThat(violations)
                .as("Refer to another module's row by its id and read it through the module's interface")
                .isEmpty();
    }
}
