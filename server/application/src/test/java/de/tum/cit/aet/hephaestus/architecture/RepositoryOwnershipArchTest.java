package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.architecture.RepositoryOwnershipRatchet.Access;
import java.util.HashSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/** Cross-module persistence access must shrink to the two shared-mirror adapters of ADR 0095. */
@Tag("architecture")
class RepositoryOwnershipArchTest {
    @Test
    void shouldAllowOnlyTheExactOwnedRepositoryAccesses() {
        var modules = ModulithVerificationTest.applicationModules();
        var actual = new HashSet<Access>();
        for (var module : modules) {
            String source = module.getIdentifier().toString();
            module.getDirectDependencies(modules).stream().forEach(dependency -> {
                String target = dependency.getTargetModule().getIdentifier().toString();
                boolean sharedMirror = target.equals("integration.scm")
                        && (source.equals("integration.scm.github") || source.equals("integration.scm.gitlab"));
                // A DTO named Repository is not a Spring Data repository.
                if (!source.equals(target)
                        && !sharedMirror
                        && dependency.getTargetType().isAssignableTo(Repository.class)) {
                    actual.add(new Access(
                            source,
                            relative(dependency.getSourceType().getName()),
                            relative(dependency.getTargetType().getName())));
                }
            });
        }
        var declared = RepositoryOwnershipRatchet.ENTRIES.stream()
                .flatMap(entry -> entry.accesses().stream())
                .toList();
        assertThat(declared).doesNotHaveDuplicates();
        assertThat(actual)
                .as("New repository access needs its owner's interface; remove a ratchet witness when it is repaired")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(RepositoryOwnershipRatchet.ENTRIES).allSatisfy(entry -> {
            assertThat(entry.owner()).isNotBlank();
            assertThat(entry.target()).isNotBlank();
            assertThat(entry.consumers()).isNotEmpty();
        });
    }

    @Test
    @Tag("release-ratchet")
    void shouldHaveNoRepositoryOwnershipDebtForAStableRelease() {
        ReleaseRatchet.assertEmptyForRelease(
                "Cross-module repository ownership",
                RepositoryOwnershipRatchet.ENTRIES.stream()
                        .map(RepositoryOwnershipRatchet.Entry::description)
                        .toList());
    }

    private static String relative(String type) {
        return type.substring(BASE_PACKAGE.length() + 1);
    }
}
