package de.tum.cit.aet.hephaestus.workspace.settings;

import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** A detached policy value uses the same reset and patch rules as its stored settings. */
public record PracticeReviewPolicy(
        @Nullable Boolean deliverToMerged,
        @Nullable Integer cooldownMinutes,
        ReviewScopeMode reviewScopeRepositories,
        ReviewScopeMode reviewScopePeople,
        PracticeDeliveryStatus deliveryStatus,
        long rolloutRevision,
        long configVersion,
        @Nullable String defaultFeedbackMode) {
    public boolean resolveDeliverToMerged(boolean fallback) {
        return PracticeReviewSettings.detached(this).resolveDeliverToMerged(fallback);
    }

    public int resolveCooldownMinutes(int fallback) {
        return PracticeReviewSettings.detached(this).resolveCooldownMinutes(fallback);
    }

    public PracticeReviewPolicy reset(@Nullable Set<PracticeReviewField> fields) {
        return edit(settings -> settings.reset(fields));
    }

    public PracticeReviewPolicy applyPatch(@Nullable Boolean deliver, @Nullable Integer cooldown) {
        return edit(settings -> settings.applyPatch(deliver, cooldown));
    }

    public PracticeReviewPolicy applyRollout(
            @Nullable ReviewScopeMode repositories,
            @Nullable ReviewScopeMode people,
            @Nullable PracticeDeliveryStatus delivery) {
        return edit(settings -> settings.applyRollout(repositories, people, delivery));
    }

    public PracticeReviewPolicy applyDefaultFeedbackMode(@Nullable String mode) {
        return edit(settings -> settings.applyDefaultFeedbackMode(mode));
    }

    private PracticeReviewPolicy edit(Consumer<PracticeReviewSettings> change) {
        var detached = PracticeReviewSettings.detached(this);
        change.accept(detached);
        return detached.snapshot();
    }
}
