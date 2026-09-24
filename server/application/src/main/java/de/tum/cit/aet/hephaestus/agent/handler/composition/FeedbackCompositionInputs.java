package de.tum.cit.aet.hephaestus.agent.handler.composition;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stages composition bounds. Without the request file, the runner skips composition.
 * Callers select channels and placements supported by the reviewed work.
 */
public final class FeedbackCompositionInputs {

    public enum InContextPlacementKind {
        DIFF,
        ARTIFACT,
    }

    /** Per-run composition limits; delivery may impose additional per-recipient limits. */
    private static final Map<FeedbackChannel, Integer> MAX_UNITS =
            Map.of(FeedbackChannel.IN_CONTEXT, 3, FeedbackChannel.IN_APP, 2, FeedbackChannel.IN_CHAT, 3);

    /** Minimum distinct pieces of reviewed work required for a pattern claim. */
    public static final int MIN_DISTINCT_ARTIFACTS = 2;

    public static final Set<FeedbackChannel> EVENT_REVIEW_CHANNELS = EnumSet.allOf(FeedbackChannel.class);

    private FeedbackCompositionInputs() {}

    public static void stage(Map<String, byte[]> files, ObservationOrigin origin) {
        stage(files, origin, EVENT_REVIEW_CHANNELS, EnumSet.allOf(InContextPlacementKind.class));
    }

    public static void stage(Map<String, byte[]> files, ObservationOrigin origin, Set<FeedbackChannel> channels) {
        stage(files, origin, channels, EnumSet.allOf(InContextPlacementKind.class));
    }

    public static void stage(
            Map<String, byte[]> files,
            ObservationOrigin origin,
            Set<FeedbackChannel> channels,
            Set<InContextPlacementKind> inContextPlacements) {
        if (origin == ObservationOrigin.BACKFILL || channels.isEmpty()) {
            return;
        }
        if (channels.contains(FeedbackChannel.IN_CONTEXT) && inContextPlacements.isEmpty()) {
            throw new IllegalArgumentException("IN_CONTEXT requires at least one placement kind");
        }
        String request = """
            {
              "enabled": true,
              "minDistinctArtifacts": %d,
              "inContextPlacementKinds": [%s],
              "channels": {
            %s
              }
            }
            """.formatted(
                        MIN_DISTINCT_ARTIFACTS,
                        inContextPlacements.stream()
                                .map(kind -> '"' + kind.name() + '"')
                                .collect(Collectors.joining(", ")),
                        channelBounds(channels));
        files.put(SandboxLayout.FEEDBACK_COMPOSITION_PATH, request.getBytes(StandardCharsets.UTF_8));
    }

    private static String channelBounds(Set<FeedbackChannel> enabled) {
        return EnumSet.allOf(FeedbackChannel.class).stream()
                .map(channel -> "    \"%s\": { \"enabled\": %s, \"maxUnits\": %d }"
                        .formatted(channel.name(), enabled.contains(channel), MAX_UNITS.getOrDefault(channel, 1)))
                .collect(Collectors.joining(",\n"));
    }
}
