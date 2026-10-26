package de.tum.cit.aet.hephaestus.agent.handler.composition;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * The composition request of one review: the destinations it may write, their room, and the copy forms its item
 * takes. Derived from the review alone, so every attempt that composes it derives the same bytes.
 */
public final class FeedbackCompositionInputs {
    private FeedbackCompositionInputs() {}

    /** The request's JSON, or empty when the review composes nothing: a scan, a backfill, or no destination. */
    public static Optional<byte[]> request(
            ReviewKind kind,
            ReviewOrigin origin,
            Set<FeedbackDestination> destinations,
            Set<CopyCapability> copyCapabilities,
            ObjectMapper mapper) {
        if (kind == ReviewKind.SCAN || origin == ReviewOrigin.BACKFILL || destinations.isEmpty())
            return Optional.empty();
        boolean onWork = copyCapabilities.contains(CopyCapability.SUMMARY)
                || copyCapabilities.contains(CopyCapability.LINE_NOTE);
        var selected = new EnumMap<FeedbackDestination, DestinationBounds>(FeedbackDestination.class);
        for (var destination : destinations)
            if (destination != FeedbackDestination.ON_WORK || onWork)
                selected.put(destination, new DestinationBounds(1));
        if (selected.isEmpty()) return Optional.empty();
        var forms =
                selected.containsKey(FeedbackDestination.ON_WORK) && copyCapabilities.contains(CopyCapability.LINE_NOTE)
                        ? List.of(CopyForm.FILE_NOTE, CopyForm.LINE_NOTE)
                        : List.<CopyForm>of();
        return Optional.of(mapper.writeValueAsBytes(new Request(3, selected, 2, forms)));
    }

    private record DestinationBounds(int maxPieces) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record Request(
            int contractVersion,
            Map<FeedbackDestination, DestinationBounds> destinations,
            int minDistinctItems,
            List<CopyForm> onWorkCopyForms) {}
}
