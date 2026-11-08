package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackCopyCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDeveloperDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackLocation;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackListReadQuery {
    private static final int PREVIEW_LENGTH = 240;
    private static final CopyFacts NO_COPIES = new CopyFacts(new FeedbackCopyCountsDTO(0, 0, 0, 0, 0, 0), false);
    private final FeedbackQueryRepository queries;
    private final FeedbackBasisRead basis;
    private final ProductReferenceQuery references;
    private final FeedbackListReadRepository rows;
    private final FeedbackVisibilityService visibility;
    private final ObjectMapper mapper;

    public Page<ReviewFeedbackDTO> list(long workspaceId, FeedbackQueryFilter filter, Pageable pageable) {
        var page = queries.page(
                workspaceId,
                mapper.writeValueAsString(filter),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        if (page.isEmpty()) return new PageImpl<>(List.of(), page.getPageable(), page.getTotalElements());
        var ids = page.getContent().stream().map(Feedback::getId).toList();
        var locations = queries.locations(workspaceId, ids).stream()
                .collect(Collectors.toMap(
                        FeedbackQueryRepository.LocationRow::getId, FeedbackQueryRepository.LocationRow::getLocation));
        var facts = facts(workspaceId, page.getContent());
        return page.map(row -> summary(row, Objects.requireNonNull(locations.get(row.getId())), facts));
    }

    public ReviewFeedbackDTO summary(Feedback row, FeedbackLocation location) {
        return summary(row, location, facts(row.getWorkspaceId(), List.of(row)));
    }

    private Facts facts(long workspaceId, List<Feedback> feedback) {
        var ids = feedback.stream().map(Feedback::getId).toList();
        var copies = rows.copyCounts(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(FeedbackListReadRepository.CopyCount::feedbackId));
        var copyFacts = copies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> copyFacts(entry.getValue())));
        var disagreed = rows.responseTimes(workspaceId, ids).stream()
                .filter(row -> row.resolution() == FeedbackResolution.DISAGREE)
                .collect(Collectors.toMap(
                        FeedbackListReadRepository.ResponseTime::feedbackId,
                        FeedbackListReadRepository.ResponseTime::createdAt));
        return new Facts(
                references.items(
                        workspaceId,
                        feedback.stream()
                                .map(Feedback::getItemId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList()),
                references.developers(
                        workspaceId,
                        feedback.stream()
                                .map(Feedback::getDeveloperId)
                                .distinct()
                                .toList()),
                basis.readAll(workspaceId, ids),
                copyFacts,
                visibility.withdrawnAt(workspaceId, ids).keySet(),
                disagreed);
    }

    private static CopyFacts copyFacts(List<FeedbackListReadRepository.CopyCount> rows) {
        var counts = new EnumMap<FeedbackCopyState, Integer>(FeedbackCopyState.class);
        rows.forEach(row -> counts.put(row.state(), Math.toIntExact(row.count())));
        return new CopyFacts(
                new FeedbackCopyCountsDTO(
                        counts.getOrDefault(FeedbackCopyState.PLANNED, 0),
                        counts.getOrDefault(FeedbackCopyState.WRITE_STARTED, 0),
                        counts.getOrDefault(FeedbackCopyState.POSTED, 0),
                        counts.getOrDefault(FeedbackCopyState.FAILED, 0),
                        counts.getOrDefault(FeedbackCopyState.WITHHELD, 0),
                        counts.getOrDefault(FeedbackCopyState.RETRACTED, 0)),
                rows.stream().anyMatch(row -> row.bodyPresent() != 0));
    }

    private static ReviewFeedbackDTO summary(Feedback row, FeedbackLocation location, Facts facts) {
        var id = row.getId();
        var selected = Objects.requireNonNull(facts.basis().get(id));
        var copies = facts.copies().getOrDefault(id, NO_COPIES);
        boolean privateDestination = row.getDestination() != FeedbackDestination.ON_WORK;
        boolean allowed = !privateDestination && selected.readable();
        String body = allowed ? row.getBody() : null;
        return new ReviewFeedbackDTO(
                id,
                row.getReviewId(),
                row.getItemId() == null ? null : facts.items().get(row.getItemId()),
                Objects.requireNonNull(facts.developers().get(row.getDeveloperId())),
                row.getDestination(),
                row.getState(),
                row.getStopReason(),
                location,
                row.getReplacesId(),
                row.getCreatedAt(),
                row.getDeliveredAt(),
                body == null ? null : body.substring(0, Math.min(body.length(), PREVIEW_LENGTH)),
                body != null && body.length() > PREVIEW_LENGTH,
                privateDestination || (!allowed && (row.getBody() != null || copies.bodyPresent())),
                selected.results().stream().anyMatch(Result::isMigratedHistory),
                (int) selected.edges().stream()
                        .map(FeedbackBasisRepository.ReadRow::resultId)
                        .distinct()
                        .count(),
                copies.counts(),
                facts.withdrawn().contains(id),
                facts.disagreed().get(id));
    }

    private record CopyFacts(FeedbackCopyCountsDTO counts, boolean bodyPresent) {}

    private record Facts(
            Map<Long, ItemRefDTO> items,
            Map<Long, ReviewDeveloperDTO> developers,
            Map<UUID, FeedbackBasisRead.Snapshot> basis,
            Map<UUID, CopyFacts> copies,
            Set<UUID> withdrawn,
            Map<UUID, Instant> disagreed) {}
}
