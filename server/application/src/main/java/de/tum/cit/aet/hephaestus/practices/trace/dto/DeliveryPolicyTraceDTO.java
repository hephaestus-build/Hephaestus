package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyCheck;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyCheckStatus;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluation;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyFactsSnapshot;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

public record DeliveryPolicyTraceDTO(
        @NonNull UUID reviewId,
        @NonNull Long admittedRevision,
        @Nullable Long evaluatedRevision,
        @NonNull String resolverVersion,
        @NonNull DeliveryPolicySurface surface,
        @NonNull DeliveryPolicyStage stage,
        @NonNull Boolean allowed,
        @Nullable FeedbackSuppressionReason decisiveReason,
        @NonNull List<DeliveryPolicyTraceCheckDTO> checks,
        @NonNull DeliveryPolicyFactsSnapshot facts,
        @NonNull Instant evaluatedAt) {
    public static DeliveryPolicyTraceDTO from(DeliveryPolicyEvaluation evaluation, ObjectMapper objectMapper) {
        return new DeliveryPolicyTraceDTO(
                evaluation.getAgentJobId(),
                evaluation.getAdmittedRevision(),
                evaluation.getEvaluatedRevision(),
                evaluation.getResolverVersion(),
                evaluation.getSurface(),
                evaluation.getStage(),
                evaluation.getAllowed(),
                evaluation.getDecisiveReason(),
                StreamSupport.stream(evaluation.getChecks().spliterator(), false)
                        .map(check -> new DeliveryPolicyTraceCheckDTO(
                                DeliveryPolicyCheck.valueOf(check.path("check").asString()),
                                DeliveryPolicyCheckStatus.valueOf(
                                        check.path("status").asString())))
                        .toList(),
                objectMapper.treeToValue(evaluation.getFacts(), DeliveryPolicyFactsSnapshot.class),
                evaluation.getEvaluatedAt());
    }
}
