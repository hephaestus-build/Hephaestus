package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateInfoDTO;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import java.math.BigDecimal;
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
    private final WorkspaceCommands workspaceCommands;
    private final ConfigAuditPort configAudit;
    private final ReviewRunKind reviewRuns;

    /** Display-only; never an input to a cap comparison. */
    private final FxRateLookup fxRateLookup;

    public LlmUsageAdminService(
            LlmUsageEventRepository usageRepository,
            WorkspaceCommands workspaceCommands,
            ConfigAuditPort configAudit,
            ReviewRunKind reviewRuns,
            FxRateLookup fxRateLookup) {
        this.usageRepository = usageRepository;
        this.workspaceCommands = workspaceCommands;
        this.configAudit = configAudit;
        this.reviewRuns = reviewRuns;
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
        var change = workspaceCommands.updateInstanceLlmBudget(workspaceSlug, monthlyLlmBudgetUsd);
        BigDecimal before = change.previousLimit();
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_INSTANCE_LLM_BUDGET,
                change.workspaceId(),
                change.workspaceId(),
                new LlmBudgetSnapshot(before),
                new LlmBudgetSnapshot(monthlyLlmBudgetUsd)));
        reviewRuns.releaseBudgetHolds(change.workspaceId());
    }

    private static AdminWorkspaceLlmUsageDTO toRollup(
            LlmUsageEventRepository.WorkspaceAggregate row, YearMonth month, boolean isCurrentMonth) {
        LlmBudgetVerdict instanceVerdict = LlmBudgetService.verdictFor(
                row.getPricedTotalCostUsd().add(row.getInstanceUpperBoundUsd()),
                row.isHasUnpricedInstanceUsage(),
                row.getMonthlyBudgetUsd());
        LlmBudgetVerdict ownProviderVerdict = LlmBudgetService.verdictFor(
                row.getByoTotalCostUsd().add(row.getOwnProviderUpperBoundUsd()),
                row.isHasUnpricedByoUsage(),
                row.getByoMonthlyBudgetUsd());
        return new AdminWorkspaceLlmUsageDTO(
                row.getWorkspaceSlug(),
                row.getDisplayName(),
                row.getMonthlyBudgetUsd(),
                row.getByoMonthlyBudgetUsd(),
                row.getPricedTotalCostUsd(),
                row.getByoTotalCostUsd(),
                new LlmUnmeasuredUsageDTO(row.getInstanceUnmeasuredCalls(), row.getInstanceUpperBoundUsd()),
                new LlmUnmeasuredUsageDTO(row.getOwnProviderUnmeasuredCalls(), row.getOwnProviderUpperBoundUsd()),
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
