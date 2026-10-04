package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.usage.fx.FxRateInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One calendar month of one workspace's LLM spend. See {@code package-info} for the two-purse model. */
@Schema(description = "One calendar month of a workspace's LLM spend, rolled up from the usage ledger")
public record WorkspaceLlmUsageReportDTO(
        @NonNull @Schema(description = "Calendar month (UTC), ISO yyyy-MM", example = "2026-07")
        String month,

        @Nullable
        @Schema(
                description = "Monthly cap in USD on spend the host pays for (shared models); null = uncapped. Set "
                        + "by instance admins only — a workspace admin can see it but not change it.")
        BigDecimal instanceMonthlyBudgetUsd,

        @Nullable
        @Schema(
                description = "Monthly cap in USD on spend this workspace pays for through its own connected "
                        + "provider; null = uncapped. Set by this workspace's own admins.")
        BigDecimal ownProviderMonthlyBudgetUsd,

        @NonNull
        @Schema(
                description = "This month's confirmed spend on shared (instance) models, in USD — the figure "
                        + "instanceMonthlyBudgetUsd compares against. A floor, not the full total, while "
                        + "unpricedEventCount is non-zero.")
        BigDecimal instanceTotalCostUsd,

        @NonNull
        @Schema(
                description = "This month's confirmed spend on this workspace's own connected provider(s), in USD — "
                        + "the figure ownProviderMonthlyBudgetUsd compares against. Different money from "
                        + "instanceTotalCostUsd: the two are never added together.")
        BigDecimal ownProviderTotalCostUsd,

        @NonNull
        @Schema(
                description = "Calls this month (either purse) whose price is not yet known. They are excluded from "
                        + "both totals above, so a non-zero value means the real spend may be higher than shown.")
        Long unpricedEventCount,

        @NonNull
        @Schema(
                description = "Whether host-funded (shared-model) spend is within its cap, has reached it, or cannot "
                        + "be confirmed because some shared-model usage has no price set.")
        LlmBudgetVerdict instanceBudgetVerdict,

        @NonNull
        @Schema(description = "The same verdict for spend on this workspace's own provider, against its own cap.")
        LlmBudgetVerdict ownProviderBudgetVerdict,

        @NonNull
        @Schema(
                description = "Whether work on SHARED models is currently paused for this workspace. Authoritative — "
                        + "it mirrors the live gate rather than being derivable from the verdict alone. Always false for a "
                        + "past month, which cannot pause anything.")
        Boolean instancePaused,

        @NonNull @Schema(description = "Whether work on this workspace's OWN provider is currently paused.")
        Boolean ownProviderPaused,

        @NonNull List<LlmUsageByJobTypeDTO> byJobType,
        @NonNull List<LlmUsageByDayDTO> byDay,

        @Nullable
        @Schema(
                description = "Display-only conversion when the instance has a display currency. "
                        + "Absent = show USD only.")
        FxRateInfoDTO fx) {}
