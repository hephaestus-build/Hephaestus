package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The authored standard and the occasion on which it is reviewed. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeDefinition(
        @NonNull String name,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SignalName> signals,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<PracticeEvidenceRequirement> evidenceRequirements,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = ReviewWhen.DESCRIPTION)
        Map<String, Set<String>> reviewWhen,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ActorRole subject,

        @Nullable PracticePrecondition precondition,
        @NonNull String criteria,

        @Schema(
                description = "Questions the review answers and the rules that decide the outcome; required exactly"
                        + " when the practice is reviewed automatically")
        @Nullable
        PracticeJudgment judgment,

        @Nullable String precomputeScript,
        @NonNull PracticeAutomatedReviewPolicy automatedReviewPolicy,
        @Nullable String whyItMatters,
        @Nullable String whatGoodLooksLike,
        @Nullable String groupSlug,
        @NonNull PracticeDeliveryBehavior deliveryBehavior)
        implements CatalogDefinition, ClosedPracticeInput {
    public static final int MAX_PRECOMPUTE_SCRIPT_LENGTH = 100_000;

    public PracticeDefinition {
        Objects.requireNonNull(name, "name");
        signals = canonicalSignals(signals);
        reviewWhen = ReviewWhen.canonical(Objects.requireNonNull(reviewWhen, "reviewWhen"));
        evidenceRequirements = List.copyOf(Objects.requireNonNull(evidenceRequirements, "evidenceRequirements").stream()
                .sorted(Comparator.comparing(
                        requirement -> requirement.sourceKind().value()))
                .toList());
        var sources = new HashSet<String>();
        for (PracticeEvidenceRequirement requirement : evidenceRequirements) {
            if (!sources.add(requirement.sourceKind().value())) {
                throw new IllegalArgumentException("An evidence source is listed twice. List each source once.");
            }
        }
        subject = subject == null ? ActorRole.AUTHOR : subject;
        Objects.requireNonNull(criteria, "criteria");
        Objects.requireNonNull(automatedReviewPolicy, "automatedReviewPolicy");
        deliveryBehavior = deliveryBehavior == null ? PracticeDeliveryBehavior.DEFAULT : deliveryBehavior;
        boolean automatedReviewDisabled =
                automatedReviewPolicy.automatedReview().mode() == PracticeAutomatedReviewMode.NONE;
        if (automatedReviewDisabled && !evidenceRequirements.isEmpty()) {
            throw new IllegalArgumentException("A practice without automated review cannot declare evidence");
        }
        if (!automatedReviewDisabled && evidenceRequirements.stream().noneMatch(PracticeEvidenceRequirement::refuses)) {
            throw new IllegalArgumentException("Automated review requires at least one required evidence source");
        }
        if (automatedReviewDisabled && judgment != null) {
            throw new IllegalArgumentException("A practice without automated review asks no questions");
        }
        // A practice withdrawn for insufficient evidence keeps the questions it would be reviewed with.
        if (automatedReviewPolicy.automatedReview().canAttemptAutomatedReview() && judgment == null) {
            throw new IllegalArgumentException(
                    "Automated review needs questions and rules that decide the outcome. Add at least one question.");
        }
        precomputeScript = blankToNull(precomputeScript);
        whyItMatters = blankToNull(whyItMatters);
        whatGoodLooksLike = blankToNull(whatGoodLooksLike);
    }

    public PracticeDefinition(
            String name,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            Map<String, Set<String>> reviewWhen,
            ActorRole subject,
            @Nullable PracticePrecondition precondition,
            String criteria,
            @Nullable PracticeJudgment judgment,
            @Nullable String precomputeScript,
            PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            @Nullable String groupSlug) {
        this(
                name,
                signals,
                evidenceRequirements,
                reviewWhen,
                subject,
                precondition,
                criteria,
                judgment,
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
                practice.getSignals(),
                practice.getEvidenceRequirements(),
                practice.getReviewWhen(),
                practice.getSubject(),
                practice.getPrecondition(),
                practice.getCriteria(),
                practice.getJudgment(),
                practice.getPrecomputeScript(),
                practice.getAutomatedReviewPolicy(),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                practice.getGroup() == null ? null : practice.getGroup().getSlug(),
                practice.getDeliveryBehavior());
    }

    /**
     * The definition a revision recorded, or null when the revision predates recording a complete one: a
     * missing field stays unknown rather than borrowing today's value. A reviewed practice's revision from before
     * judgments were recorded is incomplete in the same way.
     */
    public static @Nullable PracticeDefinition recordedBy(PracticeRevision revision) {
        if (revision.getSlug() == null
                || revision.getSignals() == null
                || revision.getEvidenceRequirements() == null
                || revision.getReviewWhen() == null
                || revision.getSubject() == null
                || revision.getAutomatedReviewPolicy() == null
                || (revision.getJudgment() == null
                        && revision.getAutomatedReviewPolicy().automatedReview().mode()
                                != PracticeAutomatedReviewMode.NONE)) {
            return null;
        }
        return new PracticeDefinition(
                revision.getName(),
                revision.getSignals(),
                revision.getEvidenceRequirements(),
                revision.getReviewWhen(),
                revision.getSubject(),
                revision.getPrecondition(),
                revision.getCriteria(),
                revision.getJudgment(),
                revision.getPrecomputeScript(),
                revision.getAutomatedReviewPolicy(),
                revision.getWhyItMatters(),
                revision.getWhatGoodLooksLike(),
                revision.getGroupSlug(),
                revision.getDeliveryBehavior());
    }

    /**
     * This definition as the shipped entry that withdrew it from automated review: a definition that asks for a
     * review takes the shipped policy and reason and loses what only a review reads. One that already declares
     * no automated review is never reviewed and stays as written, rather than gaining evidence it never read.
     */
    public PracticeDefinition withdrawnAs(PracticeDefinition shipped) {
        if (automatedReviewPolicy.automatedReview().mode() == PracticeAutomatedReviewMode.NONE) {
            return this;
        }
        boolean shippedWithdrawn =
                shipped.automatedReviewPolicy().automatedReview().mode() == PracticeAutomatedReviewMode.NONE;
        return new PracticeDefinition(
                name,
                signals,
                shippedWithdrawn ? List.of() : evidenceRequirements,
                reviewWhen,
                subject,
                null,
                criteria,
                shippedWithdrawn ? null : judgment,
                null,
                shipped.automatedReviewPolicy(),
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                deliveryBehavior);
    }

    public ArtifactKind artifactKind() {
        return signals.getFirst().artifactKind();
    }

    @Override
    public String provenanceFingerprint(String slug) {
        return ReviewRuleFingerprint.of(
                slug,
                name,
                artifactKind(),
                evidenceRequirements,
                subject,
                precondition,
                criteria,
                judgment,
                precomputeScript,
                automatedReviewPolicy,
                groupSlug);
    }

    @Override
    public String digest(String slug) {
        return PracticeDefinitionDigest.digest(slug, this);
    }

    public String exactFingerprint(String slug) {
        return "v1:" + digest(slug);
    }

    public static List<SignalName> canonicalSignals(List<SignalName> signals) {
        List<SignalName> sorted = Objects.requireNonNull(signals, "signals").stream()
                .sorted(Comparator.comparing(SignalName::value))
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one moment that starts a review.");
        }
        if (new HashSet<>(sorted).size() != sorted.size()) {
            throw new IllegalArgumentException("The same moment is chosen twice. Choose each moment once.");
        }
        ArtifactKind kind = sorted.getFirst().artifactKind();
        if (sorted.stream().anyMatch(signal -> !kind.equals(signal.artifactKind()))) {
            throw new IllegalArgumentException(
                    "A practice reviews one kind of work. Choose its moments from one kind of work only.");
        }
        return List.copyOf(sorted);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
