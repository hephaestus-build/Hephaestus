package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubAccountRenameRepositoryIdentityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubLifecycleListener listener;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Test
    void shouldRenameOnlyGitHubRepositoriesWhenAnotherProviderHasTheSameAccountPath() {
        String suffix = UUID.randomUUID().toString();
        String oldLogin = "r-" + suffix;
        String newLogin = "n-" + suffix;
        String outsidePath = "s-" + suffix + "/sibling";
        String oldPath = oldLogin + "/target";
        String newPath = newLogin + "/target";
        long nativeId = 100_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) % 900_000_000L;
        long installationId = nativeId + 1;
        // Another test can clean the database, so the provider row is created here when it is missing.
        var github = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var gitlab = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + suffix + ".gitlab.example.test"));
        var workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installationId, oldLogin)
                        .withAccountType(AccountType.USER),
                installationId);
        var monitor = monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, oldPath, nativeId));
        var outsideMonitor =
                monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, outsidePath, nativeId + 1));
        var correct = repository(github, nativeId, oldPath);
        var foreign = repository(gitlab, nativeId, oldPath);
        var outside = repository(github, nativeId + 1, outsidePath);
        assertThat(correct.getOrganization()).isNull();
        long correctId = Objects.requireNonNull(correct.getId());
        long foreignId = Objects.requireNonNull(foreign.getId());
        long outsideId = Objects.requireNonNull(outside.getId());
        var foreignBefore = state(foreignId);
        var outsideBefore = state(outsideId);

        listener.handleAccountRename(installationId, oldLogin, newLogin);

        var correctAfter = state(correctId);
        var foreignAfter = state(foreignId);
        var outsideAfter = state(outsideId);
        var workspaceAfter = workspaces.findById(workspace.getId()).orElseThrow();
        var monitorAfter = monitors.findById(monitor.getId()).orElseThrow();
        var outsideMonitorAfter = monitors.findById(outsideMonitor.getId()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(correctAfter)
                    .isEqualTo(new RepositoryState(
                            correctId,
                            nativeId,
                            Objects.requireNonNull(github.getId()),
                            newPath,
                            "https://github.com/" + newPath));
            softly.assertThat(foreignAfter).as("Foreign provider repository").isEqualTo(foreignBefore);
            softly.assertThat(outsideAfter)
                    .as("Outside-prefix GitHub repository")
                    .isEqualTo(outsideBefore);
            softly.assertThat(workspaceAfter.getAccountLogin()).isEqualTo(newLogin);
            softly.assertThat(workspaceAfter.getAccountType()).isEqualTo(AccountType.USER);
            softly.assertThat(monitorAfter.getNameWithOwner()).isEqualTo(newPath);
            softly.assertThat(outsideMonitorAfter.getNameWithOwner()).isEqualTo(outsidePath);
        });
    }

    private Repository repository(IdentityProvider provider, long nativeId, String path) {
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(nativeId);
        repository.setName(path.substring(path.indexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setPushedAt(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(repository.getId()).isNull();
        var saved = repositories.saveAndFlush(repository);
        assertThat(saved.getId()).isPositive();
        return saved;
    }

    private RepositoryState state(long id) {
        var repository = repositories.findById(id).orElseThrow();
        return new RepositoryState(
                Objects.requireNonNull(repository.getId()),
                Objects.requireNonNull(repository.getNativeId()),
                Objects.requireNonNull(repository.getProvider().getId()),
                repository.getNameWithOwner(),
                repository.getHtmlUrl());
    }

    private record RepositoryState(long id, long nativeId, long providerId, String path, String url) {}
}
