package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly LLM budget cap evaluation over calendar months in UTC.
 *
 * <p>There are two purses, each judged against its own cap and never summed: the host's shared models
 * and the workspace's own provider. A capped purse whose month is UNVERIFIABLE — some event funded from
 * it has neither a confirmed cost nor an upper bound — is blocked exactly like an exhausted one, because a cap you
 * cannot verify is not a cap.
 *
 * <p>The ledger has one row for each model call. A call counts its upper bound from the moment it opens, and its
 * measured cost once it ends; a call whose usage was not measured keeps its bound ({@link
 * LlmUsageEventRepository#SPEND}). So {@link #decide} counts the calls of work that still runs, those at their
 * provider included.
 */
@Service
public class LlmBudgetService {

    private final LlmUsageEventRepository usageRepository;
    private final WorkspaceQueries workspaceQueries;

    public LlmBudgetService(LlmUsageEventRepository usageRepository, WorkspaceQueries workspaceQueries) {
        this.usageRepository = usageRepository;
        this.workspaceQueries = workspaceQueries;
    }

    @Transactional(readOnly = true)
    public LlmBudgetDecision decide(Long workspaceId) {
        return loadHeadroom(workspaceId).decide();
    }

    /** The same month-window reads as {@link #decide}, handed back as numbers. */
    @Transactional(readOnly = true)
    public LlmBudgetHeadroom headroom(Long workspaceId) {
        return loadHeadroom(workspaceId);
    }

    /**
     * The instance cap and the measured spend of the month against it ({@link LlmUsageEventRepository#CONFIRMED}),
     * without the upper bound of any open or unmeasured call.
     *
     * @return {@code null} when the instance purse has no cap
     */
    @Transactional(readOnly = true)
    public @Nullable MeasuredSpend measuredInstanceSpend(Long workspaceId) {
        BigDecimal budgetUsd = workspaceQueries
                .llmBudgets(workspaceId)
                .map(WorkspaceQueries.LlmBudgets::instanceBudgetUsd)
                .orElse(null);
        if (budgetUsd == null) return null;
        MonthWindow window = MonthWindow.of(YearMonth.now(ZoneOffset.UTC));
        return new MeasuredSpend(usageRepository.sumConfirmedCost(workspaceId, window.from(), window.to()), budgetUsd);
    }

    /** What a purse's measured calls cost this month, and its cap. */
    public record MeasuredSpend(BigDecimal spentUsd, BigDecimal budgetUsd) {}

    private LlmBudgetHeadroom loadHeadroom(Long workspaceId) {
        return workspaceQueries.llmBudgets(workspaceId).map(this::headroom).orElse(LlmBudgetHeadroom.UNCAPPED);
    }

    private LlmBudgetHeadroom headroom(WorkspaceQueries.LlmBudgets workspace) {
        return headroom(workspace.workspaceId(), workspace.instanceBudgetUsd(), workspace.ownProviderBudgetUsd());
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
     * UNVERIFIABLE, and unknown spend can only keep it exhausted.
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
