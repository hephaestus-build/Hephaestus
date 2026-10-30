package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The developer's Hephaestus conversation in a workspace at one moment: what runs now and how far each part reached. The
 * live stream sends it on every connect, so a client can tell which of its cached pages moved.
 */
@Schema(description = "The developer's Hephaestus conversation in a workspace at one moment")
public record MentorConversationDTO(
        @Schema(
                description =
                        "The main conversation; absent until the developer writes to Hephaestus or gets a weekly digest")
        @Nullable
        UUID id,

        @Schema(
                description = "Sequence number of the newest top-level message shown; 0 when there is none",
                requiredMode = RequiredMode.REQUIRED)
        long latestSeq,

        @Schema(description = "Queued and running turns in the conversation and its threads, oldest first") @NonNull
        List<MentorTurnDTO> activeTurns,

        @Schema(description = "The newest threads, latest activity first") @NonNull
        List<MentorThreadSummaryDTO> threads,

        @Schema(
                description =
                        "When Hephaestus's newest shown answer in the conversation or any of its threads was written; absent"
                                + " before the first. Compare it with the newest answer the client has seen.")
        @Nullable
        Instant lastAnswerAt,

        @Schema(
                description = "How far the developer read the conversation on any device; absent before the first"
                        + " read. Hephaestus has an unread answer when lastAnswerAt is later.")
        @Nullable
        Instant lastReadAt) {

    static final MentorConversationDTO NONE = new MentorConversationDTO(null, 0, List.of(), List.of(), null, null);
}
