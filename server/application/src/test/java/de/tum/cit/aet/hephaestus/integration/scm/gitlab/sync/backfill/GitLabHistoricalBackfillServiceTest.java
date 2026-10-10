package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider.SyncContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The cooldown contract behind {@code GitLabIntegrationSyncRunner}'s single-pass backfill: a
 * repository that did work is parked for {@code COOLDOWN_NORMAL} (5 minutes), so a second pass
 * launched back-to-back can only skip exactly what the first one advanced. That is why the runner
 * does one pass per job instead of looping.
 */
@Tag("unit")
class GitLabHistoricalBackfillServiceTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 100L;
    private static final Long SYNC_TARGET_ID = 7L;
    private static final String REPO = "acme/widgets";

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider;

    @Mock
    private GitLabSyncServiceHolder syncServiceHolder;

    @Mock
    private GitLabIssueSyncService issueSyncService;

    @Mock
    private SyncExecutionHandle handle;

    @Mock
    private ActivityLedgerRepair ledger;

    @Mock
    private PullRequestRepository pullRequests;

    @Mock
    private GitLabMergeRequestSyncService mergeRequests;

    private GitLabHistoricalBackfillService service;

    @BeforeEach
    void setUp() {
        service = new GitLabHistoricalBackfillService(
                syncTargetProvider,
                repositoryRepository,
                actorSelector,
                syncServiceHolderProvider,
                new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 3 * * *",
                        15,
                        new BackfillProperties(true, 50, 100, 60),
                        new FilterProperties(Set.of(), Set.of(), Set.of()),
                        null,
                        null),
                ledger,
                pullRequests);

        lenient().when(syncServiceHolderProvider.getIfAvailable()).thenReturn(syncServiceHolder);
        lenient().when(syncServiceHolder.getIssueSyncService()).thenReturn(issueSyncService);
        // Merge-request backfill is out of scope here; issues alone exercise the cooldown.
        lenient().when(syncServiceHolder.getMergeRequestSyncService()).thenReturn(null);

        Repository repository = new Repository();
        repository.setId(42L);
        repository.setNameWithOwner(REPO);
        lenient().when(actorSelector.connectedProviderId(SCOPE_ID)).thenReturn(Optional.of(20L));
        lenient()
                .when(repositoryRepository.findByNameWithOwnerAndProviderId(REPO, 20L))
                .thenReturn(Optional.of(repository));

        lenient()
                .when(syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB))
                .thenReturn(List.of(session()));
        lenient()
                .when(syncTargetProvider.getSyncSession(SCOPE_ID, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(session()));
    }

    @Test
    void shouldWarnInsteadOfReadingAnotherInstanceWhenThereIsNoConnectedProvider() {
        when(actorSelector.connectedProviderId(SCOPE_ID)).thenReturn(Optional.empty());
        service.repairCompletedRepositories(SCOPE_ID, handle);
        verify(handle).reportWarnings();
        verify(ledger, never()).reconcileRepository(anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"79,100,true", "80,100,false", "99,100,false", "0,19,false"})
    void shouldRestartCompletedGitLabHistoryOnlyForAMaterialProviderGap(long stored, int total, boolean restart) {
        var target = completedTarget(7L);
        prepareAdminRepair(List.of(target));
        when(pullRequests.countStoredByRepositoryId(42L)).thenReturn(stored);
        when(mergeRequests.countMergeRequests(SCOPE_ID, REPO)).thenReturn(total);
        service.repairCompletedRepositories(SCOPE_ID, handle);
        verify(ledger).reconcileRepository(SCOPE_ID, 42L);
        verify(syncTargetProvider, times(restart ? 1 : 0)).restartCompletedBackfill(SCOPE_ID, 7L, total, stored);
    }

    @Test
    void shouldWarnAndContinueGitLabAdminRepairAfterOneRepositoryFails() {
        var good = SyncTargetTestBuilder.syncTarget()
                .id(8L)
                .scopeId(SCOPE_ID)
                .repositoryNameWithOwner("acme/other")
                .pullRequestBackfillHighWaterMark(1000)
                .pullRequestBackfillCheckpoint(0)
                .build();
        prepareAdminRepair(List.of(completedTarget(7L), good));
        var other = new Repository();
        other.setId(43L);
        when(repositoryRepository.findByNameWithOwnerAndProviderId("acme/other", 20L))
                .thenReturn(Optional.of(other));
        when(ledger.reconcileRepository(SCOPE_ID, 42L)).thenThrow(new IllegalStateException("Database unavailable"));
        when(mergeRequests.countMergeRequests(SCOPE_ID, "acme/other")).thenReturn(100);
        when(pullRequests.countStoredByRepositoryId(43L)).thenReturn(100L);
        service.repairCompletedRepositories(SCOPE_ID, handle);
        verify(handle).reportWarnings();
        verify(ledger).reconcileRepository(SCOPE_ID, 42L);
        verify(ledger).reconcileRepository(SCOPE_ID, 43L);
        verify(mergeRequests).countMergeRequests(SCOPE_ID, "acme/other");
    }

    private void prepareAdminRepair(List<SyncTarget> targets) {
        var repository = new Repository();
        repository.setId(42L);
        when(repositoryRepository.findByNameWithOwnerAndProviderId(REPO, 20L)).thenReturn(Optional.of(repository));
        when(syncTargetProvider.getSyncSession(SCOPE_ID, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new SyncSession(
                        SCOPE_ID,
                        "acme",
                        "Acme",
                        "acme",
                        null,
                        null,
                        targets,
                        new SyncContext(SCOPE_ID, "acme", "Acme", null))));
        when(syncServiceHolder.getMergeRequestSyncService()).thenReturn(mergeRequests);
    }

    private static SyncTarget completedTarget(long id) {
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(SCOPE_ID)
                .repositoryNameWithOwner(REPO)
                .pullRequestBackfillHighWaterMark(1000)
                .pullRequestBackfillCheckpoint(0)
                .build();
    }

    @Test
    void shouldPropagateUnreadableCredentialWhenScopedPassReadsItsSession() {
        CredentialUnreadableException unreadable =
                new CredentialUnreadableException(50, IntegrationKind.GITLAB, new IllegalStateException());
        when(syncTargetProvider.getSyncSession(SCOPE_ID, IntegrationKind.GITLAB))
                .thenThrow(unreadable);

        assertThatThrownBy(() -> service.runBackfillPass(SCOPE_ID, handle)).isSameAs(unreadable);
        verify(issueSyncService, never()).backfillIssues(any(), any(), any(), anyInt());
    }

    @Test
    void shouldSkipHistoricalFetchWhenRepositoryIsUnavailable() {
        when(syncTargetProvider.isRepositoryUnavailable(SCOPE_ID, SYNC_TARGET_ID))
                .thenReturn(true);
        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();
        verify(issueSyncService, never()).backfillIssues(any(), any(), any(), anyInt());
    }

    @Test
    void scheduledPassAfterAProductiveOneIsGatedByTheSuccessCooldown() {
        // One page of issues with more to come: the repository is nowhere near backfilled.
        when(issueSyncService.backfillIssues(eq(SCOPE_ID), any(), any(), anyInt()))
                .thenReturn(new BackfillBatchResult(25, 100, 124, "cursor-2", false, false));

        // No handle: this is the 60s scheduler tick, which the success cooldown exists to space out.
        int firstPass = service.runBackfillPass(SCOPE_ID, null);
        int secondPass = service.runBackfillPass(SCOPE_ID, null);

        assertThat(firstPass).isEqualTo(1);
        assertThat(secondPass).isZero();
        verify(issueSyncService, times(1)).backfillIssues(eq(SCOPE_ID), any(), any(), anyInt());
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, SyncPass.HISTORICAL_BACKFILL, null);
    }

    @Test
    void jobDrivenPassIgnoresTheSuccessCooldownSoAManualBackfillDrains() {
        // Same repository, same "more history remains" answer — but an administrator is driving.
        when(issueSyncService.backfillIssues(eq(SCOPE_ID), any(), any(), anyInt()))
                .thenReturn(new BackfillBatchResult(25, 100, 124, "cursor-2", false, false));

        int firstPass = service.runBackfillPass(SCOPE_ID, handle);
        int secondPass = service.runBackfillPass(SCOPE_ID, handle);

        // The success cooldown is tick-spacing for the scheduler, not a limit on a job the admin
        // asked for; otherwise "Run backfill" would stop after one page on GitLab and drain on GitHub.
        assertThat(firstPass).isEqualTo(1);
        assertThat(secondPass).isEqualTo(1);
        verify(issueSyncService, times(2)).backfillIssues(eq(SCOPE_ID), any(), any(), anyInt());
    }

    @Test
    void errorBackoffGatesTheJobDrivenPassToo() {
        // A vendor that just failed is backed off for everybody — an admin click cannot hammer it.
        when(issueSyncService.backfillIssues(eq(SCOPE_ID), any(), any(), anyInt()))
                .thenReturn(new BackfillBatchResult(0, 0, 0, null, false, true));

        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();
        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();

        verify(issueSyncService, times(1)).backfillIssues(eq(SCOPE_ID), any(), any(), anyInt());
        verify(syncTargetProvider)
                .updateSyncError(SYNC_TARGET_ID, SyncPass.HISTORICAL_BACKFILL, "Historical issue backfill aborted");
    }

    @Test
    void passThatDidNoWorkLeavesTheRepositoryEligibleForTheNextPass() {
        // No issues at all: didWork stays false, so no COOLDOWN_NORMAL is parked on the target.
        when(issueSyncService.backfillIssues(eq(SCOPE_ID), any(), any(), anyInt()))
                .thenReturn(BackfillBatchResult.empty());

        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();
        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();

        // Not cooled down — the gate is on work performed, not on having been visited.
        verify(issueSyncService, times(2)).backfillIssues(eq(SCOPE_ID), any(), any(), anyInt());
    }

    @Test
    void cancellationBetweenRepositoriesStopsThePass() {
        when(handle.isCancellationRequested()).thenReturn(true);

        assertThat(service.runBackfillPass(SCOPE_ID, handle)).isZero();

        verify(issueSyncService, times(0)).backfillIssues(any(), any(), any(), anyInt());
    }

    private static SyncSession session() {
        return new SyncSession(
                SCOPE_ID,
                "acme",
                "Acme",
                "acme",
                null,
                null,
                List.of(target()),
                new SyncContext(SCOPE_ID, "acme", "Acme", null));
    }

    /** A target past initial sync with issue backfill still pending — the only shape that backfills. */
    private static SyncTarget target() {
        return SyncTargetTestBuilder.syncTarget()
                .id(SYNC_TARGET_ID)
                .scopeId(SCOPE_ID)
                .personalAccessToken("glpat-token")
                .authMode(AuthMode.PERSONAL_ACCESS_TOKEN)
                .repositoryNameWithOwner(REPO)
                .lastIssuesSyncedAt(Instant.now()) // initial sync done, so backfill is allowed
                .issueBackfillHighWaterMark(500) // initialized
                .issueBackfillCheckpoint(125) // still counting down, so not complete
                .issueSyncCursor("cursor-1")
                .build();
    }
}
