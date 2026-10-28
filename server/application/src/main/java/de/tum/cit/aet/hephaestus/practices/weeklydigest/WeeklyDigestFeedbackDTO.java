package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One piece of feedback that reached the developer in the week, and where it was delivered. Its words stay there. */
@Schema(description = "One piece of feedback that reached the developer in the week, and where it was delivered")
public record WeeklyDigestFeedbackDTO(
        @NonNull UUID feedbackId,
        @NonNull String practiceSlug,
        @NonNull String practiceName,
        @Nullable String groupSlug,
        @Nullable String groupName,

        @NonNull
        @Schema(
                description = "Where it was delivered: on the work (ON_WORK) or in the conversation with Hephaestus"
                        + " (CONVERSATION). Practice page feedback is never listed; the waiting count covers it")
        FeedbackDestination destination,

        @Nullable @Schema(description = "The item it was delivered on; only for ON_WORK, and never when withdrawn")
        ItemRefDTO item,

        @Nullable
        @Schema(description = "The Hephaestus message it appeared in; only for CONVERSATION, and never when withdrawn")
        UUID mentorEntryId,

        @Nullable
        @Schema(
                description = "The top-level message whose thread holds that Hephaestus message; only for CONVERSATION,"
                        + " and absent when the message is not in a thread")
        UUID mentorParentEntryId,

        @NonNull Instant deliveredAt,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description = "A workspace admin withdrew it; only the practice is shown")
        boolean withdrawn,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description = "Newer feedback replaced it since; the link opens the current feedback")
        boolean replaced) {}
