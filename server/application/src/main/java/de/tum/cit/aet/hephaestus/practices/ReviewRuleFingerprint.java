package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
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

    public static final String SCHEME = "v6:";

    private ReviewRuleFingerprint() {}

    public static boolean isCurrentScheme(@Nullable String fingerprint) {
        return fingerprint != null && fingerprint.startsWith(SCHEME);
    }

    public static String of(
            String slug,
            String name,
            ArtifactKind artifactKind,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            ActorRole subject,
            @Nullable PracticePrecondition precondition,
            String criteria,
            @Nullable PracticeJudgment judgment,
            @Nullable String precomputeScript,
            PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String groupSlug) {
        CanonicalDigest digest = new CanonicalDigest().add(slug).add(name).add(artifactKind.value());
        addAssessment(digest, evidenceRequirements, subject, precondition, judgment);
        return (SCHEME
                + digest.add(criteria)
                        .addNullable(precomputeScript)
                        .add(PracticeAutomatedReviewPolicyDigest.digest(automatedReviewPolicy))
                        .addNullable(groupSlug)
                        .hex());
    }

    static void addAssessment(
            CanonicalDigest digest,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            ActorRole subject,
            @Nullable PracticePrecondition precondition,
            @Nullable PracticeJudgment judgment) {
        digest.add(subject.name());
        digest.addInt(evidenceRequirements.size());
        evidenceRequirements.forEach(
                requirement -> digest.add(requirement.sourceKind().value())
                        .add(requirement.stance().name()));
        addPrecondition(digest, precondition);
        addJudgment(digest, judgment);
    }

    /** Everything the reviewer reads and everything that turns answers into an outcome, in authored order. */
    private static void addJudgment(CanonicalDigest digest, @Nullable PracticeJudgment judgment) {
        if (judgment == null) {
            return;
        }
        digest.add("judgment").addInt(judgment.questions().size());
        for (PracticeQuestion question : judgment.questions()) {
            digest.add(question.key())
                    .add(question.title())
                    .add(question.question())
                    .add(question.yes())
                    .add(question.no());
        }
        digest.addInt(judgment.rules().size());
        for (PracticeRule rule : judgment.rules()) {
            digest.add(rule.id()).addInt(rule.when().size());
            for (PracticeQuestion question : judgment.questions()) {
                QuestionAnswer required = rule.when().get(question.key());
                if (required != null) {
                    digest.add(question.key()).add(required.name());
                }
            }
            digest.add(rule.outcome().name())
                    .addNullable(
                            rule.severity() == null ? null : rule.severity().name())
                    .add(rule.reason());
        }
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
