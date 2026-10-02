package de.tum.cit.aet.hephaestus.practices.feedback;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class DeliveryPolicyResolver {

    public static final String VERSION = "2";

    private DeliveryPolicyResolver() {}

    public enum FactAnswer {
        PASSES,
        DENIES,
        NOT_APPLICABLE;

        public static FactAnswer of(boolean passes) {
            return passes ? PASSES : DENIES;
        }
    }

    public static Result resolve(Facts facts) {
        List<Candidate> candidates = List.of(
                new Candidate(
                        DeliveryPolicyCheck.INSTANCE_SILENT_MODE,
                        facts.instanceMayDeliver(),
                        FeedbackSuppressionReason.INSTANCE_SILENCED),
                new Candidate(
                        DeliveryPolicyCheck.WORKSPACE_ENABLED,
                        FactAnswer.of(facts.workspaceEnabled()),
                        FeedbackSuppressionReason.WORKSPACE_DISABLED),
                new Candidate(
                        DeliveryPolicyCheck.ROLLOUT_REVISION,
                        facts.rolloutCurrent(),
                        FeedbackSuppressionReason.STALE_ROLLOUT_REVISION),
                new Candidate(
                        DeliveryPolicyCheck.WORKSPACE_DELIVERY,
                        facts.deliveryActive(),
                        FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED),
                new Candidate(
                        DeliveryPolicyCheck.CURRENT_COVERAGE,
                        facts.currentlyCovered(),
                        FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE),
                new Candidate(
                        DeliveryPolicyCheck.PRACTICE_AUTHORITY,
                        facts.practiceAuthority(),
                        FeedbackSuppressionReason.PRACTICE_REQUIRES_APPROVAL),
                new Candidate(
                        DeliveryPolicyCheck.RECIPIENT_CONSENT,
                        facts.recipientConsent(),
                        FeedbackSuppressionReason.RECIPIENT_OPTED_OUT),
                new Candidate(
                        DeliveryPolicyCheck.ARTIFACT_ELIGIBILITY,
                        facts.artifactEligible(),
                        facts.artifactRefusal() == null
                                ? FeedbackSuppressionReason.ARTIFACT_GONE
                                : facts.artifactRefusal()));

        List<CheckResult> checks = new ArrayList<>(candidates.size());
        FeedbackSuppressionReason refusal = null;
        for (Candidate candidate : candidates) {
            if (refusal != null) {
                checks.add(new CheckResult(candidate.check(), DeliveryPolicyCheckStatus.NOT_REACHED));
                continue;
            }
            if (candidate.answer() == FactAnswer.DENIES) {
                refusal = candidate.refusal();
            }
            checks.add(new CheckResult(candidate.check(), statusOf(candidate.answer())));
        }
        return new Result(refusal == null, refusal, List.copyOf(checks));
    }

    private static DeliveryPolicyCheckStatus statusOf(FactAnswer answer) {
        return switch (answer) {
            case PASSES -> DeliveryPolicyCheckStatus.PASSED;
            case DENIES -> DeliveryPolicyCheckStatus.DENIED;
            case NOT_APPLICABLE -> DeliveryPolicyCheckStatus.NOT_APPLICABLE;
        };
    }

    public record Facts(
            FactAnswer instanceMayDeliver,
            boolean workspaceEnabled,
            FactAnswer rolloutCurrent,
            FactAnswer deliveryActive,
            FactAnswer currentlyCovered,
            FactAnswer practiceAuthority,
            FactAnswer recipientConsent,
            FactAnswer artifactEligible,
            @Nullable FeedbackSuppressionReason artifactRefusal) {}

    public record CheckResult(DeliveryPolicyCheck check, DeliveryPolicyCheckStatus status) {}

    public record Result(
            boolean allowed, @Nullable FeedbackSuppressionReason suppressionReason, List<CheckResult> checks) {
        public FeedbackSuppressionReason refusal() {
            return java.util.Objects.requireNonNull(suppressionReason, "a denied result always names its reason");
        }
    }

    private record Candidate(DeliveryPolicyCheck check, FactAnswer answer, FeedbackSuppressionReason refusal) {}
}
