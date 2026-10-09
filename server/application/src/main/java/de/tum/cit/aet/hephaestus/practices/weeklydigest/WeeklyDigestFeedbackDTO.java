package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One piece of feedback that reached the developer in the week, and where it landed. Its words stay there. */
@Schema(description = "One piece of feedback that reached the developer in the week, and where it landed")
public record WeeklyDigestFeedbackDTO(
        @NonNull UUID feedbackId,
        @NonNull String practiceSlug,
        @NonNull String practiceName,
        @Nullable String groupSlug,
        @Nullable String groupName,

        @NonNull
        @Schema(
                description = "Where it landed: on the reviewed work (IN_CONTEXT), on the Practice profile (IN_APP) or"
                        + " in the conversation with Heph (IN_CHAT)")
        FeedbackChannel channel,

        @Nullable @Schema(description = "The reviewed work it landed on; only for IN_CONTEXT")
        ReviewedWorkRefDTO work,

        @Nullable
        @Schema(description = "The card's title on the Practice profile; only for IN_APP, and never when withdrawn")
        String title,

        @Nullable @Schema(description = "The Heph message it appeared in; only for IN_CHAT")
        UUID mentorEntryId,

        @Nullable
        @Schema(
                description = "The top-level message whose thread holds that Heph message; only for IN_CHAT, and"
                        + " absent when the message is not in a thread")
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
