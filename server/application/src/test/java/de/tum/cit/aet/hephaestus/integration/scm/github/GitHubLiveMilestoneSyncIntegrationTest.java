package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubLiveMilestoneSyncIntegrationTest extends AbstractGitHubLiveSyncIntegrationTest {

    @Autowired
    private GitHubRepositorySyncService repositorySyncService;

    @Autowired
    private GitHubMilestoneSyncService milestoneSyncService;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Test
    void syncsMilestonesAndReflectsUpdates() throws Exception {
        var repository = createEphemeralRepository("milestone-sync");
        var milestone =
                createRepositoryMilestone(repository.fullName(), "IT milestone", "Focused milestone sync coverage");

        repositorySyncService
                .syncRepository(workspace.getId(), repository.fullName(), githubProvider, null)
                .orElseThrow();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        milestoneSyncService.syncMilestonesForRepository(workspace.getId(), localRepo.getId());

        var storedMilestone = milestoneRepository.findAll().stream()
                .filter(candidate -> candidate.getRepository().getId().equals(localRepo.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(storedMilestone.getTitle()).isEqualTo(milestone.title());
        assertThat(storedMilestone.getState()).isEqualTo(Milestone.State.OPEN);

        // Close milestone via REST API
        fixtureService.closeMilestone(repository.fullName(), milestone.number());

        milestoneSyncService.syncMilestonesForRepository(workspace.getId(), localRepo.getId());

        var updatedMilestone = milestoneRepository.findAll().stream()
                .filter(candidate -> candidate.getRepository().getId().equals(localRepo.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(updatedMilestone.getState()).isEqualTo(Milestone.State.CLOSED);

        // Delete milestone via REST API
        fixtureService.deleteMilestone(repository.fullName(), milestone.number());
        awaitCondition("milestone removed remotely", () -> {
            var milestones = fixtureService.listMilestones(repository.fullName(), "all");
            return milestones.stream().noneMatch(m -> m.number() == milestone.number());
        });

        milestoneSyncService.syncMilestonesForRepository(workspace.getId(), localRepo.getId());

        assertThat(milestoneRepository.findAllByRepository_Id(localRepo.getId()))
                .isEmpty();
    }
}
