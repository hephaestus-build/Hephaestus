package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdmissionResponseDTO(
        int contractVersion,
        UUID reviewId,
        UUID jobId,
        int attempt,
        String admissionDigest,
        List<AdmittedResultDTO> admitted,
        List<Withheld> withheld,
        @Nullable PracticePageSupportDTO practicePageSupport,
        @Nullable ConversationEligibilityDTO conversationEligibility,
        @Nullable PrivateFeedbackHistoryDTO privateHistory,
        boolean publicReviewed,
        PrivateProgressDTO privateProgress) {
    /** The admission answer's own contract; the request and practice script contracts version separately. */
    public static final int CONTRACT_VERSION = 4;

    public AdmissionResponseDTO {
        admitted = List.copyOf(admitted);
        withheld = List.copyOf(withheld);
    }

    public record Withheld(String practiceSlug, String itemId, String reason) {}
}
