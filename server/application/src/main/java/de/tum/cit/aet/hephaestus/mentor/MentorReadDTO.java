package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How far the developer read their conversation. The answer to marking it read, and the {@code read} event of the
 * live stream when another device moved it.
 */
@Schema(description = "How far the developer read their Heph conversation")
public record MentorReadDTO(
        @Schema(description = "The read marker; absent when the developer has no conversation yet") @Nullable
        Instant lastReadAt) {}
