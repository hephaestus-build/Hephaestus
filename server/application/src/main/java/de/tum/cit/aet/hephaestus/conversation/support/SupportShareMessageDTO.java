package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.PrivateContent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** One message of a shared turn, exactly as the person saw it when they shared it. */
@PrivateContent
public record SupportShareMessageDTO(
        @NonNull UUID entryId,

        @Schema(description = "QUESTION: the person wrote it. ANSWER: Hephaestus wrote it.") @NonNull
        Author author,

        @NonNull String text,
        @NonNull Instant createdAt) {

    public enum Author {
        QUESTION,
        ANSWER
    }
}
