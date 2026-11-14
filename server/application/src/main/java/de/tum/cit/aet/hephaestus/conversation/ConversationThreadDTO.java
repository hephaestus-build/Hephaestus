package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A thread: the message it replies to and one page of its replies, oldest first")
public record ConversationThreadDTO(
        @NonNull ConversationEntryDTO parent,

        @Schema(description = "The thread's conversation; absent until the first reply opens it") @Nullable
        UUID conversationId,

        @NonNull CursorPageDTO<ConversationEntryDTO> replies) {}
