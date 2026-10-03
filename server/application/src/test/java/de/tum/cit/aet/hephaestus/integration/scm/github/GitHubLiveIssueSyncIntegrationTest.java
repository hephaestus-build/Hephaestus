package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Live integration tests for GitHub issue synchronization.
 * <p>
 * These tests hit the real GitHub API and require valid credentials.
 * They are tagged with "live" and will be skipped if credentials are not configured.
 */
class GitHubLiveIssueSyncIntegrationTest extends AbstractGitHubLiveSyncIntegrationTest {

    @Autowired
    private GitHubRepositorySyncService repositorySyncService;

    @Autowired
    private GitHubIssueSyncService issueSyncService;

    @Autowired
    private GitHubIssueCommentSyncService issueCommentSyncService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Test
    void syncsNewIssues() throws Exception {
        // 1. Create ephemeral repository
        var repository = createEphemeralRepository("issue-sync");

        // 2. Create issue via fixture service
        var repoInfo = fixtureService.getRepositoryInfo(repository.fullName());
        String issueTitle = "IT issue " + nextEphemeralSlug("issue");
        String issueBody = "Live integration issue created at " + Instant.now();
        var createdIssue = fixtureService.createIssue(repoInfo.nodeId(), issueTitle, issueBody);

        // 3. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 4. Sync issues
        SyncResult syncResult = issueSyncService.syncForRepository(workspace.getId(), localRepo.getId());

        // 5. Verify
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(1);

        Issue storedIssue = issueRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), createdIssue.number())
                .orElseThrow();
        assertThat(storedIssue.getTitle()).isEqualTo(issueTitle);
        assertThat(storedIssue.getBody()).isEqualTo(issueBody);
        assertThat(storedIssue.getNumber()).isEqualTo(createdIssue.number());
        assertThat(storedIssue.getState()).isEqualTo(Issue.State.OPEN);
        assertThat(storedIssue.requireRepository().getId()).isEqualTo(localRepo.getId());
    }

    @Test
    void syncsIssueComments() throws Exception {
        // 1. Create ephemeral repository with issue and comment
        var repository = createEphemeralRepository("issue-comment-sync");
        var createdIssue = createIssueWithComment(repository);

        // 2. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 3. Sync issues (this should include comments via the issue processor)
        SyncResult syncResult = issueSyncService.syncForRepository(workspace.getId(), localRepo.getId());
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(1);

        // 4. Verify issue is synced
        Issue storedIssue = issueRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), createdIssue.issueNumber())
                .orElseThrow();
        assertThat(storedIssue.getTitle()).isEqualTo(createdIssue.issueTitle());
        assertThat(storedIssue.getNumber()).isEqualTo(createdIssue.issueNumber());

        // 5. Set the repository explicitly to avoid lazy loading issues
        storedIssue.setRepository(localRepo);

        // 6. Sync comments separately (comments are synced via a different service)
        issueCommentSyncService.syncForIssue(workspace.getId(), storedIssue);

        // 7. Verify comment is synced
        List<IssueComment> comments = issueCommentRepository.findAll().stream()
                .filter(c -> c.getIssue() != null && c.getIssue().getId().equals(storedIssue.getId()))
                .toList();

        // Note: If comments are not synced via the issue sync service,
        // this assertion verifies the comment was at least created remotely.
        // A separate comment sync service may be needed for full comment sync.
        assertThat(comments)
                .anyMatch(c -> c.getNativeId().equals(createdIssue.commentId())
                        || c.getBody()
                                .contains(createdIssue
                                        .commentBody()
                                        .substring(
                                                0,
                                                Math.min(
                                                        20,
                                                        createdIssue
                                                                .commentBody()
                                                                .length()))));
    }

    @Test
    void syncsMultipleIssues() throws Exception {
        // 1. Create ephemeral repository
        var repository = createEphemeralRepository("multi-issue-sync");
        var repoInfo = fixtureService.getRepositoryInfo(repository.fullName());

        // 2. Create multiple issues
        String issueTitle1 = "IT issue " + nextEphemeralSlug("issue1");
        String issueTitle2 = "IT issue " + nextEphemeralSlug("issue2");
        var issue1 = fixtureService.createIssue(repoInfo.nodeId(), issueTitle1, "First issue body");
        var issue2 = fixtureService.createIssue(repoInfo.nodeId(), issueTitle2, "Second issue body");

        // 3. Sync repository first
        assertThat(repositorySyncService.syncRepository(workspace.getId(), repository.fullName(), githubProvider, null))
                .isPresent();
        var localRepo =
                repositoryRepository.findByNameWithOwner(repository.fullName()).orElseThrow();

        // 4. Sync issues
        SyncResult syncResult = issueSyncService.syncForRepository(workspace.getId(), localRepo.getId());

        // 5. Verify both issues are synced
        assertThat(syncResult.isCompleted()).isTrue();
        assertThat(syncResult.count()).isGreaterThanOrEqualTo(2);

        Issue storedIssue1 = issueRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), issue1.number())
                .orElseThrow();
        assertThat(storedIssue1.getTitle()).isEqualTo(issueTitle1);
        assertThat(storedIssue1.getNumber()).isEqualTo(issue1.number());

        Issue storedIssue2 = issueRepository
                .findByRepositoryIdAndNumber(localRepo.getId(), issue2.number())
                .orElseThrow();
        assertThat(storedIssue2.getTitle()).isEqualTo(issueTitle2);
        assertThat(storedIssue2.getNumber()).isEqualTo(issue2.number());
    }
}
