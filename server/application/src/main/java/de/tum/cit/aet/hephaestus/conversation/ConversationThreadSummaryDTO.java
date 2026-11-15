package de.tum.cit.aet.hephaestus.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "The thread on a top-level message")
public record ConversationThreadSummaryDTO(
        @Schema(description = "The thread's own conversation") @NonNull
        UUID conversationId,

        @Schema(description = "The message the thread replies to") @NonNull
        UUID parentEntryId,

        @Schema(
                description =
                        "Messages in the thread; can count a Hephaestus message without text whose feedback is no longer shown",
                requiredMode = RequiredMode.REQUIRED)
        long replyCount,

        @Nullable Instant lastReplyAt,

        @Schema(
                description = "Sequence number of the thread's newest shown message; 0 when it has none",
                requiredMode = RequiredMode.REQUIRED)
        long latestSeq) {

    static ConversationThreadSummaryDTO from(ConversationEntryRepository.ThreadSummary thread) {
        return new ConversationThreadSummaryDTO(
                thread.getConversationId(),
                thread.getParentEntryId(),
                thread.getReplies(),
                thread.getLastReplyAt(),
                thread.getLatestSeq());
    }
}
