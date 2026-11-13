package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPolicy;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshot of a workspace's practice-review policy overrides.
 *
 * <p>Every field is serialized even when null: null means "inherit the fleet default", so clearing
 * an override is a real change and must show in the diff rather than look like an absent key.
 */
record PracticeReviewSnapshot(
        @Nullable Boolean deliverToMerged,
        @Nullable Integer cooldownMinutes,
        @Nullable WorkspaceReviewScope reviewScope,
        String deliveryStatus,
        long revision,
        @Nullable String defaultFeedbackMode,
        Map<String, List<String>> generatedPaths)
        implements ConfigAuditSnapshot {
    boolean sameRolloutPolicyAs(PracticeReviewSnapshot other) {
        return (Objects.equals(deliverToMerged, other.deliverToMerged)
                && Objects.equals(reviewScope, other.reviewScope)
                && Objects.equals(deliveryStatus, other.deliveryStatus)
                && Objects.equals(defaultFeedbackMode, other.defaultFeedbackMode)
                && Objects.equals(generatedPaths, other.generatedPaths));
    }

    static PracticeReviewSnapshot of(
            PracticeReviewPolicy s, WorkspaceReviewScope scope, Map<String, List<String>> generatedPaths) {
        return new PracticeReviewSnapshot(
                s.deliverToMerged(),
                s.cooldownMinutes(),
                scope,
                s.deliveryStatus().name(),
                s.rolloutRevision(),
                s.defaultFeedbackMode(),
                generatedPaths);
    }
}
