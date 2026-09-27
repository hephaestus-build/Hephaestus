package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class DeliveredWorkFeedbackService {
    private static final int LIMIT = 50;
    private final ReviewContextService contexts;
    private final CurrentAccountUsers accountUsers;
    private final DeliveredWorkFeedbackRepository repository;

    @Transactional(readOnly = true)
    public DeliveredWorkFeedbackDTO get(long workspaceId, String url) {
        ReviewedWorkRefDTO work = contexts.resolve(workspaceId, url).work();
        List<Long> recipients = accountUsers.resolve().stream().map(User::getId).toList();
        if (recipients.isEmpty()) {
            return new DeliveredWorkFeedbackDTO(work, List.of(), false);
        }
        long artifactId = Long.parseLong(work.id());
        var found = repository.findDelivered(
                workspaceId, recipients, work.kind(), artifactId, PageRequest.of(0, LIMIT + 1));
        var rows = found.stream().limit(LIMIT).toList();
        if (rows.isEmpty()) {
            return new DeliveredWorkFeedbackDTO(work, List.of(), false);
        }
        List<UUID> ids = rows.stream()
                .map(DeliveredWorkFeedbackRepository.DeliveredRow::getId)
                .toList();
        var practices = repository.findPractices(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(
                        DeliveredWorkFeedbackRepository.PracticeRow::getFeedbackId,
                        Collectors.mapping(
                                row -> new DeliveredFeedbackPracticeDTO(row.getSlug(), row.getName()),
                                Collectors.toList())));
        List<FeedbackPlacement> placements = repository.findPlacements(workspaceId, ids);
        Map<Long, String> mirroredLinks = mirroredLinks(workspaceId, artifactId, work, placements);
        Map<UUID, List<DeliveredWorkFeedbackPlacementDTO>> byFeedback = new HashMap<>();
        for (FeedbackPlacement placement : placements) {
            String commentRef = placement.getPostedCommentRef();
            if (commentRef == null || commentRef.isBlank()) {
                continue;
            }
            String permalink = verifiedLink(work, placement.getPostedCommentUrl())
                    .orElseGet(() -> ReviewedWorkUrls.commentNativeId(work.provider(), placement.getPostedCommentRef())
                            .map(mirroredLinks::get)
                            .flatMap(link -> verifiedLink(work, link))
                            .orElse(null));
            byFeedback
                    .computeIfAbsent(placement.getFeedbackId(), ignored -> new ArrayList<>())
                    .add(new DeliveredWorkFeedbackPlacementDTO(
                            placement.getId(),
                            placement.getPlacementType(),
                            commentRef,
                            placement.getAnchorPath(),
                            placement.getAnchorStartLine(),
                            placement.getAnchorEndLine(),
                            placement.getAnchorSide(),
                            permalink));
        }
        return new DeliveredWorkFeedbackDTO(
                work,
                rows.stream()
                        .map(row -> new DeliveredWorkFeedbackItemDTO(
                                row.getId(),
                                row.getDeliveredAt(),
                                practices.getOrDefault(row.getId(), List.of()),
                                byFeedback.getOrDefault(row.getId(), List.of())))
                        .toList(),
                found.size() > LIMIT);
    }

    private Map<Long, String> mirroredLinks(
            long workspaceId, long artifactId, ReviewedWorkRefDTO work, List<FeedbackPlacement> placements) {
        List<Long> nativeIds = placements.stream()
                .map(FeedbackPlacement::getPostedCommentRef)
                .flatMap(ref -> ReviewedWorkUrls.commentNativeId(work.provider(), ref).stream())
                .distinct()
                .toList();
        if (nativeIds.isEmpty()) {
            return Map.of();
        }
        var rows = new ArrayList<>(repository.findSummaryLinks(workspaceId, artifactId, nativeIds));
        rows.addAll(repository.findInlineLinks(workspaceId, artifactId, nativeIds));
        Map<Long, String> links = new HashMap<>();
        for (var row : rows) {
            verifiedLink(work, row.getUrl()).ifPresent(link -> links.put(row.getNativeId(), link));
        }
        return links;
    }

    /** A recorded URL is still untrusted input: only a comment on this exact provider work is a link. */
    static Optional<String> verifiedLink(ReviewedWorkRefDTO work, @Nullable String url) {
        String workUrl = work.url();
        IntegrationKind provider = work.provider();
        if (url == null || workUrl == null || provider == null) {
            return Optional.empty();
        }
        try {
            URI link = new URI(url);
            String fragment = link.getRawFragment();
            if (fragment == null || fragment.isBlank()) {
                return Optional.empty();
            }
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        var source = ReviewedWorkUrls.page(workUrl);
        var target = ReviewedWorkUrls.page(url);
        if (source.isEmpty()
                || target.isEmpty()
                || !source.get().origin().equals(target.get().origin())) {
            return Optional.empty();
        }
        var sourceWork = ReviewedWorkUrls.workAddress(provider, source.get());
        var targetWork = ReviewedWorkUrls.workAddress(provider, target.get());
        return sourceWork.isPresent() && sourceWork.equals(targetWork) ? Optional.of(url) : Optional.empty();
    }
}
