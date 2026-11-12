package de.tum.cit.aet.hephaestus.agent.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One workspace's row inside {@link AdminLlmUsageReportDTO}. */
@Schema(description = "One workspace's month of spend, as an instance admin sees it")
public record AdminWorkspaceLlmUsageDTO(
        @NonNull @Schema(description = "Addresses the workspace everywhere else in the API, including its cap")
        String workspaceSlug,

        @NonNull String displayName,

        @Nullable
        @Schema(
                description = "Monthly cap in USD on this workspace's spend on shared (instance) models — the money "
                        + "this instance pays for; null = uncapped. Yours to set.")
        BigDecimal instanceMonthlyBudgetUsd,

        @Nullable
        @Schema(
                description = "The workspace's own cap in USD on its own-provider spend; null = uncapped. Read-only "
                        + "here — it governs the workspace's money, so only its own admins may change it.")
        BigDecimal ownProviderMonthlyBudgetUsd,

        @NonNull
        @Schema(
                description = "This month's confirmed spend on shared (instance) models, in USD. "
                        + "instanceMonthlyBudgetUsd compares against it plus instanceUnmeasured.upperBoundUsd.")
        BigDecimal instanceTotalCostUsd,

        @NonNull
        @Schema(
                description = "This month's confirmed spend on the workspace's own connected provider(s), in USD. "
                        + "ownProviderMonthlyBudgetUsd compares against it plus ownProviderUnmeasured.upperBoundUsd.")
        BigDecimal ownProviderTotalCostUsd,

        @NonNull @Schema(description = "Shared-model calls whose usage was not measured, and the most they can cost.")
        LlmUnmeasuredUsageDTO instanceUnmeasured,

        @NonNull @Schema(description = "Own-provider calls whose usage was not measured, and the most they can cost.")
        LlmUnmeasuredUsageDTO ownProviderUnmeasured,

        @NonNull
        @Schema(
                description = "Whether this month has an own-provider purse to show for the workspace. Same rule as "
                        + "WorkspaceLlmUsageReport.ownProviderInUse.")
        Boolean ownProviderInUse,

        @NonNull
        @Schema(
                description =
                        "Runs this month, either purse: job attempts and mentor turn attempts. A practice script call"
                                + " counts toward the run of its review.")
        Long events,

        @NonNull
        @Schema(
                description = "Whether shared-model spend is within the instance cap, has reached it, or cannot be "
                        + "confirmed because shared-model rates are missing or usage measurements are unverified.")
        LlmBudgetVerdict instanceBudgetVerdict,

        @NonNull @Schema(description = "The same verdict for the workspace's own-provider spend against its own cap.")
        LlmBudgetVerdict ownProviderBudgetVerdict,

        @NonNull
        @Schema(
                description = "Whether work on SHARED models is paused for this workspace right now (current month "
                        + "only) — authoritative, mirroring the live gate.")
        Boolean instancePaused,

        @NonNull @Schema(description = "Whether work on the workspace's OWN provider is paused right now.")
        Boolean ownProviderPaused) {}
