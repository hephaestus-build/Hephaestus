package de.tum.cit.aet.hephaestus.workspace.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/** PATCH null preserves an override; name the field in {@link #reset(java.util.Set)} to restore inheritance. */
@Embeddable
@Getter
@Setter
public class PracticeReviewSettings {

    /** Deliver feedback even to already-merged PRs/MRs. */
    @Column(name = "practice_deliver_to_merged")
    @Nullable
    private Boolean deliverToMerged;

    /** Minimum minutes between reviews for the same PR/config; 0 disables the cooldown. */
    @Column(name = "practice_cooldown_minutes")
    @Nullable
    private Integer cooldownMinutes;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ALL'")
    @Column(name = "review_scope_repositories", nullable = false, length = 24)
    private ReviewScopeMode reviewScopeRepositories = ReviewScopeMode.ALL;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ALL'")
    @Column(name = "review_scope_people", nullable = false, length = 24)
    private ReviewScopeMode reviewScopePeople = ReviewScopeMode.ALL;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ACTIVE'")
    @Column(name = "practice_delivery_status", nullable = false, length = 16)
    private PracticeDeliveryStatus deliveryStatus = PracticeDeliveryStatus.ACTIVE;

    @ColumnDefault("0")
    @Column(name = "practice_rollout_revision", nullable = false)
    private long rolloutRevision;

    @ColumnDefault("0")
    @Column(name = "practice_config_version", nullable = false)
    private long configVersion;

    // Kept as a String because the workspace module cannot depend on the practices module.
    @Column(name = "practice_default_feedback_mode", length = 16)
    @Nullable
    private String defaultFeedbackMode;

    public boolean resolveDeliverToMerged(boolean fallback) {
        return deliverToMerged != null ? deliverToMerged : fallback;
    }

    public int resolveCooldownMinutes(int fallback) {
        return cooldownMinutes != null ? cooldownMinutes : fallback;
    }

    /** PATCH semantics: only non-null fields overwrite; null leaves the current value untouched. */
    public void applyPatch(@Nullable Boolean deliverToMerged, @Nullable Integer cooldownMinutes) {
        if (deliverToMerged != null) this.deliverToMerged = deliverToMerged;
        if (cooldownMinutes != null) this.cooldownMinutes = cooldownMinutes;
    }

    public void applyRollout(
            @Nullable ReviewScopeMode repositoryMode,
            @Nullable ReviewScopeMode personMode,
            @Nullable PracticeDeliveryStatus deliveryStatus) {
        if (repositoryMode != null) this.reviewScopeRepositories = repositoryMode;
        if (personMode != null) this.reviewScopePeople = personMode;
        if (deliveryStatus != null) this.deliveryStatus = deliveryStatus;
    }

    public long incrementRolloutRevision() {
        return ++rolloutRevision;
    }

    public long incrementConfigVersion() {
        return ++configVersion;
    }

    /**
     * PATCH semantics, same as the scalars. Clearing back to the feedback mode default is a {@link
     * PracticeReviewField#DEFAULT_FEEDBACK_MODE} reset, not a null here — otherwise a client that
     * simply omitted the field would silently reset it.
     */
    public void applyDefaultFeedbackMode(@Nullable String tierName) {
        if (tierName != null) {
            this.defaultFeedbackMode = tierName;
        }
    }

    /** Clear the named fields back to {@code null} (inherit the fleet default). */
    public void reset(@Nullable Set<PracticeReviewField> fields) {
        if (fields == null) {
            return;
        }
        for (PracticeReviewField field : fields) {
            // Switch EXPRESSION, not statement: the compiler then forces every constant to be
            // handled
            // here. The yielded value is unused; the exhaustiveness check is the point.
            boolean ignored = switch (field) {
                case DELIVER_TO_MERGED -> {
                    this.deliverToMerged = null;
                    yield true;
                }
                case COOLDOWN_MINUTES -> {
                    this.cooldownMinutes = null;
                    yield true;
                }
                case REVIEW_SCOPE -> {
                    this.reviewScopeRepositories = ReviewScopeMode.ALL;
                    this.reviewScopePeople = ReviewScopeMode.ALL;
                    yield true;
                }
                case DEFAULT_FEEDBACK_MODE -> {
                    this.defaultFeedbackMode = null;
                    yield true;
                }
            };
        }
    }
}
