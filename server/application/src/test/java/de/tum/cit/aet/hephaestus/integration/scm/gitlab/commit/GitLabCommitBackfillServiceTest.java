package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class GitLabCommitBackfillServiceTest extends BaseUnitTest {
    @Test
    void shouldCheckAllBranchesWithoutUsingTheLatestStoredCommitAsAnAncestryCutoff() {
        GitRepositoryManager git = mock(GitRepositoryManager.class);
        GitLabTokenService tokens = mock(GitLabTokenService.class);
        CommitRepository commits = mock(CommitRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        var service = new GitLabCommitBackfillService(
                git,
                tokens,
                commits,
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                mock(ApplicationEventPublisher.class),
                transactions);
        when(git.isEnabled()).thenReturn(true);
        when(git.resolveBranchHead(new RepositoryKey(100L, 1L), "main")).thenReturn("unchanged-head");
        when(tokens.resolveServerUrl(100L)).thenReturn("https://gitlab.example.com");
        when(commits.findGitDetailsCapturedShas(1L, List.of("unchanged-head", "feature-commit")))
                .thenReturn(Set.of("unchanged-head"));
        doAnswer(invocation -> {
                    Function<List<String>, Set<String>> existing = invocation.getArgument(1);
                    assertThat(existing.apply(List.of("unchanged-head", "feature-commit")))
                            .containsExactly("unchanged-head");
                    return null;
                })
                .when(git)
                .forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());

        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.completed(0));
        verify(git).forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());
    }

    @Test
    void shouldReportFailureWhenTraversalDoesNotFinish() {
        GitRepositoryManager git = mock(GitRepositoryManager.class);
        GitLabTokenService tokens = mock(GitLabTokenService.class);
        var service = new GitLabCommitBackfillService(
                git,
                tokens,
                mock(CommitRepository.class),
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                mock(ApplicationEventPublisher.class),
                mock(TransactionTemplate.class));
        when(git.isEnabled()).thenReturn(true);
        when(git.resolveBranchHead(new RepositoryKey(100L, 1L), "main")).thenReturn("head");
        when(tokens.resolveServerUrl(100L)).thenReturn("https://gitlab.example.com");
        doAnswer(invocation -> {
                    throw new IllegalStateException("Persistence unavailable");
                })
                .when(git)
                .forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());

        assertThat(service.backfillCommits(100L, TestEntities.repository(1L, "owner/repo", "main")))
                .isEqualTo(SyncResult.abortedError(0));
    }
}
