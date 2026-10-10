package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly LLM budget cap evaluation over calendar months in UTC.
 *
 * <p>There are two purses, each judged against its own cap and never summed: the host's shared models
 * and the workspace's own provider. A capped purse whose month is UNVERIFIABLE — some event funded from
 * it has no resolvable price — is blocked exactly like an exhausted one, because a cap you cannot
 * verify is not a cap.
 *
 * <p>The ledger only gains a row when an agent job or mentor turn ENDS, so {@link #decide} is blind to
 * work running right now. A gate that must also bound a run already in progress reads {@link #headroom}
 * and adds the in-flight spend itself.
 */
@Service
public class LlmBudgetService {

    private static final Logger log = LoggerFactory.getLogger(LlmBudgetService.class);

    private final LlmUsageEventRepository usageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MeterRegistry meterRegistry;

    public LlmBudgetService(
            LlmUsageEventRepository usageRepository,
            WorkspaceRepository workspaceRepository,
            MeterRegistry meterRegistry) {
        this.usageRepository = usageRepository;
        this.workspaceRepository = workspaceRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The submission gate: logs and counts the refusal itself, so a caller that gets {@code true} back
     * simply skips the submission.
     */
    @Transactional(readOnly = true)
    public boolean blockSubmission(Workspace workspace, String jobType, @Nullable FundingSource fundingSource) {
        LlmBudgetDecision.Block block = headroom(workspace).decide().decideFor(fundingSource);
        if (!block.blocked()) {
            return false;
        }
        log.info(
                "Skipping agent job submission — {} monthly LLM budget {}: workspaceId={}, jobType={}",
                purseLabel(block.purse()),
                reasonLabel(block.reason()),
                workspace.getId(),
                jobType);
        meterRegistry
                .counter(
                        AgentMetrics.LLM_BUDGET_BLOCKED,
                        "surface",
                        "agent_job",
                        "cap",
                        FundingSource.capTag(block.purse()))
                .increment();
        return true;
    }

    private static String purseLabel(@Nullable FundingSource purse) {
        return purse == FundingSource.WORKSPACE ? "own-provider" : "shared-model";
    }

    private static String reasonLabel(LlmBudgetBlockReason reason) {
        return reason == LlmBudgetBlockReason.EXHAUSTED ? "exhausted" : "unverifiable (some spend has no price)";
    }

    @Transactional(readOnly = true)
    public LlmBudgetDecision decide(Long workspaceId) {
        return loadHeadroom(workspaceId).decide();
    }

    /**
     * The same month-window reads as {@link #decide}, handed back as numbers, so a caller that can see
     * spend the ledger cannot yet — the LLM proxy, mid-attempt — can add it before the cap comparison.
     */
    @Transactional(readOnly = true)
    public LlmBudgetHeadroom headroom(Long workspaceId) {
        return loadHeadroom(workspaceId);
    }

    private LlmBudgetHeadroom loadHeadroom(Long workspaceId) {
        return workspaceRepository.findById(workspaceId).map(this::headroom).orElse(LlmBudgetHeadroom.UNCAPPED);
    }

    private LlmBudgetHeadroom headroom(Workspace workspace) {
        return headroom(workspace.getId(), workspace.getMonthlyLlmBudgetUsd(), workspace.getMonthlyByoLlmBudgetUsd());
    }

    private LlmBudgetHeadroom headroom(
            Long workspaceId, @Nullable BigDecimal instanceBudgetUsd, @Nullable BigDecimal byoBudgetUsd) {
        MonthWindow window = MonthWindow.of(YearMonth.now(ZoneOffset.UTC));
        BigDecimal instanceSpent = spentIfCapped(
                instanceBudgetUsd, () -> usageRepository.sumCost(workspaceId, window.from(), window.to()));
        BigDecimal byoSpent =
                spentIfCapped(byoBudgetUsd, () -> usageRepository.sumByoCost(workspaceId, window.from(), window.to()));
        return new LlmBudgetHeadroom(
                instanceSpent,
                instanceBudgetUsd,
                probeUnpriced(
                        instanceSpent,
                        instanceBudgetUsd,
                        () -> usageRepository.existsUnpricedInstanceFunded(workspaceId, window.from(), window.to())),
                byoSpent,
                byoBudgetUsd,
                probeUnpriced(
                        byoSpent,
                        byoBudgetUsd,
                        () -> usageRepository.existsUnpricedWorkspaceFunded(workspaceId, window.from(), window.to())));
    }

    /** An uncapped purse is never blocked, so its month-window SUM is never run. */
    private static @Nullable BigDecimal spentIfCapped(@Nullable BigDecimal budgetUsd, Supplier<BigDecimal> pricedCost) {
        return budgetUsd == null ? null : pricedCost.get();
    }

    /**
     * A purse already exhausted on recorded spend alone skips the probe: EXHAUSTED outranks
     * UNVERIFIABLE, and adding in-flight spend can only keep it exhausted.
     */
    private static boolean probeUnpriced(
            @Nullable BigDecimal spentUsd, @Nullable BigDecimal budgetUsd, BooleanSupplier hasUnpriced) {
        if (spentUsd == null || budgetUsd == null || capReached(spentUsd, budgetUsd)) {
            return false;
        }
        return hasUnpriced.getAsBoolean();
    }

    /**
     * The cap comparison, written once so the live gate and the reported verdict can never disagree.
     * Inclusive: a cap of exactly 0 is therefore an immediate pause switch.
     */
    static boolean capReached(BigDecimal confirmedSpendUsd, @Nullable BigDecimal budgetUsd) {
        return budgetUsd != null && confirmedSpendUsd.compareTo(budgetUsd) >= 0;
    }

    /** The verdict for a purse whose spend the caller already has, rather than read here. */
    public static LlmBudgetVerdict verdictFor(
            BigDecimal confirmedSpendUsd, boolean hasUnpricedEvent, @Nullable BigDecimal monthlyBudgetUsd) {
        if (capReached(confirmedSpendUsd, monthlyBudgetUsd)) {
            return LlmBudgetVerdict.EXHAUSTED;
        }
        if (hasUnpricedEvent) {
            return LlmBudgetVerdict.UNVERIFIABLE;
        }
        return LlmBudgetVerdict.WITHIN;
    }

    /** Half-open UTC instant window [from, to) of one calendar month. */
    public record MonthWindow(Instant from, Instant to) {
        public static MonthWindow of(YearMonth month) {
            return new MonthWindow(
                    month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }
}
