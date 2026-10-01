package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's practice-review policy.
 *
 * <p>Each knob is exposed twice: the <em>effective</em> value (override-or-fleet-default) drives the
 * control, and the raw <em>override</em> (null = inheriting) lets the UI mark inherited fields.
 */
@Schema(description = "A workspace's practice-review policy: effective values plus raw overrides")
public record PracticeReviewSettingsDTO(
        @NonNull @Schema(description = "Strong entity tag to send in If-Match when updating this rollout")
        String etag,

        @NonNull @Schema(description = "Monotonic rollout revision carried by automatically admitted review jobs")
        Long revision,

        @NonNull @Schema(description = "Effective: deliver feedback to merged PRs/MRs")
        Boolean deliverToMerged,

        @NonNull @Schema(description = "Effective: minimum minutes between reviews for the same PR")
        Integer cooldownMinutes,

        @Schema(description = "Raw override; null = inheriting the fleet default") @Nullable
        Boolean deliverToMergedOverride,

        @Schema(description = "Raw override; null = inheriting the fleet default") @Nullable
        Integer cooldownMinutesOverride,

        @NonNull
        @Schema(description = "Explicit all-or-selected repository and person coverage. Selected-empty means nobody.")
        WorkspaceReviewScope reviewScope,

        @NonNull @Schema(description = "Whether new external practice feedback may be sent")
        PracticeDeliveryStatus deliveryStatus,

        @NonNull PracticeReviewCoverageSummaryDTO coverageSummary,

        @NonNull
        @Schema(
                description = "Effective: how much autonomy the system has over practices and groups that hold no "
                        + "autonomy of their own — the bottom of the practice → group → workspace chain")
        PracticeAutonomy defaultAutonomy,

        @Schema(description = "Raw override; null = this workspace has never chosen, so HUMAN_APPROVAL applies")
        @Nullable
        PracticeAutonomy defaultAutonomyOverride,

        @NonNull
        @Schema(description = "Repository-root Ant-style generated-path patterns, keyed by monitored repository name")
        Map<String, List<String>> generatedPaths) {}
