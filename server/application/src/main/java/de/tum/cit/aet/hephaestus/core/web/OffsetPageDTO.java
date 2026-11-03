package de.tum.cit.aet.hephaestus.core.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;

/**
 * One numbered page of a list that a person pages through by number or that shows its total. A long
 * append-only list uses {@link CursorPageDTO}. {@code scripts/check-api-pagination.ts} refuses any
 * other paginated response.
 */
@Schema(description = "One numbered page of a list")
public record OffsetPageDTO<T>(
        @NonNull List<T> content,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "This page's number, from 0")
        int page,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The most rows a page holds")
        int size,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Rows in the whole list")
        long totalElements,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Pages in the whole list")
        int totalPages) {

    public static <T> OffsetPageDTO<T> from(Page<T> page) {
        return new OffsetPageDTO<>(
                List.copyOf(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
