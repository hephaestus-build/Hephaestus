package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkspaceRepositoryCoverageIntegrationTest extends BaseIntegrationTest {

    private static final long INSTALLATION_ID = 9912345L;

    @Autowired
    private WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private StubInstallationRepositoryEnumerator repositoryEnumerator;

    @Autowired
    private GitHubAppTokenService gitHubAppTokenService;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        repositoryEnumerator.reset();
        // Ensure GitHub IdentityProvider exists - required by
        // WorkspaceRepositoryMonitorService.ensureRepositoryFromSnapshot
        if (gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .isEmpty()) {
            gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com"));
        }
        // Clear any suspended installation state from previous tests
        // This prevents test pollution when async syncs mark installations as suspended
        gitHubAppTokenService.markInstallationActive(INSTALLATION_ID);
    }

    @Test
    void shouldReconcileMonitorsForInstallation() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        repositoryToMonitorRepository.save(buildMonitor(workspace, "HephaestusTest/Orphaned"));

        repositoryEnumerator.returns(List.of(
                snapshot(1L, "HephaestusTest/demo-repository", "demo-repository", true),
                snapshot(2L, "HephaestusTest/payload-fixture-repo-renamed", "payload-fixture-repo-renamed", false)));

        // Use deferSync=true to prevent async syncs from running during test
        // Async syncs can mark installations as suspended on 403 errors, affecting subsequent calls
        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
        assertThat(monitors)
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactlyInAnyOrder(
                        "HephaestusTest/demo-repository", "HephaestusTest/payload-fixture-repo-renamed");
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/demo-repository"))
                .isPresent();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/payload-fixture-repo-renamed"))
                .isPresent();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/Orphaned"))
                .isEmpty();
    }

    @Test
    void shouldRespectSelectedInstallations() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);

        repositoryEnumerator.returns(List.of(snapshot(3L, "HephaestusTest/HelloWorld", "HelloWorld", true)));

        // Use deferSync=true to prevent async syncs from running during test
        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
        assertThat(monitors)
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactly("HephaestusTest/HelloWorld");
    }

    @Test
    void replayedRepositoryAddCannotRepopulateAPurgedWorkspace() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        workspaceRepositoryMonitorService.ensureRepositoryAndMonitorFromSnapshot(
                INSTALLATION_ID,
                new ProvisioningListener.RepositorySnapshot(4L, "HephaestusTest/replayed", "replayed", true));

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/replayed"))
                .isEmpty();
    }

    @Test
    void replayedRepositoryRemovalCannotMutateAPurgedWorkspace() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);
        repositoryToMonitorRepository.saveAndFlush(buildMonitor(workspace, "HephaestusTest/retained"));
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        workspaceRepositoryMonitorService.removeRepositoryMonitorForInstallation(
                INSTALLATION_ID, "HephaestusTest/retained");

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactly("HephaestusTest/retained");
    }

    private Workspace persistWorkspace(RepositorySelection selection) {
        var builder = WorkspaceTestFixtures.installationWorkspace(INSTALLATION_ID, "HephaestusTest")
                .withRepositorySelection(selection)
                .withSlug(
                        "ws-install-" + INSTALLATION_ID + "-" + selection.name().toLowerCase(Locale.ENGLISH));
        // the provider classification used by
        // WorkspaceRepositoryMonitorService.isGitHubAppWorkspace pulls from the
        // Connection registry, so the fixture must persist the GitHub App connection
        // alongside the Workspace.
        return WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository, connectionRepository, builder, INSTALLATION_ID);
    }

    private RepositoryToMonitor buildMonitor(Workspace workspace, String nameWithOwner) {
        return WorkspaceTestFixtures.repositoryMonitor(workspace, nameWithOwner);
    }

    private InstallationRepositoryEnumerator.InstallationRepository snapshot(
            long id, String nameWithOwner, String name, boolean isPrivate) {
        return new InstallationRepositoryEnumerator.InstallationRepository(id, nameWithOwner, name, isPrivate);
    }
}
