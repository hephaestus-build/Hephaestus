package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** What a page of reviews refers to: the reviewed work, the event that started each review and who requested it. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewReferences {
    private final ProductReferenceQuery references;
    private final ItemTraceRepository events;
    private final PerPersonResults perPerson;

    /** Each kind in one query for the whole page. */
    PageRefs read(long ws, List<Review> page) {
        var items = references.items(
                ws, page.stream().map(Review::getItemId).distinct().toList());
        var eventIds = page.stream()
                .map(Review::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, ItemEventDTO> eventById = eventIds.isEmpty()
                ? Map.of()
                : events.events(ws, eventIds).stream()
                        .collect(Collectors.toMap(ItemTraceRepository.EventRow::getId, ReviewReferences::eventOf));
        var requesterIds = page.stream()
                .map(Review::getRequestedById)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // Who requested a review is usually whose work it is, so the name follows the per-person results setting.
        Map<Long, AccountRefDTO> requesters =
                requesterIds.isEmpty() || !perPerson.shown(ws) ? Map.of() : references.accounts(ws, requesterIds);
        return new PageRefs(items, eventById, requesters);
    }

    /** A requester missing from {@code requesters} is either unnamed by the setting or no longer known. */
    record PageRefs(Map<Long, ItemRefDTO> items, Map<UUID, ItemEventDTO> events, Map<Long, AccountRefDTO> requesters) {}

    private static ItemEventDTO eventOf(ItemTraceRepository.EventRow row) {
        return new ItemEventDTO(
                row.getId(),
                Long.toString(row.getItemId()),
                row.getName(),
                row.getItemVersion(),
                row.getOccurredAt(),
                row.getFoundBy(),
                row.getState(),
                row.getReason(),
                row.getDecidedAt(),
                row.getReviewId());
    }
}
