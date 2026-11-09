package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * That the two writers cannot revert each other — the reason both reads are pessimistic — is a
 * property of two transactions racing for one row, so it is asserted where it can actually happen, in
 * {@link LlmBudgetCapConcurrencyIntegrationTest}. Here the locking read is simply the one that is
 * stubbed: a writer that dropped it would find no workspace and 404.
 */
class LlmBudgetCapWriteTest extends BaseUnitTest {

    private static final Long WORKSPACE_ID = 7L;
    private static final String SLUG = "acme";

    @Mock
    private LlmUsageEventRepository usageRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private LlmBudgetService llmBudgetService;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private ReviewRunKind reviewRuns;

    @Mock
    private FxRateLookup fxRateLookup;

    private LlmUsageAdminService adminService;
    private LlmUsageService workspaceService;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        adminService =
                new LlmUsageAdminService(usageRepository, workspaceRepository, configAudit, reviewRuns, fxRateLookup);
        workspaceService = new LlmUsageService(
                usageRepository, workspaceRepository, llmBudgetService, configAudit, reviewRuns, fxRateLookup);
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setWorkspaceSlug(SLUG);
    }

    @Test
    @DisplayName("the instance cap write sets the shared-model cap and frees the jobs held on it")
    void theInstanceCapWriteSetsTheSharedModelCapAndFreesTheJobsHeldOnIt() {
        when(workspaceRepository.findByWorkspaceSlugForUpdate(SLUG)).thenReturn(Optional.of(workspace));

        adminService.updateBudget(SLUG, new BigDecimal("250.00"));

        assertThat(workspace.getMonthlyLlmBudgetUsd()).isEqualByComparingTo("250.00");
        // The other purse is a different person's money and this write is not about it.
        assertThat(workspace.getMonthlyByoLlmBudgetUsd()).isNull();

        // The audit row must name WHICH purse moved and to what. Recording the own-provider entity
        // type here would attribute the host's spending decision to the workspace that pays its own
        // bills — the two writers are otherwise structurally identical.
        var entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_INSTANCE_LLM_BUDGET);
        assertThat(entry.getValue().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(entry.getValue().after())
                .isEqualTo(new LlmUsageAdminService.LlmBudgetSnapshot(new BigDecimal("250.00")));

        // Raising a cap must free the jobs held on it immediately, not up to an hour later — and only
        // for this workspace, or one admin's raise would un-hold every tenant's parked work.
        verify(reviewRuns).releaseBudgetHolds(WORKSPACE_ID);
    }

    @Test
    @DisplayName("the own-provider cap write sets the own-provider cap and frees the jobs held on it")
    void theOwnProviderCapWriteSetsTheOwnProviderCapAndFreesTheJobsHeldOnIt() {
        when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        workspaceService.updateOwnProviderBudget(WORKSPACE_ID, new BigDecimal("40.00"));

        assertThat(workspace.getMonthlyByoLlmBudgetUsd()).isEqualByComparingTo("40.00");
        assertThat(workspace.getMonthlyLlmBudgetUsd()).isNull();

        var entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_OWN_PROVIDER_LLM_BUDGET);
        assertThat(entry.getValue().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(entry.getValue().after())
                .isEqualTo(new LlmUsageService.OwnProviderLlmBudgetSnapshot(new BigDecimal("40.00")));

        verify(reviewRuns).releaseBudgetHolds(WORKSPACE_ID);
    }
}
