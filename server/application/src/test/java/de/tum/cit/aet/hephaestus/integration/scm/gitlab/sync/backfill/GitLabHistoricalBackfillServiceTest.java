package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider.SyncContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The cooldown contract behind {@code GitlabIntegrationSyncRunner}'s single-pass backfill: a
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
    private OrganizationRepository organizationRepository;

    @Mock
    private ObjectProvider<GitLabSyncServiceHolder> syncServiceHolderProvider;

    @Mock
    private GitLabSyncServiceHolder syncServiceHolder;

    @Mock
    private GitLabIssueSyncService issueSyncService;

    @Mock
    private SyncExecutionHandle handle;

    private GitLabHistoricalBackfillService service;

    @BeforeEach
    void setUp() {
        service = new GitLabHistoricalBackfillService(
                syncTargetProvider,
                repositoryRepository,
                organizationRepository,
                syncServiceHolderProvider,
                new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 3 * * *",
                        15,
                        new BackfillProperties(true, 50, 100, 60),
                        new FilterProperties(Set.of(), Set.of(), Set.of()),
                        null,
                        null));

        lenient().when(syncServiceHolderProvider.getIfAvailable()).thenReturn(syncServiceHolder);
        lenient().when(syncServiceHolder.getIssueSyncService()).thenReturn(issueSyncService);
        // Merge-request backfill is out of scope here; issues alone exercise the cooldown.
        lenient().when(syncServiceHolder.getMergeRequestSyncService()).thenReturn(null);

        Repository repository = new Repository();
        repository.setNameWithOwner(REPO);
        // No GitLab organization row → no provider id → the service falls back to the unscoped lookup.
        lenient()
                .when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(
                        any(), eq(IdentityProviderType.GITLAB)))
                .thenReturn(Optional.empty());
        lenient().when(repositoryRepository.findByNameWithOwner(REPO)).thenReturn(Optional.of(repository));

        lenient()
                .when(syncTargetProvider.getSyncSessions(IntegrationKind.GITLAB))
                .thenReturn(List.of(session()));
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
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, null);
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
        verify(syncTargetProvider).updateSyncError(SYNC_TARGET_ID, "Historical issue backfill aborted");
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
