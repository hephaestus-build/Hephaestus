package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * A module reaches another module's persistence only through that module's interface (ADR 0099). This covers entities
 * too, so no JPA association crosses a module (ADR 0138). Main's earlier entity uses are listed in
 * {@code legacy-entity-uses.txt} by class and entity, with the count of their dependency sites, the shared SCM mirror's
 * included. An edge that touches a mentoring module has none.
 */
class RepositoryOwnershipArchTest extends HephaestusArchitectureTest {
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
    void shouldUseNoEntityOfAnotherModule() {
        var modules = ModulithVerificationTest.applicationModules();
        var uses = new EntityUses();
        for (var module : modules) {
            uses.scan(
                    module.getBasePackage().stream().toList(),
                    type -> modules.getModuleByType(type)
                            .map(owner -> owner.getIdentifier().toString()));
        }
        assertThat(uses.mentoring)
                .as("Refer to another module's row by its id and read it through the module's interface")
                .isEmpty();
        LegacyBoundaryBaseline.assertMatches("legacy-entity-uses", uses.legacy());
    }

    @Test
    void shouldCountEachUseOfAForeignEntityAndRefuseOneAcrossAMentoringEdge() {
        var fixtures = List.of(classesWithTests.get(TwoUses.class), classesWithTests.get(OneUse.class));
        var acrossMentoring = new EntityUses();
        acrossMentoring.scan(
                fixtures, type -> Optional.of(type.equals(AccountExport.class.getName()) ? "mentoring" : "practices"));
        assertThat(acrossMentoring.mentoring)
                .containsExactlyInAnyOrder(
                        TwoUses.class.getName() + " -> " + AccountExport.class.getName(),
                        OneUse.class.getName() + " -> " + AccountExport.class.getName());

        var legacy = new EntityUses();
        legacy.scan(fixtures, type -> Optional.of(type.equals(AccountExport.class.getName()) ? "practices" : "agent"));
        assertThat(legacy.mentoring).isEmpty();
        assertThat(legacy.counts.get(TwoUses.class.getName() + " -> " + AccountExport.class.getName()))
                .as("a second use raises the count, so the baseline line no longer matches")
                .isGreaterThan(
                        legacy.counts.getOrDefault(OneUse.class.getName() + " -> " + AccountExport.class.getName(), 0));
    }

    /** The uses of another module's entities, by class and entity, each with the count of its dependency sites. */
    private static final class EntityUses {
        private final Map<String, Integer> counts = new TreeMap<>();
        private final Set<String> mentoring = new TreeSet<>();

        void scan(List<JavaClass> types, Function<String, Optional<String>> moduleOf) {
            for (JavaClass type : types) {
                String source = moduleOf.apply(type.getName()).orElse("");
                // Each dependency is one site: a field, a signature, a call or an access.
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    JavaClass entity = dependency.getTargetClass();
                    if (!entity.isAnnotatedWith(Entity.class)) continue;
                    String target = moduleOf.apply(entity.getName()).orElse(source);
                    if (target.equals(source)) continue;
                    String use = type.getName() + " -> " + entity.getName();
                    if (MentoringModules.touches(source, target)) mentoring.add(use);
                    else counts.merge(use, 1, Integer::sum);
                }
            }
        }

        List<String> legacy() {
            return counts.entrySet().stream()
                    .map(use -> use.getKey() + " x" + use.getValue())
                    .toList();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class TwoUses {
        private @Nullable AccountExport row;

        @Nullable
        AccountExport read(AccountExport other) {
            return row == null ? other : row;
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class OneUse {
        void read(AccountExport row) {}
    }
}
