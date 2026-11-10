package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace-scoped side of the LLM usage ledger: the month rollup a workspace admin sees, and its own
 * cap. The cross-tenant rollup and the host's cap live on {@link LlmUsageAdminService}.
 */
@Service
public class LlmUsageService {

    private final LlmUsageEventRepository usageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final LlmBudgetService llmBudgetService;

    private final ConfigAuditPort configAudit;
    private final ReviewRunKind reviewRuns;

    /** Display-only; no number it produces is ever compared against a budget. */
    private final FxRateLookup fxRateLookup;

    public LlmUsageService(
            LlmUsageEventRepository usageRepository,
            WorkspaceRepository workspaceRepository,
            LlmBudgetService llmBudgetService,
            ConfigAuditPort configAudit,
            ReviewRunKind reviewRuns,
            FxRateLookup fxRateLookup) {
        this.usageRepository = usageRepository;
        this.workspaceRepository = workspaceRepository;
        this.llmBudgetService = llmBudgetService;
        this.configAudit = configAudit;
        this.reviewRuns = reviewRuns;
        this.fxRateLookup = fxRateLookup;
    }

    @Transactional(readOnly = true)
    public WorkspaceLlmUsageReportDTO getWorkspaceReport(Long workspaceId, YearMonth month) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
        LlmBudgetService.MonthWindow window = LlmBudgetService.MonthWindow.of(month);

        List<LlmUsageByJobTypeDTO> byJobType =
                usageRepository.aggregateByJobType(workspaceId, window.from(), window.to()).stream()
                        .map(row -> new LlmUsageByJobTypeDTO(
                                row.getJobType(),
                                row.getPricedTotalCostUsd(),
                                row.getByoTotalCostUsd(),
                                row.getUnpricedEventCount(),
                                row.getInputTokens(),
                                row.getOutputTokens(),
                                row.getCacheReadTokens(),
                                row.getCacheWriteTokens(),
                                row.getTotalCalls(),
                                row.getEvents()))
                        .toList();

        List<LlmUsageByDayDTO> byDay = usageRepository.aggregateByDay(workspaceId, window.from(), window.to()).stream()
                .map(row -> new LlmUsageByDayDTO(
                        row.getDay(),
                        row.getPricedTotalCostUsd(),
                        row.getByoTotalCostUsd(),
                        row.getUnpricedEventCount(),
                        row.getEvents()))
                .toList();

        // One read, so a call that ends meanwhile is either confirmed or bounded, never both. The caps compare
        // confirmed spend plus the bounds; the report shows the bounds apart from the confirmed spend.
        LlmUsageEventRepository.MonthSpend spend =
                usageRepository.aggregateSpend(workspaceId, window.from(), window.to());
        BigDecimal pricedTotal = spend.getInstanceConfirmedUsd();
        BigDecimal ownProviderTotal = spend.getOwnProviderConfirmedUsd();
        BigDecimal instanceCapSpend = pricedTotal.add(spend.getInstanceUpperBoundUsd());
        BigDecimal ownProviderCapSpend = ownProviderTotal.add(spend.getOwnProviderUpperBoundUsd());
        BigDecimal instanceBudget = workspace.getMonthlyLlmBudgetUsd();
        BigDecimal ownProviderBudget = workspace.getMonthlyByoLlmBudgetUsd();
        long unpricedRuns = usageRepository.countUnpricedRuns(workspaceId, window.from(), window.to());
        LlmBudgetVerdict instanceVerdict = LlmBudgetService.verdictFor(
                instanceCapSpend,
                usageRepository.existsUnpricedInstanceFunded(workspaceId, window.from(), window.to()),
                instanceBudget);
        LlmBudgetVerdict ownProviderVerdict = LlmBudgetService.verdictFor(
                ownProviderCapSpend,
                usageRepository.existsUnpricedWorkspaceFunded(workspaceId, window.from(), window.to()),
                ownProviderBudget);
        LlmBudgetDecision decision = livePauseDecision(workspaceId, month);
        boolean ownProviderInUse = ownProviderInUse(
                month,
                usageRepository.isOwnProviderConnected(workspaceId),
                usageRepository.existsWorkspaceFunded(workspaceId, window.from(), window.to()));
        return new WorkspaceLlmUsageReportDTO(
                month.toString(),
                instanceBudget,
                ownProviderBudget,
                pricedTotal,
                ownProviderTotal,
                new LlmUnmeasuredUsageDTO(spend.getInstanceCalls(), spend.getInstanceUpperBoundUsd()),
                new LlmUnmeasuredUsageDTO(spend.getOwnProviderCalls(), spend.getOwnProviderUpperBoundUsd()),
                ownProviderInUse,
                unpricedRuns,
                instanceVerdict,
                ownProviderVerdict,
                decision.blocks(UsageFundingSource.INSTANCE),
                decision.blocks(UsageFundingSource.WORKSPACE),
                byJobType,
                byDay,
                fxRateLookup.forMonth(month).orElse(null));
    }

    /**
     * The mirror of {@code LlmUsageAdminService#updateBudget} for the purse the workspace itself pays:
     * same instrument, same audit trail, and it cannot reach the host's cap.
     */
    @Transactional
    public void updateOwnProviderBudget(Long workspaceId, @Nullable BigDecimal monthlyBudgetUsd) {
        // Locked read — see LlmUsageAdminService#updateBudget: the two caps share one workspace row,
        // and Hibernate's all-columns UPDATE would otherwise let this write revert the instance
        // admin's cap (or be reverted by it).
        Workspace workspace = workspaceRepository
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
        BigDecimal before = workspace.getMonthlyByoLlmBudgetUsd();
        workspace.setMonthlyByoLlmBudgetUsd(monthlyBudgetUsd);
        workspaceRepository.save(workspace);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_OWN_PROVIDER_LLM_BUDGET,
                workspaceId,
                workspaceId,
                new OwnProviderLlmBudgetSnapshot(before),
                new OwnProviderLlmBudgetSnapshot(monthlyBudgetUsd)));
        reviewRuns.releaseBudgetHolds(workspaceId);
    }

    /**
     * Whether a month's report shows the workspace's own-provider purse. The ledger answers for every
     * month: one own-provider row, priced or not, is use. For the current month, an own-provider model
     * that work can run on also counts, so its admins can cap that purse before the first run. A
     * connection has no history, so it says nothing about a past month.
     */
    static boolean ownProviderInUse(YearMonth month, boolean ownProviderConnected, boolean hasOwnProviderUsage) {
        return hasOwnProviderUsage || (ownProviderConnected && isCurrentMonth(month));
    }

    static boolean isCurrentMonth(YearMonth month) {
        return month.equals(YearMonth.now(ZoneOffset.UTC));
    }

    /** The live gate's verdict, which always evaluates against now — so a closed month pauses nothing. */
    private LlmBudgetDecision livePauseDecision(Long workspaceId, YearMonth month) {
        return isCurrentMonth(month) ? llmBudgetService.decide(workspaceId) : LlmBudgetDecision.ALLOWED;
    }

    /** {@code null} = uncapped. */
    public record OwnProviderLlmBudgetSnapshot(@Nullable BigDecimal monthlyBudgetUsd) implements ConfigAuditSnapshot {}
}
