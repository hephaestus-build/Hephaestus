package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.RecentSyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReaderListing;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitMetadataEnrichmentService;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.GitHubCommitBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.RepositoryIdentityMismatchException;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService.CollaboratorListing;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService.ListedCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    private ActivityLedgerRepair activityLedgerRepair;

    @Mock
    private GitHubIssueCommentSyncService commentSyncService;

    @Mock
    private SourceProviderNamespaces providerNamespaces;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private OrganizationMembershipListener organizationMembershipListener;

    @Mock
    private RepositoryRepository repositoryRepository;

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
    private GitHubTeamSyncService teamSyncService;

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
                new FilterProperties(Set.of(), Set.of()));

        service = new GitHubDataSyncService(
                properties,
                providerNamespaces,
                syncTargetProvider,
                organizationMembershipListener,
                repositoryRepository,
                labelSyncService,
                milestoneSyncService,
                issueSyncService,
                issueDependencySyncService,
                issueTypeSyncService,
                subIssueSyncService,
                pullRequestSyncService,
                teamSyncService,
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
                activityLedgerRepair,
                commentSyncService);

        provider = new IdentityProvider();
        ReflectionTestUtils.setField(provider, "id", PROVIDER_ID);

        Repository repository = new Repository();
        repository.setId(REPOSITORY_ID);
        repository.setNativeId(NATIVE_ID);
        repository.setNameWithOwner(REPO_NAME);
        repository.setProvider(provider);
        // GitHub leaves updatedAt untouched when a PR is opened, so a re-synced repository reports the
        // very same timestamp already stored.
        repository.setUpdatedAt(REPO_UPDATED_AT);

        when(syncTargetProvider.isScopeActiveForSync(SCOPE_ID)).thenReturn(true);
        lenient()
                .when(providerNamespaces.findForMirror(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.of(provider));
        lenient()
                .when(repositoryRepository.findByNativeIdAndProviderId(NATIVE_ID, PROVIDER_ID))
                .thenReturn(Optional.of(repository));
        // Re-sync returns the same entity with an unchanged updatedAt. Lenient: the NOT_FOUND
        // rename/delete tests re-stub this to throw, which would otherwise flag this as unused.
        lenient()
                .when(repositorySyncService.syncRepository(SCOPE_ID, REPO_NAME, provider, NATIVE_ID))
                .thenReturn(Optional.of(repository));

        lenient()
                .when(commitBackfillService.backfillCommits(any(), any(), any()))
                .thenReturn(0);
        lenient()
                .when(issueSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.completed(3), null));
        lenient()
                .when(pullRequestSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.completed(2), null));
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
        var builder = recentlySynced();
        if (issueCursor != null) builder.issueSyncCursor(issueCursor);
        if (pullRequestCursor != null) builder.pullRequestSyncCursor(pullRequestCursor);
        return builder.build();
    }

    private static SyncTargetTestBuilder recentlySynced() {
        // All timestamps recent => within cooldown and initial sync "completed".
        Instant recent = Instant.now();
        return SyncTargetTestBuilder.syncTarget()
                .id(SYNC_TARGET_ID)
                .scopeId(SCOPE_ID)
                .nativeId(NATIVE_ID)
                .installationId(100L)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(REPO_NAME)
                .lastLabelsSyncedAt(recent)
                .lastMilestonesSyncedAt(recent)
                .lastIssuesSyncedAt(recent)
                .lastPullRequestsSyncedAt(recent)
                .lastCollaboratorsSyncedAt(recent)
                .lastFullSyncAt(recent);
    }

    private static final long NATIVE_ID = 555L;

    @Test
    void shouldPreserveSyncTargetWhenRenamedRepoHasStableIdOnNotFound() {
        // The repo was renamed upstream. Its local row (found by the old name) still exists, so metadata
        // re-sync answers 404 for both name and stable id. Access loss and deletion are indistinguishable,
        // so neither the monitor nor its retained work may be removed.
        SyncTarget target = syncTarget(null, null);
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
        verify(syncTargetProvider).recordRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID);
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldTrackStableIdInsteadOfReusedNameWhenUnavailableOrHealthy(boolean unavailable) {
        var target = syncTarget(null, null);
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
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID + 1), any(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID + 1), any(), any());
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

        assertThat(service.syncSyncTarget(syncTarget(null, null))).isFalse();

        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
        verify(syncTargetProvider, never()).recordRepositoryUnavailable(any(), any());
        verify(syncTargetProvider).retryUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID);
        verifyNoInteractions(issueSyncService, pullRequestSyncService, commitBackfillService);
    }

    @Test
    void shouldSkipRepositoryAndCommitFetchWhenDailyRecheckIsNotDue() {
        when(syncTargetProvider.deferUnavailableRepository(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);
        service.syncSyncTarget(syncTarget(null, null));
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

        assertThat(service.syncSyncTarget(syncTarget(null, null))).isFalse();

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

        assertThatThrownBy(() -> service.syncSyncTarget(syncTarget(null, null))).isSameAs(failure);

        verify(syncTargetProvider).recordRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID);
        verify(syncTargetProvider, never()).clearRepositoryUnavailable(any(), any());
        verifyNoInteractions(issueSyncService, pullRequestSyncService, commitBackfillService);
    }

    @Test
    void shouldKeepNormalRetryWhenMetadataFetchIsRateLimited() {
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.empty());
        service.syncSyncTarget(syncTarget(null, null));
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
                .when(repositoryRepository.findByNativeIdAndProviderId(NATIVE_ID, PROVIDER_ID))
                .thenReturn(Optional.of(resolved));

        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.of(resolved));
        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider).reconcileSyncTargetIdentity(SYNC_TARGET_ID, NATIVE_ID, REPO_NAME);
        verify(syncTargetProvider).updateSyncTimestamp(eq(SYNC_TARGET_ID), eq(SyncType.REPOSITORY_VISIBILITY), any());
        verify(syncTargetProvider).recordRepositoryNotPublic(any());
    }

    /**
     * The shared repository row holds the last read of any workspace. This workspace's proof follows the answer that its
     * own read stored, and a public answer carries the generation taken before the read began.
     */
    @ParameterizedTest(name = "stored row public={0}, this read public={1}")
    @CsvSource({"true,false", "false,true"})
    void shouldProveVisibilityFromThisReadsAnswerNotTheSharedRow(boolean sharedRowPublic, boolean readPublic) {
        Repository shared = new Repository();
        shared.setId(REPOSITORY_ID);
        shared.setNativeId(NATIVE_ID);
        shared.setNameWithOwner(REPO_NAME);
        shared.setVisibility(sharedRowPublic ? Repository.Visibility.PUBLIC : Repository.Visibility.PRIVATE);
        shared.setPrivate(!sharedRowPublic);
        Repository read = new Repository();
        read.setId(REPOSITORY_ID);
        read.setNativeId(NATIVE_ID);
        read.setNameWithOwner(REPO_NAME);
        read.setVisibility(readPublic ? Repository.Visibility.PUBLIC : Repository.Visibility.PRIVATE);
        read.setPrivate(!readPublic);
        lenient()
                .when(repositoryRepository.findByNativeIdAndProviderId(NATIVE_ID, PROVIDER_ID))
                .thenReturn(Optional.of(shared));
        ReadersRead begun = new ReadersRead(SYNC_TARGET_ID, NATIVE_ID, PROVIDER_ID, 7L, Instant.EPOCH);
        when(syncTargetProvider.beginReadersProof(eq(SYNC_TARGET_ID), eq(NATIVE_ID), eq(PROVIDER_ID), any()))
                .thenReturn(begun);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.of(read));

        service.syncSyncTarget(syncTarget(null, null));

        InOrder order = inOrder(syncTargetProvider, repositorySyncService);
        order.verify(syncTargetProvider).beginReadersProof(eq(SYNC_TARGET_ID), eq(NATIVE_ID), eq(PROVIDER_ID), any());
        order.verify(repositorySyncService).syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any());
        if (readPublic) {
            order.verify(syncTargetProvider).recordRepositoryPublic(begun);
            verify(syncTargetProvider, never()).recordRepositoryNotPublic(any());
        } else {
            order.verify(syncTargetProvider).recordRepositoryNotPublic(begun);
            verify(syncTargetProvider, never()).recordRepositoryPublic(any());
        }
    }

    private Repository readOf(Repository.Visibility visibility, boolean isPrivate) {
        Repository read = new Repository();
        read.setId(REPOSITORY_ID);
        read.setNativeId(NATIVE_ID);
        read.setNameWithOwner(REPO_NAME);
        read.setProvider(provider);
        read.setVisibility(visibility);
        read.setPrivate(isPrivate);
        when(repositorySyncService.syncRepository(eq(SCOPE_ID), eq(REPO_NAME), any(), any()))
                .thenReturn(Optional.of(read));
        return read;
    }

    private static ListedCollaborator listed(long databaseId) {
        return new ListedCollaborator(
                new GitHubUserDTO(null, databaseId, "login" + databaseId, null, null, null, null, User.Type.USER),
                RepositoryCollaborator.Permission.READ);
    }

    /**
     * A private repository's listing proves this monitor's readers with the read begun just before the listing, and is
     * recorded before the shared mirror changes: a removal in the mirror moves every monitor's generation on.
     */
    @Test
    void shouldProveThePrivateRepositorysReadersBeforeTheMirrorChanges() {
        readOf(Repository.Visibility.PRIVATE, true);
        ReadersRead listingRead = new ReadersRead(SYNC_TARGET_ID, NATIVE_ID, PROVIDER_ID, 9L, Instant.EPOCH);
        when(syncTargetProvider.beginReadersProof(eq(SYNC_TARGET_ID), eq(NATIVE_ID), eq(PROVIDER_ID), any()))
                .thenReturn(new ReadersRead(SYNC_TARGET_ID, NATIVE_ID, PROVIDER_ID, 8L, Instant.EPOCH), listingRead);
        when(syncTargetProvider.readersListingDue(eq(SYNC_TARGET_ID), any(), any()))
                .thenReturn(true);
        CollaboratorListing listing =
                new CollaboratorListing(CollaboratorListing.Outcome.COMPLETE, List.of(listed(11L), listed(12L)));
        when(collaboratorSyncService.listCollaborators(SCOPE_ID, REPOSITORY_ID, NATIVE_ID))
                .thenReturn(listing);

        service.syncSyncTarget(syncTarget(null, null));

        InOrder order = inOrder(syncTargetProvider, collaboratorSyncService);
        order.verify(collaboratorSyncService).listCollaborators(SCOPE_ID, REPOSITORY_ID, NATIVE_ID);
        order.verify(syncTargetProvider)
                .recordRepositoryReaders(listingRead, new ReaderListing.Listed(Set.of(11L, 12L)));
        order.verify(collaboratorSyncService).storeCollaborators(REPOSITORY_ID, listing);
    }

    @ParameterizedTest(name = "{0} waits with {1}")
    @CsvSource({"RATE_LIMITED,RATE_LIMITED", "INCOMPLETE,LISTING_INCOMPLETE"})
    void shouldLetTheReadersWaitWhenTheListingStops(CollaboratorListing.Outcome outcome, ProofReason reason) {
        readOf(Repository.Visibility.PRIVATE, true);
        ReadersRead read = new ReadersRead(SYNC_TARGET_ID, NATIVE_ID, PROVIDER_ID, 3L, Instant.EPOCH);
        when(syncTargetProvider.beginReadersProof(eq(SYNC_TARGET_ID), eq(NATIVE_ID), eq(PROVIDER_ID), any()))
                .thenReturn(read);
        when(syncTargetProvider.readersListingDue(eq(SYNC_TARGET_ID), any(), any()))
                .thenReturn(true);
        when(collaboratorSyncService.listCollaborators(SCOPE_ID, REPOSITORY_ID, NATIVE_ID))
                .thenReturn(new CollaboratorListing(outcome, List.of(listed(11L))));

        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider).recordRepositoryReaders(read, new ReaderListing.Stopped(reason));
        verify(syncTargetProvider, never()).recordRepositoryReaders(eq(read), any(ReaderListing.Listed.class));
    }

    /** Access lost between the metadata read and the listing is a definitive answer, not an incomplete listing. */
    @Test
    void shouldRecordTheRepositoryUnavailableWhenTheListingIsRefused() {
        readOf(Repository.Visibility.PRIVATE, true);
        when(syncTargetProvider.readersListingDue(eq(SYNC_TARGET_ID), any(), any()))
                .thenReturn(true);
        when(collaboratorSyncService.listCollaborators(SCOPE_ID, REPOSITORY_ID, NATIVE_ID))
                .thenReturn(new CollaboratorListing(CollaboratorListing.Outcome.REPOSITORY_MISSING, List.of()));

        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider).recordRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID);
        verify(syncTargetProvider, never()).recordRepositoryReaders(any(), any());
    }

    /** Enterprise members outside the organization read an internal repository, and no listing names them. */
    @Test
    void shouldNeverProveTheReadersOfAnInternalRepository() {
        readOf(Repository.Visibility.INTERNAL, true);
        ReadersRead read = new ReadersRead(SYNC_TARGET_ID, NATIVE_ID, PROVIDER_ID, 3L, Instant.EPOCH);
        when(syncTargetProvider.beginReadersProof(eq(SYNC_TARGET_ID), eq(NATIVE_ID), eq(PROVIDER_ID), any()))
                .thenReturn(read);
        when(syncTargetProvider.readersListingDue(eq(SYNC_TARGET_ID), any(), any()))
                .thenReturn(true);
        when(collaboratorSyncService.listCollaborators(SCOPE_ID, REPOSITORY_ID, NATIVE_ID))
                .thenReturn(new CollaboratorListing(CollaboratorListing.Outcome.COMPLETE, List.of(listed(11L))));

        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider).recordRepositoryReaders(read, new ReaderListing.NotListable());
        verify(syncTargetProvider, never()).recordRepositoryReaders(any(), any(ReaderListing.Listed.class));
    }

    @Test
    void shouldNotListTheReadersOfAPublicRepository() {
        readOf(Repository.Visibility.PUBLIC, false);

        service.syncSyncTarget(syncTarget(null, null));

        verify(syncTargetProvider, never()).readersListingDue(any(), any(), any());
        verify(syncTargetProvider, never()).recordRepositoryReaders(any(), any());
        verify(collaboratorSyncService, never()).listCollaborators(any(), any(), anyLong());
    }

    @Test
    void shouldSyncIssuesAndPullRequestsWhenRepositoryUpdatedAtUnchanged() {
        // A repo that has completed its initial sync and whose updatedAt has not moved — the state in
        // which a short-circuit on updatedAt would return early and drop new PRs.
        SyncTarget target = syncTarget(null, null);

        boolean result = service.syncSyncTarget(target);

        // The sub-syncs still run rather than being skipped as "repoUnchanged".
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), any(), any());
        assertThat(result).isTrue();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, null);
    }

    @Test
    void shouldRecordLedgerFailureWithoutMislabelingProviderSync() {
        var target = syncTarget(null, null);
        when(activityLedgerRepair.reconcileRepository(SCOPE_ID, REPOSITORY_ID))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertThat(service.syncSyncTarget(target)).isFalse();
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.RECENT, "Activity ledger repair failed");
    }

    @Test
    void shouldRecordIncompleteIssueSyncAndClearAfterRecovery() {
        SyncTarget target = syncTarget(null, null);
        when(issueSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(
                        new RecentSyncResult(SyncResult.abortedError(1), null),
                        new RecentSyncResult(SyncResult.completed(3), null));

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
        verify(issueSyncService, times(2)).syncForRepository(any(), any(), any(), any());
    }

    @Test
    void shouldNotResumeIncrementalSyncFromBackfillCursor() {
        // Mid-backfill state: the backfill's cursor columns hold CREATED_AT-ordered checkpoints.
        SyncTarget target = syncTarget("issue-cursor-created-at-desc", "pr-cursor-created-at-desc");

        service.syncSyncTarget(target);

        // The UPDATED_AT-ordered recent pass starts at the newest item instead of a cursor of another ordering.
        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), isNull(), any());
        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), isNull(), any());
        verify(syncTargetProvider, never()).updateSyncCursor(any(), any(), any());
    }

    @Test
    void shouldOpenARecentWindowWhenAPassStopsAtThePageCap() {
        SyncTarget target = syncTarget(null, null);
        when(issueSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedError(25_000), "cursor-at-cap"));
        Instant before = Instant.now();

        service.syncSyncTarget(target);

        var window = ArgumentCaptor.forClass(SyncTargetProvider.RecentWindow.class);
        verify(syncTargetProvider).updateRecentWindow(eq(SYNC_TARGET_ID), eq(SyncCursorKind.ISSUE), window.capture());
        assertThat(window.getValue().resumeCursor()).isEqualTo("cursor-at-cap");
        assertThat(window.getValue().startedAt()).isBetween(before, Instant.now());
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(SYNC_TARGET_ID), eq(SyncType.ISSUES), any());
    }

    @Test
    void shouldResumeAnOpenWindowAndKeepItsStartWhenThePassStopsAtTheCapAgain() {
        Instant opened = Instant.parse("2026-11-20T03:00:00Z");
        SyncTarget target = recentlySynced()
                .pullRequestRecentWindow(new SyncTargetProvider.RecentWindow("cursor-1", opened))
                .build();
        when(pullRequestSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedError(10_000), "cursor-2"));

        service.syncSyncTarget(target);

        verify(pullRequestSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), eq("cursor-1"), any());
        verify(syncTargetProvider)
                .updateRecentWindow(
                        SYNC_TARGET_ID,
                        SyncCursorKind.PULL_REQUEST,
                        new SyncTargetProvider.RecentWindow("cursor-2", opened));
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(SYNC_TARGET_ID), eq(SyncType.PULL_REQUESTS), any());
    }

    @Test
    void shouldMoveTheWatermarkToTheWindowStartAndCloseTheWindowOnceItIsRead() {
        Instant opened = Instant.parse("2026-11-20T03:00:00Z");
        SyncTarget target = recentlySynced()
                .issueRecentWindow(new SyncTargetProvider.RecentWindow("cursor-1", opened))
                .build();

        service.syncSyncTarget(target);

        verify(issueSyncService).syncForRepository(eq(SCOPE_ID), eq(REPOSITORY_ID), eq("cursor-1"), any());
        // The changes after the window opened are newer than its start, so the next run reads them from there.
        verify(syncTargetProvider).updateSyncTimestamp(SYNC_TARGET_ID, SyncType.ISSUES, opened);
        verify(syncTargetProvider).updateRecentWindow(SYNC_TARGET_ID, SyncCursorKind.ISSUE, null);
    }

    @Test
    void shouldCloseAnOpenWindowAndKeepTheWatermarkWhenAResumedPassFails() {
        Instant opened = Instant.parse("2026-11-20T03:00:00Z");
        SyncTarget target = recentlySynced()
                .issueRecentWindow(new SyncTargetProvider.RecentWindow("cursor-github-refuses", opened))
                .build();
        when(issueSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedError(0), null));

        service.syncSyncTarget(target);

        // The next run reads from the newest issue again instead of retrying a cursor GitHub refuses.
        verify(syncTargetProvider).updateRecentWindow(SYNC_TARGET_ID, SyncCursorKind.ISSUE, null);
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(SYNC_TARGET_ID), eq(SyncType.ISSUES), any());
    }

    @Test
    void shouldKeepAnOpenWindowWhenAResumedPassIsRateLimited() {
        Instant opened = Instant.parse("2026-11-20T03:00:00Z");
        SyncTarget target = recentlySynced()
                .issueRecentWindow(new SyncTargetProvider.RecentWindow("cursor-1", opened))
                .build();
        when(issueSyncService.syncForRepository(any(), any(), any(), any()))
                .thenReturn(new RecentSyncResult(SyncResult.abortedRateLimit(40), null));

        service.syncSyncTarget(target);

        verify(syncTargetProvider, never()).updateRecentWindow(eq(SYNC_TARGET_ID), eq(SyncCursorKind.ISSUE), any());
        verify(syncTargetProvider, never()).updateSyncTimestamp(eq(SYNC_TARGET_ID), eq(SyncType.ISSUES), any());
    }
}
