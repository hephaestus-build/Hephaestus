package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class GitHubCommitBackfillServiceTest extends BaseUnitTest {

    private static final RepositoryKey KEY = new RepositoryKey(100L, 1L);

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitHubAppTokenService tokenService;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitDetailsPersister persister;

    @Mock
    private CommitAuthorResolver authorResolver;

    @InjectMocks
    private GitHubCommitBackfillService service;

    /** Feeds the walk the way native Git does: ask which shas are captured, then hand over each commit. */
    private void stubCommits(List<CommitDetails> commits) {
        doAnswer(invocation -> {
                    Function<List<String>, Set<String>> captured = invocation.getArgument(1);
                    captured.apply(commits.stream().map(CommitDetails::sha).toList());
                    Consumer<CommitDetails> consumer = invocation.getArgument(2);
                    commits.forEach(consumer);
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachMissingCommit(eq(KEY), any(), any());
    }

    private void stubWalkableRepository() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.resolveBranchHead(KEY, "main")).thenReturn("head");
    }

    private static SyncTarget createSyncTarget(AuthMode authMode) {
        var builder = SyncTargetTestBuilder.syncTarget()
                .id(1L)
                .scopeId(100L)
                .authMode(authMode)
                .repositoryNameWithOwner("owner/repo");
        if (authMode == AuthMode.INSTALLATION_APP) builder.installationId(42L);
        if (authMode == AuthMode.PERSONAL_ACCESS_TOKEN) builder.personalAccessToken("ghp_test_token");
        return builder.build();
    }

    private static Repository createMockRepository(Long id, String nameWithOwner, @Nullable String defaultBranch) {
        Repository repo = TestEntities.repository(id, nameWithOwner, "main");
        repo.setDefaultBranch(defaultBranch);
        repo.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITHUB));
        return repo;
    }

    private static CommitDetails createCommitInfo(String sha, String message) {
        return new CommitDetails(
                sha,
                message,
                null,
                "Author",
                "author@test.com",
                Instant.parse("2024-01-15T10:00:00Z"),
                "Committer",
                "committer@test.com",
                Instant.parse("2024-01-15T10:00:00Z"),
                10,
                5,
                1,
                List.of(new CommitDetails.FileChange(
                        "src/Main.java", CommitFileChange.ChangeType.MODIFIED, 10, 5, 15, null)),
                List.of());
    }

    @Nested
    class SkipConditions {

        @Test
        @DisplayName("should return -1 when git checkout is disabled")
        void shouldReturnNegativeOneWhenDisabled() {
            when(gitRepositoryManager.isEnabled()).thenReturn(false);
            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
            verify(gitRepositoryManager, never()).ensureRepository(any(RepositoryKey.class), anyString(), any());
        }

        @Test
        void shouldReturnNegativeOneWhenDefaultBranchNull() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            Repository repo = createMockRepository(1L, "owner/repo", null);
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
            verify(gitRepositoryManager, never()).ensureRepository(any(RepositoryKey.class), anyString(), any());
        }

        @Test
        void shouldReturnNegativeOneWhenDefaultBranchBlank() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            Repository repo = createMockRepository(1L, "owner/repo", "  ");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when HEAD cannot be resolved")
        void shouldReturnNegativeOneWhenHeadUnresolvable() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(KEY, "main")).thenReturn(null);
            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }
    }

    @Test
    void shouldCountOnlyCapturedCommitsAndContinueTheWalkWhenOneFailsToPersist() {
        stubWalkableRepository();
        when(persister.persist(any(), any(), any())).thenAnswer(invocation -> switch (invocation
                .<CommitDetails>getArgument(0)
                .sha()) {
            case "broken" -> Outcome.FAILED;
            case "existing" -> Outcome.ALREADY_CAPTURED;
            default -> Outcome.CAPTURED;
        });
        List<CommitDetails> commits = List.of(
                createCommitInfo("first", "Commit"),
                createCommitInfo("broken", "Commit"),
                createCommitInfo("existing", "Commit"),
                createCommitInfo("last", "Commit"));
        stubCommits(commits);
        Repository repo = createMockRepository(1L, "owner/repo", "main");

        int result = service.backfillCommits(createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN), repo, 100L);

        assertThat(result).isEqualTo(2);
        for (CommitDetails commit : commits) verify(persister).persist(eq(commit), eq(repo), any());
    }

    @Test
    void shouldAskTheRepositoryWhichShasAreCapturedWhenWalking() {
        stubWalkableRepository();
        when(persister.persist(any(), any(), any())).thenReturn(Outcome.CAPTURED);
        stubCommits(List.of(createCommitInfo("first", "Commit"), createCommitInfo("second", "Commit")));

        service.backfillCommits(
                createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN), createMockRepository(1L, "owner/repo", "main"), 100L);

        verify(commitRepository).findGitDetailsCapturedShas(1L, List.of("first", "second"));
    }

    @Test
    void shouldHandAGraphqlSyncOriginToThePersisterWhenWalking() {
        stubWalkableRepository();
        when(persister.persist(any(), any(), any())).thenReturn(Outcome.CAPTURED);
        when(authorResolver.resolveByEmail("author@test.com", 1L)).thenReturn(10L);
        when(authorResolver.resolveByEmail("nobody@test.com", 1L)).thenReturn(null);
        CommitDetails commitInfo = createCommitInfo("commit1", "Commit");
        stubCommits(List.of(commitInfo));
        Repository repo = createMockRepository(1L, "owner/repo", "main");

        int result = service.backfillCommits(createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN), repo, 100L);

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<CommitDetailsPersister.Origin> origin =
                ArgumentCaptor.forClass(CommitDetailsPersister.Origin.class);
        verify(persister).persist(eq(commitInfo), eq(repo), origin.capture());
        assertThat(origin.getValue().scopeId()).isEqualTo(100L);
        assertThat(origin.getValue().dataSource()).isEqualTo(DataSource.GRAPHQL_SYNC);
        assertThat(origin.getValue().provider()).isEqualTo(IdentityProviderType.GITHUB);
        assertThat(origin.getValue().commitUrl().apply("commit1"))
                .isEqualTo("https://github.com/owner/repo/commit/commit1");
        assertThat(origin.getValue().userIdByEmail().apply("author@test.com")).isEqualTo(10L);
        assertThat(origin.getValue().userIdByEmail().apply("nobody@test.com")).isNull();
    }

    @Test
    void shouldReturnZeroWhenTheWalkHandsOverNoCommit() {
        stubWalkableRepository();
        stubCommits(List.of());

        int result = service.backfillCommits(
                createSyncTarget(AuthMode.INSTALLATION_APP), createMockRepository(1L, "owner/repo", "main"), 100L);

        assertThat(result).isEqualTo(0);
        verify(persister, never()).persist(any(), any(), any());
    }

    @Nested
    class Authentication {

        @Test
        void shouldUseInstallationTokenForGitHubApp() {
            stubWalkableRepository();
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenReturn("ghs_install_token");
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            verify(gitRepositoryManager)
                    .ensureRepository(KEY, "https://github.com/owner/repo.git", "ghs_install_token");
        }

        @Test
        void shouldUsePATForPersonalAccessTokenAuth() {
            stubWalkableRepository();
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN);

            service.backfillCommits(target, repo, 100L);

            verify(gitRepositoryManager).ensureRepository(KEY, "https://github.com/owner/repo.git", "ghp_test_token");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturnNegativeOneOnGitOperationException() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new GitRepositoryManager.GitOperationException("Clone failed", new RuntimeException()))
                    .when(gitRepositoryManager)
                    .ensureRepository(any(RepositoryKey.class), anyString(), any());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        void shouldReturnNegativeOneOnUnexpectedException() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("Unexpected error"))
                    .when(gitRepositoryManager)
                    .ensureRepository(any(RepositoryKey.class), anyString(), any());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        void shouldReturnNegativeOneWhenTokenServiceFails() {
            stubWalkableRepository();
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenThrow(new RuntimeException("Token error"));
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            // A token failure is not fatal: the backfill proceeds with a null token.
            assertThat(result).isEqualTo(0);
            verify(gitRepositoryManager).ensureRepository(KEY, "https://github.com/owner/repo.git", null);
        }
    }
}
