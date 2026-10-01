package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Fingerprints review-rule inputs, excluding why-it-matters and what-good-looks-like guidance.
 *
 * <p>Bump {@code SCHEME} whenever the set of digested <em>inputs</em> changes, so a stored fingerprint
 * is never silently compared against one computed from different facts. A bump is unnecessary only
 * while no released build has written a digest under the current scheme.
 */
public final class ReviewRuleFingerprint {

    private static final String SCHEME = "v5:";

    private ReviewRuleFingerprint() {}

    public static boolean isCurrentScheme(@Nullable String fingerprint) {
        return fingerprint != null && fingerprint.startsWith(SCHEME);
    }

    public static String of(
            String slug,
            String name,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            boolean onDrafts,
            ActorRole subject,
            @Nullable PracticePrecondition precondition,
            String criteria,
            @Nullable String precomputeScript,
            PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String groupSlug) {
        CanonicalDigest digest = new CanonicalDigest().add(slug).add(name);
        addOccasion(digest, signals, evidenceRequirements, onDrafts, subject, precondition);
        return (SCHEME
                + digest.add(criteria)
                        .addNullable(precomputeScript)
                        .add(PracticeAutomatedReviewPolicyDigest.digest(automatedReviewPolicy))
                        .addNullable(groupSlug)
                        .hex());
    }

    static void addOccasion(
            CanonicalDigest digest,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            boolean onDrafts,
            ActorRole subject,
            @Nullable PracticePrecondition precondition) {
        digest.addInt(signals.size());
        signals.forEach(signal -> digest.add(signal.value()));
        digest.add(String.valueOf(onDrafts)).add(subject.name());
        digest.addInt(evidenceRequirements.size());
        evidenceRequirements.forEach(
                requirement -> digest.add(requirement.sourceKind().value())
                        .add(requirement.stance().name()));
        addPrecondition(digest, precondition);
    }

    private static void addPrecondition(CanonicalDigest digest, @Nullable PracticePrecondition subject) {
        if (subject == null) {
            return;
        }
        digest.add("precondition")
                .add(subject.skipReason())
                .addInt(subject.anyOf().size());
        for (PracticePreconditionClause clause : subject.anyOf()) {
            digest.add(clause.aspect().name()).add(clause.describe());
        }
    }
}
