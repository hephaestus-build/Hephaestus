package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FeedbackQueryFilter(
        @Nullable List<FeedbackDeliveryState> deliveryStates,
        @Nullable List<FeedbackSuppressionReason> suppressionReasons,
        @Nullable List<FeedbackChannel> channels,
        @Nullable UUID agentJobId,
        @Nullable ArtifactKind artifactKind,
        @Nullable Long artifactId,
        @Nullable Long recipientUserId,
        @Nullable List<String> practiceSlugs,
        @Nullable Instant from,
        @Nullable Instant to) {
    public String @Nullable [] deliveryStateNames() {
        return names(deliveryStates);
    }

    public String @Nullable [] suppressionReasonNames() {
        return names(suppressionReasons);
    }

    public String @Nullable [] channelNames() {
        return names(channels);
    }

    public String @Nullable [] practiceSlugArray() {
        return practiceSlugs == null || practiceSlugs.isEmpty() ? null : practiceSlugs.toArray(String[]::new);
    }

    public @Nullable String artifactKindValue() {
        return artifactKind == null ? null : artifactKind.value();
    }

    private static String @Nullable [] names(@Nullable List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).toArray(String[]::new);
    }
}
