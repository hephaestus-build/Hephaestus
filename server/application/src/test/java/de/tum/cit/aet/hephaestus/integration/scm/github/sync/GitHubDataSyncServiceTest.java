package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.DiscussionsProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.ProjectsProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitMetadataEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.GitHubCommitBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.GitHubDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.RepositoryIdentityMismatchException;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link GitHubDataSyncService#syncSyncTarget}, guarding two data-loss hazards in the
 * incremental path:
 *
 * <ol>
 *   <li>Sub-syncs must not be short-circuited when {@code repository.updatedAt} has not advanced.
 *       GitHub does not bump that field when an issue/PR is opened or commented on, so keying off it
 *       silently never ingests new PRs.</li>
 *   <li>The incremental path must not read or write {@code issueSyncCursor} /
 *       {@code pullRequestSyncCursor}, which are owned by the CREATED_AT-ordered historical backfill.
 *       Resuming an UPDATED_AT-ordered query from a CREATED_AT cursor skips the newest items and
 *       clobbers backfill's checkpoint.</li>
 * </ol>
 */
class GitHubDataSyncServiceTest extends BaseUnitTest {

    private static final long SCOPE_ID = 1L;
    private static final long SYNC_TARGET_ID = 42L;
    private static final long REPOSITORY_ID = 100L;
    private static final long PROVIDER_ID = 7L;
    private static final String REPO_NAME = "acme/widgets";

    /** Frozen so a re-synced repo reports a bit-identical "unchanged" updatedAt. */
    private static final Instant REPO_UPDATED_AT = Instant.parse("2026-07-01T00:00:00Z");

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private OrganizationMembershipListener organizationMembershipListener;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private GitHubLabelSyncService labelSyncService;

    @Mock
    private GitHubMilestoneSyncService milestoneSyncService;

    @Mock
    private GitHubIssueSyncService issueSyncService;

    @Mock
    private GitHubIssueDependencySyncService issueDependencySyncService;

    @Mock
    private GitHubIssueTypeSyncService issueTypeSyncService;

    @Mock
    private GitHubSubIssueSyncService subIssueSyncService;

    @Mock
    private GitHubPullRequestSyncService pullRequestSyncService;

    @Mock
    private GitHubDiscussionSyncService discussionSyncService;

    @Mock
    private GitHubTeamSyncService teamSyncService;

    @Mock
    private GitHubProjectSyncService projectSyncService;

    @Mock
    private GitHubOrganizationSyncService organizationSyncService;

    @Mock
    private GitHubRepositorySyncService repositorySyncService;

    @Mock
    private GitHubCollaboratorSyncService collaboratorSyncService;

    @Mock
    private GitHubCommitBackfillService commitBackfillService;

    @Mock
    private CommitAuthorEnrichmentService commitAuthorEnrichmentService;

    @Mock
    private CommitMetadataEnrichmentService commitMetadataEnrichmentService;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private InstallationTokenProvider tokenProvider;

    @Mock
    private GitHubAppTokenService gitHubAppTokenService;

    @Mock
    private RateLimitTracker rateLimitTracker;

    private GitHubDataSyncService service;
    private IdentityProvider provider;

    @BeforeEach
    void setUp() {
        SyncSchedulerProperties properties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15, // cooldownMinutes
                new BackfillProperties(false, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                new DiscussionsProperties(false), // discussions off — keeps the test on the issue/PR path
                new ProjectsProperties(false));

        service = new GitHubDataSyncService(
                properties,
                gitProviderRepository,
                syncTargetProvider,
                organizationMembershipListener,
                repositoryRepository,
                organizationRepository,
                labelSyncService,
                milestoneSyncService,
                issueSyncService,
                issueDependencySyncService,
                issueTypeSyncService,
                subIssueSyncService,
                pullRequestSyncService,
                discussionSyncService,
                teamSyncService,
                projectSyncService,
                organizationSyncService,
                repositorySyncService,
                collaboratorSyncService,
                commitBackfillService,
                commitAuthorEnrichmentService,
                commitMetadataEnrichmentService,
                exceptionClassifier,
                tokenProvider,
                gitHubAppTokenService,
                rateLimitTracker,
                mock(ActivityLedgerRepair.class));

        provider = new IdentityProvider();
        ReflectionTestUtils.setField(provider, "id", PROVIDER_ID);

        Repository repository = new Repository();
        repository.setId(REPOSITORY_ID);
        repository.setNameWithOwner(REPO_NAME);
        repository.setProvider(provider);
        // GitHub leaves updatedAt untouched when a PR is opened, so a re-synced repository reports the
        // very same timestamp already stored.
        repository.setUpdatedAt(REPO_UPDATED_AT);

        when(syncTargetProvider.isScopeActiveForSync(SCOPE_ID)).thenReturn(true);
        lenient()
                .when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.of(provider));
        lenient()
                .when(repositoryRepository.findByNameWithOwnerAndProviderId(REPO_NAME, PROVIDER_ID))
                .thenReturn(Optional.of(repository));
        // Re-sync returns the same entity with an unchanged updatedAt. Lenient: the NOT_FOUND
        // rename/delete tests re-stub this to throw, which would otherwise flag this as unused.
        lenient()
                .when(repositorySyncService.syncRepository(SCOPE_ID, REPO_NAME, provider, null))
                .thenReturn(Optional.of(repository));

        lenient()
                .when(commitBackfillService.backfillCommits(any(), any(), any()))
                .thenReturn(0);
        lenient()
                .when(issueSyncService.syncForRepository(any(), any(), any(), any(), any()))
                .thenReturn(SyncResult.completed(3));
        lenient()
                .when(pullRequestSyncService.syncForRepository(any(), any(), any(), any(), any()))
                .thenReturn(SyncResult.completed(2));
    }

    /**
     * Builds a sync target that has already completed an initial issue/PR sync and whose
     * collaborator/label/milestone cooldowns are still warm, so the only remaining work is
     * issues and PRs.
     *
     * @param issueCursor       value for the backfill-owned issue cursor column
     * @param pullRequestCursor value for the backfill-owned PR cursor column
     */
    private static SyncTarget syncTarget(@Nullable String issueCursor, @Nullable String pullRequestCursor) {
        // All timestamps recent => within cooldown and initial sync "completed".
        Instant recent = Instant.now();
        var builder = SyncTargetTestBuilder.syncTarget()
                .id(SYNC_TARGET_ID)
                .scopeId(SCOPE_ID)
                .installationId(100L)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(REPO_NAME)
                .lastLabelsSyncedAt(recent)
                .lastMilestonesSyncedAt(recent)
                .lastIssuesSyncedAt(recent)
                .lastPullRequestsSyncedAt(recent)
                .lastDiscussionsSyncedAt(recent)
                .lastCollaboratorsSyncedAt(recent)
                .lastFullSyncAt(recent);
        if (issueCursor != null) builder.issueSyncCursor(issueCursor);
        if (pullRequestCursor != null) builder.pullRequestSyncCursor(pullRequestCursor);
        return builder.build();
    }

    private static final long NATIVE_ID = 555L;

    /** Same warm target as {@link #syncTarget}, with a stable provider {@code nativeId} attached. */
    private static SyncTarget syncTargetWithNativeId(@Nullable Long nativeId) {
        Instant recent = Instant.now();
        var builder = SyncTargetTestBuilder.syncTarget()
                .id(SYNC_TARGET_ID)
                .scopeId(SCOPE_ID)
                .installationId(100L)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(REPO_NAME)
                .lastLabelsSyncedAt(recent)
                .lastMilestonesSyncedAt(recent)
                .lastIssuesSyncedAt(recent)
                .lastPullRequestsSyncedAt(recent)
                .lastDiscussionsSyncedAt(recent)
                .lastCollaboratorsSyncedAt(recent)
                .lastFullSyncAt(recent);
        if (nativeId != null) builder.nativeId(nativeId);
        return builder.build();
    }

    @Test
    void shouldPreserveSyncTargetWhenRenamedRepoHasStableIdOnNotFound() {
        // The repo was renamed upstream. Its local row (found by the old name) still exists, so metadata
        // re-sync answers 404 for both name and stable id. Access loss and deletion are indistinguishable,
        // so neither the monitor nor its retained work may be removed.
        SyncTarget target = syncTargetWithNativeId(NATIVE_ID);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenThrow(new RepositoryNotFoundOnGitProviderException(REPO_NAME));
        when(repositorySyncService.resolveRepositoryNameById(SCOPE_ID, NATIVE_ID))
                .thenThrow(new RepositoryNotFoundOnGitProviderException(REPO_NAME));

        lenient().when(repositoryRepository.findById(REPOSITORY_ID)).thenReturn(Optional.empty());

        boolean result = service.syncSyncTarget(target);

        // Neither the monitor nor the repository is deleted, and the cycle reports not-completed so it
        // retries.
        verify(syncTargetProvider, never()).removeSyncTarget(any());
        verify(repositoryRepository, never()).delete(any());
        assertThat(result).isFalse();
    }

    @Test
    void shouldPreserveSyncTargetWhenLegacyRowHasNoStableIdOnNotFound() {
        // Even without a stable id, 404 is not proof of deletion.
        SyncTarget target = syncTargetWithNativeId(null);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenThrow(new RepositoryNotFoundOnGitProviderException(REPO_NAME));

        lenient().when(repositoryRepository.findById(REPOSITORY_ID)).thenReturn(Optional.empty());

        boolean result = service.syncSyncTarget(target);

        // Preserve the monitor and pause repeated failing fetches.
        verify(syncTargetProvider, never()).removeSyncTarget(SYNC_TARGET_ID);
        verify(syncTargetProvider).recordRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID);
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldTrackStableIdInsteadOfReusedNameWhenUnavailableOrHealthy(boolean unavailable) {
        var target = syncTargetWithNativeId(NATIVE_ID);
        when(syncTargetProvider.isRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(unavailable);
        if (!unavailable) {
            when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                    .thenThrow(new RepositoryIdentityMismatchException(REPO_NAME, NATIVE_ID, NATIVE_ID + 1));
        }
        when(repositorySyncService.resolveRepositoryNameById(SCOPE_ID, NATIVE_ID))
                .thenReturn("owner/renamed");
        var repository = new Repository();
        repository.setId(REPOSITORY_ID + 1);
        repository.setNativeId(NATIVE_ID);
        repository.setNameWithOwner("owner/renamed");
        repository.setProvider(provider);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq("owner/renamed"), any(), any()))
                .thenReturn(Optional.of(repository));
        assertThat(service.syncSyncTarget(target)).isTrue();
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID + 1), any(), any(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID + 1), any(), any(), any());
        verify(repositorySyncService, times(unavailable ? 0 : 1))
                .syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any());
        verify(syncTargetProvider).clearRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID);
        verify(syncTargetProvider).reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, "owner/renamed");
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, null);
    }

    @Test
    void shouldKeepUnavailableStateWhenResolvedNameReturnsAnotherRepository() {
        when(syncTargetProvider.isRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);
        when(repositorySyncService.resolveRepositoryNameById(SCOPE_ID, NATIVE_ID))
                .thenReturn(REPO_NAME);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenThrow(new RepositoryIdentityMismatchException(REPO_NAME, NATIVE_ID, NATIVE_ID + 1));

        assertThat(service.syncSyncTarget(syncTargetWithNativeId(NATIVE_ID))).isFalse();

        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
        verify(syncTargetProvider, never()).recordRepositoryUnavailable(any(), any());
        verify(syncTargetProvider).retryUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID);
        verifyNoInteractions(issueSyncService, pullRequestSyncService, commitBackfillService);
    }

    @Test
    void shouldSkipRepositoryAndCommitFetchWhenDailyRecheckIsNotDue() {
        when(syncTargetProvider.deferUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);
        service.syncSyncTarget(syncTargetWithNativeId(NATIVE_ID));
        verify(repositorySyncService, never()).syncRepository(any(), any(), any(), any());
        verify(commitBackfillService, never()).backfillCommits(any(), any(), any());
    }

    @Test
    void shouldKeepRetainedWorkVisibleAfterAnotherWorkspaceResolvesARename() {
        var repository = new Repository();
        repository.setNativeId(NATIVE_ID);
        repository.setNameWithOwner("acme/renamed");
        when(repositoryRepository.findByNativeIdAndProviderId(NATIVE_ID, PROVIDER_ID))
                .thenReturn(Optional.of(repository));
        when(syncTargetProvider.deferUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);

        assertThat(service.syncSyncTarget(syncTargetWithNativeId(NATIVE_ID))).isFalse();

        verify(syncTargetProvider).reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, "acme/renamed");
        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
        verifyNoInteractions(repositorySyncService, issueSyncService, pullRequestSyncService, commitBackfillService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReleaseDailyRecheckWhenInstallationCannotBeRead(boolean hasLocalRepository) {
        if (hasLocalRepository) {
            var repository = new Repository();
            repository.setId(REPOSITORY_ID);
            repository.setNativeId(NATIVE_ID);
            repository.setNameWithOwner(REPO_NAME);
            when(repositoryRepository.findByNativeIdAndProviderId(NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.of(repository));
        }
        when(syncTargetProvider.isRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);
        var failure = new InstallationNotFoundException(99L);
        when(repositorySyncService.resolveRepositoryNameById(SCOPE_ID, NATIVE_ID))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.syncSyncTarget(syncTargetWithNativeId(NATIVE_ID)))
                .isSameAs(failure);

        verify(syncTargetProvider).retryUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID);
        verify(syncTargetProvider, never()).recordRepositoryUnavailable(any(), any());
        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
        verifyNoInteractions(issueSyncService, pullRequestSyncService, commitBackfillService);
    }

    @Test
    void shouldKeepNormalRetryWhenMetadataFetchIsRateLimited() {
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.empty());
        service.syncSyncTarget(syncTargetWithNativeId(NATIVE_ID));
        verify(syncTargetProvider, never()).recordRepositoryUnavailable(any(), any());
        verify(syncTargetProvider).retryUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID);
        verify(commitBackfillService, never()).backfillCommits(any(), any(), any());
    }

    @Test
    void shouldReconcileMonitorIdentityOnEverySync() {
        // Successful metadata captures the stable id and current name for later rechecks.
        Repository resolved = new Repository();
        resolved.setId(REPOSITORY_ID);
        resolved.setNativeId(NATIVE_ID);
        resolved.setNameWithOwner(REPO_NAME);
        lenient()
                .when(repositoryRepository.findByNameWithOwnerAndProviderId(REPO_NAME, PROVIDER_ID))
                .thenReturn(Optional.of(resolved));

        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.of(resolved));
        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider).reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, REPO_NAME);
    }

    @Test
    void shouldSyncIssuesAndPullRequestsWhenRepositoryUpdatedAtUnchanged() {
        // A repo that has completed its initial sync and whose updatedAt has not moved — the state in
        // which a short-circuit on updatedAt would return early and drop new PRs.
        SyncTarget target = syncTarget(null, null);

        boolean result = service.syncSyncTarget(target);

        // The sub-syncs still run rather than being skipped as "repoUnchanged".
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any(), any());
        assertThat(result).isTrue();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, null);
    }

    @Test
    void shouldRecordIncompleteIssueSyncAndClearAfterRecovery() {
        SyncTarget target = syncTarget(null, null);
        when(issueSyncService.syncForRepository(any(), any(), any(), any(), any()))
                .thenReturn(SyncResult.abortedError(1), SyncResult.completed(3));

        assertThat(service.syncSyncTarget(target)).isFalse();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");

        assertThat(service.syncSyncTarget(target)).isTrue();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, null);
    }

    @Test
    void shouldContinueIssueSyncAfterCommitBackfillFailsAndClearOnRecovery() {
        SyncTarget target = syncTarget(null, null);
        doThrow(new IllegalStateException("sensitive checkout detail"))
                .doReturn(0)
                .when(commitBackfillService)
                .backfillCommits(any(), any(), any());

        assertThat(service.syncSyncTarget(target)).isFalse();
        verify(syncTargetProvider)
                .updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, "Commit backfill failed (IllegalStateException)");

        assertThat(service.syncSyncTarget(target)).isTrue();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, null);
        verify(issueSyncService, times(2)).syncForRepository(any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotResumeIncrementalSyncFromBackfillCursor() {
        // Mid-backfill state — the shared cursor columns hold CREATED_AT-ordered checkpoints belonging to
        // GitHubHistoricalBackfillService.
        SyncTarget target = syncTarget("issue-cursor-created-at-desc", "pr-cursor-created-at-desc");

        service.syncSyncTarget(target);

        // The UPDATED_AT-ordered incremental path starts fresh instead of resuming from a cursor produced
        // by a different ordering (which would skip the newest items).
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any(), any());
    }

    @Test
    void shouldNotClobberBackfillCursorWhenRunningIncrementalSync() {
        SyncTarget target = syncTarget("issue-cursor-created-at-desc", "pr-cursor-created-at-desc");

        service.syncSyncTarget(target);

        // Passing a null syncTargetId disables cursor persistence inside the sync services, so backfill's
        // checkpoint survives an interleaved incremental run.
        verify(issueSyncService, never()).syncForRepository(any(), any(), eq(SYNC_TARGET_ID), any(), any());
        verify(pullRequestSyncService, never()).syncForRepository(any(), any(), eq(SYNC_TARGET_ID), any(), any());
    }
}
