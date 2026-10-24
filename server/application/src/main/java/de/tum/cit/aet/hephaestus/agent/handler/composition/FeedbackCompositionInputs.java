package de.tum.cit.aet.hephaestus.agent.handler.composition;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

public final class FeedbackCompositionInputs {
    private FeedbackCompositionInputs() {}

    public static void stage(
            Map<String, byte[]> files,
            ReviewKind kind,
            ReviewOrigin origin,
            Set<FeedbackDestination> destinations,
            Set<CopyCapability> copyCapabilities,
            ObjectMapper mapper) {
        if (kind == ReviewKind.SCAN || origin == ReviewOrigin.BACKFILL || destinations.isEmpty()) return;
        boolean onWork = copyCapabilities.contains(CopyCapability.SUMMARY)
                || copyCapabilities.contains(CopyCapability.LINE_NOTE);
        var selected = new EnumMap<FeedbackDestination, DestinationBounds>(FeedbackDestination.class);
        for (var destination : destinations)
            if (destination != FeedbackDestination.ON_WORK || onWork)
                selected.put(destination, new DestinationBounds(1));
        if (selected.isEmpty()) return;
        var forms =
                selected.containsKey(FeedbackDestination.ON_WORK) && copyCapabilities.contains(CopyCapability.LINE_NOTE)
                        ? List.of(CopyForm.FILE_NOTE, CopyForm.LINE_NOTE)
                        : List.<CopyForm>of();
        files.put(
                SandboxLayout.FEEDBACK_COMPOSITION_PATH, mapper.writeValueAsBytes(new Request(3, selected, 2, forms)));
    }

    /** Whether a staged request asks for a review on the work. */
    public static boolean requestsReviewOnTheWork(byte[] request, ObjectMapper mapper) {
        return mapper.readTree(request).path("destinations").has(FeedbackDestination.ON_WORK.name());
    }

    private record DestinationBounds(int maxPieces) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record Request(
            int contractVersion,
            Map<FeedbackDestination, DestinationBounds> destinations,
            int minDistinctItems,
            List<CopyForm> onWorkCopyForms) {}
}
