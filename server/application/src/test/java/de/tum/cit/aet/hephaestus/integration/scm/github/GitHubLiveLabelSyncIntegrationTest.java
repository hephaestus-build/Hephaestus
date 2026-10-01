package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubLiveLabelSyncIntegrationTest extends AbstractGitHubLiveSyncIntegrationTest {

    private static final String LABEL_PREFIX = "it-label";
    private static final String INITIAL_COLOR = "00ffaa";
    private static final String UPDATED_COLOR = "ff0077";
    private static final String LABEL_DESCRIPTION = "Focused label sync coverage";

    @Autowired
    private GitHubRepositorySyncService repositorySyncService;

    @Autowired
    private GitHubLabelSyncService labelSyncService;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Test
    void syncsNewLabels() throws Exception {
        var seeded = seedRepositoryWithLabel("label-sync");
        var repository = seeded.repository();
        var createdLabel = seeded.label();

        repositorySyncService
                .syncRepository(workspace.getId(), repository.fullName(), githubProvider, null)
                .orElseThrow();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();
        labelSyncService.syncLabelsForRepository(workspace.getId(), localRepo.getId());

        var storedLabel = labelRepository
                .findByRepositoryIdAndName(localRepo.getId(), createdLabel.name())
                .orElseThrow();
        assertThat(storedLabel.getColor()).isEqualTo(INITIAL_COLOR);
    }

    @Test
    void syncsLabelUpdates() throws Exception {
        var seeded = seedRepositoryWithLabel("label-update-sync");
        var repository = seeded.repository();
        var createdLabel = seeded.label();

        repositorySyncService
                .syncRepository(workspace.getId(), repository.fullName(), githubProvider, null)
                .orElseThrow();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();
        labelSyncService.syncLabelsForRepository(workspace.getId(), localRepo.getId());

        // Update label via GraphQL
        fixtureService.updateLabel(createdLabel.nodeId(), UPDATED_COLOR, "Updated label sync coverage");
        labelSyncService.syncLabelsForRepository(workspace.getId(), localRepo.getId());

        var updatedLabel = labelRepository
                .findByRepositoryIdAndName(localRepo.getId(), createdLabel.name())
                .orElseThrow();
        assertThat(updatedLabel.getColor()).isEqualTo(UPDATED_COLOR);
    }

    @Test
    void removesDeletedLabels() throws Exception {
        var seeded = seedRepositoryWithLabel("label-delete-sync");
        var repository = seeded.repository();
        var createdLabel = seeded.label();

        repositorySyncService
                .syncRepository(workspace.getId(), repository.fullName(), githubProvider, null)
                .orElseThrow();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();
        labelSyncService.syncLabelsForRepository(workspace.getId(), localRepo.getId());

        // Delete label via GraphQL
        fixtureService.deleteLabel(createdLabel.nodeId());
        awaitCondition("label removed remotely", () -> isLabelMissingRemotely(repository, createdLabel));

        labelSyncService.syncLabelsForRepository(workspace.getId(), localRepo.getId());

        assertThat(labelRepository.findByRepositoryIdAndName(localRepo.getId(), createdLabel.name()))
                .isEmpty();
    }

    private RepositoryLabel seedRepositoryWithLabel(String suffix) throws Exception {
        var repository = createEphemeralRepository(suffix);
        var repoInfo = fixtureService.getRepositoryInfo(repository.fullName());
        var label = createRepositoryLabel(repoInfo.nodeId(), LABEL_PREFIX, INITIAL_COLOR, LABEL_DESCRIPTION);
        return new RepositoryLabel(repository, label);
    }

    private boolean isLabelMissingRemotely(
            GitHubTestFixtureService.CreatedRepository repository, GitHubTestFixtureService.CreatedLabel label) {
        var labels = fixtureService.listLabels(repository.fullName());
        return labels.stream().noneMatch(remote -> remote.name().equals(label.name()));
    }

    private record RepositoryLabel(
            GitHubTestFixtureService.CreatedRepository repository, GitHubTestFixtureService.CreatedLabel label) {}
}
