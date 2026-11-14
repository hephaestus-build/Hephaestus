package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Only mirror writers borrow a managed provider; metadata readers use IdentityProviderLookup records. */
class IdentityProviderReferenceBoundaryTest extends HephaestusArchitectureTest {
    @Test
    void shouldKeepManagedProviderReferencesLimitedToTheExistingMirrorWriters() {
        String prefix = ArchitectureTestConstants.BASE_PACKAGE + ".integration.scm.";
        Set<String> writers = Set.of(
                prefix + "github.organization.GitHubOrganizationProcessor",
                prefix + "github.label.GitHubLabelProcessor",
                prefix + "github.team.GitHubTeamMessageHandler",
                prefix + "github.sync.GitHubDataSyncService",
                prefix + "gitlab.organization.GitLabGroupProcessor",
                prefix + "gitlab.organization.GitLabGroupSyncService",
                prefix + "gitlab.repository.GitLabProjectSyncService",
                prefix + "gitlab.workspace.GitLabWorkspaceLinkService",
                prefix + "gitlab.team.GitLabSubgroupMessageHandler");
        Set<String> actual = classes.stream()
                .flatMap(type -> type.getAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().isEquivalentTo(SourceProviderNamespaces.class))
                .filter(access -> Set.of("findForMirror", "referenceForMirror")
                        .contains(access.getTarget().getName()))
                .map(access -> access.getOriginOwner().getName())
                .collect(Collectors.toSet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(writers);
    }
}
