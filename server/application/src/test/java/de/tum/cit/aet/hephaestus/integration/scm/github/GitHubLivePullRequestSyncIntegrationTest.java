package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Live integration tests for GitHub pull request synchronization.
 * <p>
 * These tests hit the real GitHub API and require valid credentials.
 * They are tagged with "live" and will be skipped if credentials are not configured.
 */
class GitHubLivePullRequestSyncIntegrationTest extends AbstractGitHubLiveSyncIntegrationTest {

    @Autowired
    private GitHubRepositorySyncService repositorySyncService;

    @Autowired
    private GitHubPullRequestSyncService pullRequestSyncService;

    @Autowired
    private GitHubPullRequestReviewSyncService reviewSyncService;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository pullRequestReviewRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Test
    void syncsNewPullRequests() throws Exception {
        // 1. Create ephemeral repository with a feature branch and PR
        var repository = createEphemeralRepository("pr-sync");
        var repoInfo = fixtureService.getRepositoryInfo(repository.fullName());

        // 2. Create a feature branch with a commit
        String branchName = "feature-" + nextEphemeralSlug("branch");
        fixtureService.createBranch(repoInfo.nodeId(), branchName, headCommitSha(repoInfo));

        // Create a file on the feature branch
        String filePath = "test-" + nextEphemeralSlug("file") + ".txt";
        String fileContent = "Test content generated at " + Instant.now();
        fixtureService.createCommitOnBranch(repository.fullName(), branchName, "Add test file", filePath, fileContent);

        // 3. Create pull request
        String prTitle = "IT pull request " + nextEphemeralSlug("pr");
        String prBody = "Live integration PR created at " + Instant.now();
        var createdPR = fixtureService.createPullRequest(
                repoInfo.nodeId(), prTitle, prBody, branchName, defaultBranch(repoInfo));

        // 4. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 5. Sync pull requests
        SyncResult syncResult = pullRequestSyncService.syncForRepository(workspace.getId(), localRepo.getId());

        // 6. Verify
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(1);

        PullRequest storedPR = pullRequestRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), createdPR.number())
                .orElseThrow();

        assertThat(storedPR.getTitle()).isEqualTo(prTitle);
        assertThat(storedPR.getBody()).isEqualTo(prBody);
        assertThat(storedPR.getNumber()).isEqualTo(createdPR.number());
        assertThat(storedPR.getState()).isEqualTo(PullRequest.State.OPEN);
        assertThat(storedPR.requireRepository().getId()).isEqualTo(localRepo.getId());
    }

    @Test
    void syncsPullRequestReviews() throws Exception {
        // 1. Create ephemeral repository with PR and review using helper method
        var repository = createEphemeralRepository("pr-review-sync");
        var prArtifacts = createPullRequestWithReview(repository);

        // 2. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 3. Sync pull requests
        SyncResult syncResult = pullRequestSyncService.syncForRepository(workspace.getId(), localRepo.getId());
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(1);

        // 4. Verify PR is synced
        PullRequest storedPR = pullRequestRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), prArtifacts.pullRequestNumber())
                .orElseThrow();

        assertThat(storedPR.getTitle()).isEqualTo(prArtifacts.pullRequestTitle());
        assertThat(storedPR.getNumber()).isEqualTo(prArtifacts.pullRequestNumber());

        // 5. Sync reviews separately
        reviewSyncService.syncForPullRequest(workspace.getId(), storedPR);

        // 6. Verify reviews are synced
        List<PullRequestReview> reviews = pullRequestReviewRepository.findAll().stream()
                .filter(r ->
                        r.getPullRequest() != null && r.getPullRequest().getId().equals(storedPR.getId()))
                .toList();

        // Reviews should now be synced from the separate sync call
        assertThat(reviews)
                .anyMatch(r -> r.getId().equals(prArtifacts.reviewId())
                        || (r.getBody() != null
                                && r.getBody()
                                        .contains(prArtifacts
                                                .reviewBody()
                                                .substring(
                                                        0,
                                                        Math.min(
                                                                20,
                                                                prArtifacts
                                                                        .reviewBody()
                                                                        .length())))));
    }

    @Test
    void syncsMultiplePullRequests() throws Exception {
        // 1. Create ephemeral repository
        var repository = createEphemeralRepository("multi-pr-sync");
        var repoInfo = fixtureService.getRepositoryInfo(repository.fullName());

        // 2. Create first PR
        String branchName1 = "feature-" + nextEphemeralSlug("branch1");
        fixtureService.createBranch(repoInfo.nodeId(), branchName1, headCommitSha(repoInfo));
        fixtureService.createCommitOnBranch(repository.fullName(), branchName1, "Add file 1", "file1.txt", "Content 1");
        String prTitle1 = "IT PR " + nextEphemeralSlug("pr1");
        var pr1 = fixtureService.createPullRequest(
                repoInfo.nodeId(), prTitle1, "First PR", branchName1, defaultBranch(repoInfo));

        // 3. Create second PR
        String branchName2 = "feature-" + nextEphemeralSlug("branch2");
        fixtureService.createBranch(repoInfo.nodeId(), branchName2, headCommitSha(repoInfo));
        fixtureService.createCommitOnBranch(repository.fullName(), branchName2, "Add file 2", "file2.txt", "Content 2");
        String prTitle2 = "IT PR " + nextEphemeralSlug("pr2");
        var pr2 = fixtureService.createPullRequest(
                repoInfo.nodeId(), prTitle2, "Second PR", branchName2, defaultBranch(repoInfo));

        // 4. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 5. Sync pull requests
        SyncResult syncResult = pullRequestSyncService.syncForRepository(workspace.getId(), localRepo.getId());

        // 6. Verify both PRs are synced
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(2);

        PullRequest storedPR1 = pullRequestRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), pr1.number())
                .orElseThrow();
        assertThat(storedPR1.getTitle()).isEqualTo(prTitle1);

        PullRequest storedPR2 = pullRequestRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), pr2.number())
                .orElseThrow();
        assertThat(storedPR2.getTitle()).isEqualTo(prTitle2);
    }

    private static String defaultBranch(GitHubTestFixtureService.RepositoryInfo repository) {
        String branch = repository.defaultBranch();
        assertNotNull(branch);
        return branch;
    }

    private static String headCommitSha(GitHubTestFixtureService.RepositoryInfo repository) {
        String sha = repository.headCommitSha();
        assertNotNull(sha);
        return sha;
    }
}
