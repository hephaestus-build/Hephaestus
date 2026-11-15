package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param model the model that composed the words, as its provider names it, for their provenance record; {@code null}
 *     when it is unknown
 */
public record FeedbackDraft(
        UUID id,
        long workspaceId,
        UUID reviewId,
        @Nullable Long itemId,
        long developerId,
        FeedbackDestination destination,
        FeedbackState state,
        @Nullable FeedbackStopReason stopReason,
        @Nullable String body,
        List<FeedbackBasisInput> basis,
        List<FeedbackCopyInput> copies,
        Map<FeedbackCheckStage, FeedbackChecks> checks,
        @Nullable FeedbackCompositionTrace composition,
        @Nullable Page page,
        @Nullable String model) {
    public record Page(String title, String body, String nextStep) {}

    public FeedbackDraft(
            UUID id,
            long workspaceId,
            UUID reviewId,
            @Nullable Long itemId,
            long developerId,
            FeedbackDestination destination,
            FeedbackState state,
            @Nullable FeedbackStopReason stopReason,
            @Nullable String body,
            List<FeedbackBasisInput> basis,
            List<FeedbackCopyInput> copies,
            Map<FeedbackCheckStage, FeedbackChecks> checks,
            @Nullable FeedbackCompositionTrace composition,
            @Nullable Page page) {
        this(
                id,
                workspaceId,
                reviewId,
                itemId,
                developerId,
                destination,
                state,
                stopReason,
                body,
                basis,
                copies,
                checks,
                composition,
                page,
                null);
    }

    public FeedbackDraft {
        if (page != null && (destination != FeedbackDestination.PRACTICE_PAGE || body != null))
            throw new IllegalArgumentException(
                    "Structured page feedback needs the practice-page destination and no rendered body.");
        basis = List.copyOf(basis);
        copies = List.copyOf(copies);
        checks = Map.copyOf(checks);
    }
}
