package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateInfoDTO;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instance-admin side of LLM cost governance: the cross-tenant month rollup (spend totals only —
 * metadata, no tenant content) and the per-workspace budget cap write.
 *
 * <p>The {@link WorkspaceAgnostic} bypass is declared on {@link #getReport} alone, not on the class:
 * {@link #updateBudget} writes exactly one workspace's row, so it stays inside the tenancy inspector
 * where a stray cross-tenant write is still an error.
 */
@Service
public class LlmUsageAdminService {

    private final LlmUsageEventRepository usageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConfigAuditPort configAudit;
    private final AgentJobRepository jobRepository;

    /** Display-only; never an input to a cap comparison. */
    private final FxRateLookup fxRateLookup;

    public LlmUsageAdminService(
            LlmUsageEventRepository usageRepository,
            WorkspaceRepository workspaceRepository,
            ConfigAuditPort configAudit,
            AgentJobRepository jobRepository,
            FxRateLookup fxRateLookup) {
        this.usageRepository = usageRepository;
        this.workspaceRepository = workspaceRepository;
        this.configAudit = configAudit;
        this.jobRepository = jobRepository;
        this.fxRateLookup = fxRateLookup;
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Instance-admin spend rollup aggregates across all tenants (spend metadata only)")
    public AdminLlmUsageReportDTO getReport(YearMonth month) {
        LlmBudgetService.MonthWindow window = LlmBudgetService.MonthWindow.of(month);
        boolean isCurrentMonth = LlmUsageService.isCurrentMonth(month);
        FxRateInfoDTO fx = fxRateLookup.forMonth(month).orElse(null);
        List<AdminWorkspaceLlmUsageDTO> workspaces =
                usageRepository.aggregateByWorkspace(window.from(), window.to()).stream()
                        .map(row -> toRollup(row, month, isCurrentMonth))
                        .toList();
        return new AdminLlmUsageReportDTO(month.toString(), fx, workspaces);
    }

    /** Sets the host's cap on this workspace. The audit write joins this transaction. */
    @Transactional
    public void updateBudget(String workspaceSlug, @Nullable BigDecimal monthlyLlmBudgetUsd) {
        // Locked read: this cap and the workspace's own-provider cap are two columns of ONE row written
        // by two different people, and Hibernate's UPDATE covers every column — without the lock, two
        // concurrent patches each revert the other's cap.
        Workspace workspace = workspaceRepository
                .findByWorkspaceSlugForUpdate(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));
        BigDecimal before = workspace.getMonthlyLlmBudgetUsd();
        workspace.setMonthlyLlmBudgetUsd(monthlyLlmBudgetUsd);
        workspaceRepository.save(workspace);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_INSTANCE_LLM_BUDGET,
                workspace.getId(),
                workspace.getId(),
                new LlmBudgetSnapshot(before),
                new LlmBudgetSnapshot(monthlyLlmBudgetUsd)));
        // So a raised cap takes effect on the next poll rather than up to an hour later. A job released
        // under a lowered cap is simply re-held on its next claim attempt.
        jobRepository.releaseBudgetHolds(workspace.getId(), Instant.now());
    }

    private static AdminWorkspaceLlmUsageDTO toRollup(
            LlmUsageEventRepository.WorkspaceAggregate row, YearMonth month, boolean isCurrentMonth) {
        LlmBudgetVerdict instanceVerdict = LlmBudgetService.verdictFor(
                row.getPricedTotalCostUsd(), row.isHasUnpricedInstanceUsage(), row.getMonthlyBudgetUsd());
        LlmBudgetVerdict ownProviderVerdict = LlmBudgetService.verdictFor(
                row.getByoTotalCostUsd(), row.isHasUnpricedByoUsage(), row.getByoMonthlyBudgetUsd());
        return new AdminWorkspaceLlmUsageDTO(
                row.getWorkspaceSlug(),
                row.getDisplayName(),
                row.getMonthlyBudgetUsd(),
                row.getByoMonthlyBudgetUsd(),
                row.getPricedTotalCostUsd(),
                row.getByoTotalCostUsd(),
                LlmUsageService.ownProviderInUse(month, row.isOwnProviderConnected(), row.isHasByoUsage()),
                row.getEvents(),
                instanceVerdict,
                ownProviderVerdict,
                isCurrentMonth && instanceVerdict != LlmBudgetVerdict.WITHIN,
                isCurrentMonth && ownProviderVerdict != LlmBudgetVerdict.WITHIN);
    }

    /** {@code null} = uncapped. */
    public record LlmBudgetSnapshot(@Nullable BigDecimal monthlyLlmBudgetUsd) implements ConfigAuditSnapshot {}
}
