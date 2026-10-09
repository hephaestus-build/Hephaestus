package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One page of a conversation's messages, oldest first")
public record MentorEntryPageDTO(
        @NonNull List<MentorEntryDTO> entries,

        @Schema(description = "Pass as before to read the older messages; absent when there are none") @Nullable
        Long earlierBefore) {

    static final MentorEntryPageDTO EMPTY = new MentorEntryPageDTO(List.of(), null);
}
