package de.tum.cit.aet.hephaestus.core.web;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Stable wire representation for the APIs that expose flat page metadata. Spring Data's PageImpl
 * is an implementation detail, not a JSON contract; these fields preserve the existing clients'
 * response shape without relying on its bean properties. New APIs use Spring Data's PagedModel.
 */
public record PageResponseDTO<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size,
        int numberOfElements,
        boolean first,
        boolean last,
        boolean empty,
        SortDTO sort,
        PageableDTO pageable) {

    public static <T> PageResponseDTO<T> from(Page<T> page) {
        return new PageResponseDTO<>(
                List.copyOf(page.getContent()),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                page.getNumberOfElements(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty(),
                SortDTO.from(page.getSort()),
                PageableDTO.from(page.getPageable()));
    }

    public record SortDTO(boolean empty, boolean sorted, boolean unsorted) {
        static SortDTO from(Sort sort) {
            return new SortDTO(sort.isEmpty(), sort.isSorted(), sort.isUnsorted());
        }
    }

    public record PageableDTO(int pageNumber, int pageSize, long offset, boolean paged, boolean unpaged, SortDTO sort) {
        static PageableDTO from(Pageable pageable) {
            return new PageableDTO(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    pageable.getOffset(),
                    pageable.isPaged(),
                    pageable.isUnpaged(),
                    SortDTO.from(pageable.getSort()));
        }
    }
}
