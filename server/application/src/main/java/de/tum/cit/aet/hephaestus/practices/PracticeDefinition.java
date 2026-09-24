package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A practice as its author wrote it.
 *
 * <p>{@code artifactKind} is not a field. It is read off {@link #bindings()}, whose signal names carry
 * it as a prefix, so there is nothing for a second statement of it to disagree with.
 */
public record PracticeDefinition(
        @NonNull String name,
        @NonNull List<PracticeBinding> bindings,
        @NonNull String criteria,
        @Nullable String precomputeScript,
        @NonNull PracticeAutomatedReviewPolicy automatedReviewPolicy,
        @Nullable String whyItMatters,
        @Nullable String whatGoodLooksLike,
        @Nullable String groupSlug,
        @NonNull PracticeDeliveryBehavior deliveryBehavior)
        implements CatalogDefinition {
    public static final int MAX_PRECOMPUTE_SCRIPT_LENGTH = 100_000;

    public PracticeDefinition {
        Objects.requireNonNull(name, "name");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        // Refused rather than defaulted: with no binding there is no artifact kind to read off.
        PracticeBinding.artifactKindOf(bindings);
        rejectDuplicateSignals(bindings);
        Objects.requireNonNull(criteria, "criteria");
        Objects.requireNonNull(automatedReviewPolicy, "automatedReviewPolicy");
        deliveryBehavior = deliveryBehavior == null ? PracticeDeliveryBehavior.DEFAULT : deliveryBehavior;
        boolean automatedReviewDisabled =
                automatedReviewPolicy.automatedReview().mode() == PracticeAutomatedReviewMode.NONE;
        for (PracticeBinding binding : bindings) {
            if (automatedReviewDisabled && !binding.needs().isEmpty()) {
                throw new IllegalArgumentException("A practice without automated review cannot declare evidence");
            }
            // Contextual sources alone would let a review run having read nothing it must read.
            if (!automatedReviewDisabled && binding.needs().stream().noneMatch(PracticeEvidenceRequirement::refuses)) {
                throw new IllegalArgumentException(
                        "Automated review requires at least one required evidence source per binding");
            }
        }
        precomputeScript = blankToNull(precomputeScript);
        whyItMatters = blankToNull(whyItMatters);
        whatGoodLooksLike = blankToNull(whatGoodLooksLike);
    }

    public PracticeDefinition(
            String name,
            List<PracticeBinding> bindings,
            String criteria,
            @Nullable String precomputeScript,
            PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            @Nullable String groupSlug) {
        this(
                name,
                bindings,
                criteria,
                precomputeScript,
                automatedReviewPolicy,
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                PracticeDeliveryBehavior.DEFAULT);
    }

    public static PracticeDefinition from(Practice practice) {
        return new PracticeDefinition(
                practice.getName(),
                practice.getBindings(),
                practice.getCriteria(),
                practice.getPrecomputeScript(),
                practice.getAutomatedReviewPolicy(),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                practice.getGroup() == null ? null : practice.getGroup().getSlug(),
                practice.getDeliveryBehavior());
    }

    public ArtifactKind artifactKind() {
        return PracticeBinding.artifactKindOf(bindings);
    }

    @Override
    public String provenanceFingerprint(String slug) {
        return ReviewRuleFingerprint.of(
                slug, name, bindings, criteria, precomputeScript, automatedReviewPolicy, groupSlug);
    }

    @Override
    public String digest(String slug) {
        return PracticeDefinitionDigest.digest(slug, this);
    }

    public String exactFingerprint(String slug) {
        return "v1:" + digest(slug);
    }

    /**
     * One signal, one binding. Two bindings on one signal would need merging by every reader, and the
     * candidate merges — union the evidence, or take the first — are not the same review.
     */
    private static void rejectDuplicateSignals(List<PracticeBinding> bindings) {
        java.util.Set<SignalName> seen = new java.util.HashSet<>();
        for (PracticeBinding binding : bindings) {
            for (SignalName signal : binding.signals()) {
                if (!seen.add(signal)) {
                    throw new IllegalArgumentException("Signal " + signal + " is bound twice");
                }
            }
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
