package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Thread with full linear history (chronological) and the owner's votes on it, keyed by message id
 * ({@code true} = upvote), so a refresh restores the chat view as it was left.
 */
@Schema(description = "Mentor chat thread with all messages and the owner's votes on them.")
public record ChatThreadDetailDTO(
        UUID id, @Nullable String title, Instant createdAt, List<ChatMessageDTO> messages, Map<UUID, Boolean> votes) {}
