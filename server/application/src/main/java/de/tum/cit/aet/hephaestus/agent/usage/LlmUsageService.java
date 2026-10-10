package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateLookup;
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
import java.time.ZoneOffset;
import java.util.Arrays;
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
    private final AgentJobRepository jobRepository;

    /** Display-only; no number it produces is ever compared against a budget. */
    private final FxRateLookup fxRateLookup;

    public LlmUsageService(
            LlmUsageEventRepository usageRepository,
            WorkspaceRepository workspaceRepository,
            LlmBudgetService llmBudgetService,
            ConfigAuditPort configAudit,
            AgentJobRepository jobRepository,
            FxRateLookup fxRateLookup) {
        this.usageRepository = usageRepository;
        this.workspaceRepository = workspaceRepository;
        this.llmBudgetService = llmBudgetService;
        this.configAudit = configAudit;
        this.jobRepository = jobRepository;
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
                                LlmUsageJobType.valueOf(row.getJobType()),
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

        List<LlmUsageByPracticeDTO> byPractice =
                usageRepository.aggregatePrecomputeByPractice(workspaceId, window.from(), window.to()).stream()
                        .map(row -> new LlmUsageByPracticeDTO(
                                row.getPracticeSlug(),
                                row.getPracticeName(),
                                purposes(row.getModelKinds()),
                                row.getReviews(),
                                row.getCalls(),
                                row.getInputTokens(),
                                row.getOutputTokens(),
                                row.getInstanceTotalCostUsd(),
                                row.getOwnProviderTotalCostUsd(),
                                row.getUnpricedEventCount()))
                        .toList();
        LlmUsageEventRepository.PrecomputeTotalAggregate precompute =
                usageRepository.aggregatePrecomputeTotal(workspaceId, window.from(), window.to());
        LlmUsagePrecomputeTotalDTO precomputeTotal = new LlmUsagePrecomputeTotalDTO(
                precompute.getReviews(),
                precompute.getCalls(),
                precompute.getInputTokens(),
                precompute.getOutputTokens(),
                precompute.getInstanceTotalCostUsd(),
                precompute.getOwnProviderTotalCostUsd(),
                precompute.getUnpricedEventCount());

        BigDecimal pricedTotal = usageRepository.sumCost(workspaceId, window.from(), window.to());
        BigDecimal ownProviderTotal = usageRepository.sumByoCost(workspaceId, window.from(), window.to());
        BigDecimal instanceBudget = workspace.getMonthlyLlmBudgetUsd();
        BigDecimal ownProviderBudget = workspace.getMonthlyByoLlmBudgetUsd();
        long unpricedRuns = usageRepository.countUnpricedRuns(workspaceId, window.from(), window.to());
        LlmBudgetVerdict instanceVerdict = LlmBudgetService.verdictFor(
                pricedTotal,
                usageRepository.existsUnpricedInstanceFunded(workspaceId, window.from(), window.to()),
                instanceBudget);
        LlmBudgetVerdict ownProviderVerdict = LlmBudgetService.verdictFor(
                ownProviderTotal,
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
                ownProviderInUse,
                unpricedRuns,
                instanceVerdict,
                ownProviderVerdict,
                decision.blocks(FundingSource.INSTANCE),
                decision.blocks(FundingSource.WORKSPACE),
                byJobType,
                byDay,
                byPractice,
                precomputeTotal,
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
        jobRepository.releaseBudgetHolds(workspaceId, Instant.now());
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

    /** Whether the month is the current UTC month, in which the budget gate acts. */
    static boolean isCurrentMonth(YearMonth month) {
        return month.equals(YearMonth.now(ZoneOffset.UTC));
    }

    /** The precompute purposes of the comma-separated model kinds, in their declared order. */
    private static List<AgentPurpose> purposes(String modelKinds) {
        List<ModelKind> kinds =
                Arrays.stream(modelKinds.split(",")).map(ModelKind::valueOf).toList();
        return AgentPurpose.precompute().stream()
                .filter(purpose -> kinds.contains(purpose.kind()))
                .toList();
    }

    /** The live gate's verdict, which always evaluates against now — so a closed month pauses nothing. */
    private LlmBudgetDecision livePauseDecision(Long workspaceId, YearMonth month) {
        return isCurrentMonth(month) ? llmBudgetService.decide(workspaceId) : LlmBudgetDecision.ALLOWED;
    }

    /** {@code null} = uncapped. */
    public record OwnProviderLlmBudgetSnapshot(@Nullable BigDecimal monthlyBudgetUsd) implements ConfigAuditSnapshot {}
}
