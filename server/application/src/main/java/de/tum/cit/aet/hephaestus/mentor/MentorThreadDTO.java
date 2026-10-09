package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A thread: the message it replies to and one page of its replies, oldest first")
public record MentorThreadDTO(
        @NonNull MentorEntryDTO parent,

        @Schema(description = "The thread's conversation; absent until the first reply opens it") @Nullable
        UUID conversationId,

        @NonNull List<MentorEntryDTO> entries,

        @Schema(description = "Pass as before to read the older replies; absent when there are none") @Nullable
        Long earlierBefore) {}
