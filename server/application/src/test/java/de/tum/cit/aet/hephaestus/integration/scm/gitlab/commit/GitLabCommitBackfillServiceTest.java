package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitLabCommitBackfillServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    @TempDir
    private Path temporary;

    @Test
    void shouldSkipGitFetchWhenWorkspaceMonitorIsUnavailable() {
        var git = mock(GitRepositoryManager.class);
        var targets = mock(SyncTargetProvider.class);
        var target = SyncTargetTestBuilder.syncTarget()
                .id(7L)
                .scopeId(100L)
                .repositoryNameWithOwner("owner/repo")
                .build();
        when(targets.getSyncTargetsForScope(100L)).thenReturn(List.of(target));
        when(targets.isRepositoryUnavailable(100L, 7L)).thenReturn(true);
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var service = new GitLabCommitBackfillService(
                git,
                mock(GitLabTokenService.class),
                mock(CommitRepository.class),
                mock(CommitDetailsPersister.class),
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                targets,
                projectAdmission);
        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.abortedError(0));
        verify(git, never()).ensureRepository(any(), any(), any());
    }

    @Test
    void shouldCheckAllBranchesWithoutUsingTheLatestStoredCommitAsAnAncestryCutoff() {
        GitRepositoryManager git = mock(GitRepositoryManager.class);
        GitLabTokenService tokens = mock(GitLabTokenService.class);
        CommitRepository commits = mock(CommitRepository.class);
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var service = new GitLabCommitBackfillService(
                git,
                tokens,
                commits,
                mock(CommitDetailsPersister.class),
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                mock(SyncTargetProvider.class),
                projectAdmission);
        when(git.isEnabled()).thenReturn(true);
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
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var service = new GitLabCommitBackfillService(
                git,
                tokens,
                mock(CommitRepository.class),
                mock(CommitDetailsPersister.class),
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                mock(SyncTargetProvider.class),
                projectAdmission);
        when(git.isEnabled()).thenReturn(true);
        when(tokens.resolveServerUrl(100L)).thenReturn("https://gitlab.example.com");
        doAnswer(invocation -> {
                    throw new IllegalStateException("Persistence unavailable");
                })
                .when(git)
                .forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());

        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.abortedError(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCompleteBackfillForARealEmptyRepositoryWithoutADefaultBranchHead(boolean replaceCachedMirror)
            throws Exception {
        Path remote = temporary.resolve("remote/owner/repo.git");
        createEmptyRepository(remote);
        Path cachedRemote = replaceCachedMirror ? temporary.resolve("previous/owner/repo.git") : remote;
        if (replaceCachedMirror) {
            createEmptyRepository(cachedRemote);
        }
        var git = localGit();
        var key = new RepositoryKey(100L, 1L);
        String cachedUrl = cachedRemote.toUri().toString();
        git.ensureRepository(
                key, cachedUrl.endsWith("/") ? cachedUrl.substring(0, cachedUrl.length() - 1) : cachedUrl, null);
        assertThat(git.resolveBranchHead(key, "main")).isNull();
        List<CommitDetails> walked = new ArrayList<>();
        git.forEachMissingCommit(key, shas -> Set.of(), walked::add);
        assertThat(walked).isEmpty();
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var persister = mock(CommitDetailsPersister.class);
        var service = localBackfill(git, persister);

        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.completed(0));
        verify(persister, never()).persist(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "missing-default"})
    void shouldCaptureTheActualCommitFromALocalPopulatedRepository(String configuredDefaultBranch) throws Exception {
        Path remote = temporary.resolve("remote/owner/repo.git");
        Files.createDirectories(remote.getParent());
        String expectedSha;
        try (Git source = Git.init()
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            expectedSha = source.commit()
                    .setSign(false)
                    .setAllowEmpty(true)
                    .setMessage("Local history control")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                    .name();
        }
        var repository = TestEntities.repository(1L, "owner/repo", configuredDefaultBranch);
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var persister = mock(CommitDetailsPersister.class);
        List<CommitDetails> captured = new ArrayList<>();
        when(persister.persist(any(), eq(repository), any())).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return CommitDetailsPersister.Outcome.CAPTURED;
        });
        var service = localBackfill(localGit(), persister);

        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.completed(1));
        assertThat(captured).singleElement().satisfies(commit -> {
            assertThat(commit.sha()).isEqualTo(expectedSha);
            assertThat(commit.message()).isEqualTo("Local history control");
            assertThat(commit.parentShas()).isEmpty();
        });
    }

    @Test
    void shouldKeepARealMissingLocalRemoteAsABackfillFailure() {
        var repository = TestEntities.repository(1L, "owner/repo", "main");
        repository.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 100L, repository, 7L);
        var persister = mock(CommitDetailsPersister.class);
        var service = localBackfill(localGit(), persister);

        assertThat(service.backfillCommits(100L, repository)).isEqualTo(SyncResult.abortedError(0));
        verify(persister, never()).persist(any(), any(), any());
    }

    private static void createEmptyRepository(Path directory) throws Exception {
        Files.createDirectories(directory.getParent());
        try (Git source =
                Git.init().setBare(true).setDirectory(directory.toFile()).call()) {
            assertThat(source.getRepository().getRefDatabase().getRefs()).isEmpty();
        }
    }

    private GitRepositoryManager localGit() {
        return new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 8L * 1024 * 1024 * 1024),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()));
    }

    private GitLabCommitBackfillService localBackfill(GitRepositoryManager git, CommitDetailsPersister persister) {
        var tokens = mock(GitLabTokenService.class);
        String origin = temporary.resolve("remote").toUri().toString();
        when(tokens.resolveServerUrl(100L))
                .thenReturn(origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin);
        when(tokens.getAccessToken(100L)).thenReturn("local-test-token");
        return new GitLabCommitBackfillService(
                git,
                tokens,
                mock(CommitRepository.class),
                persister,
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                mock(SyncTargetProvider.class),
                projectAdmission);
    }
}
