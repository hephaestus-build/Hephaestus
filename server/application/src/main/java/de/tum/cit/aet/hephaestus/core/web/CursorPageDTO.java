package de.tum.cit.aet.hephaestus.core.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One page of a long append-only list, which a client reads forward with an opaque cursor. A row
 * written between two reads cannot repeat or shift a row, and no read counts the list. A list that a
 * person pages through by number uses {@link OffsetPageDTO}. {@code scripts/check-api-pagination.ts}
 * refuses any other paginated response.
 */
@Schema(description = "One page of a list that a client reads forward with a cursor")
public record CursorPageDTO<T>(
        @NonNull List<T> content,

        @Schema(description = "Pass as cursor to read the next page; absent on the last page") @Nullable
        String nextCursor) {

    public CursorPageDTO {
        content = List.copyOf(content);
    }

    public static <T> CursorPageDTO<T> empty() {
        return new CursorPageDTO<>(List.of(), null);
    }
}
