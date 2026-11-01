package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.context.PublicFeedbackSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewHistoryContentSource {
    private final ResultHistoryQuery results;
    private final FeedbackHistoryQuery feedback;
    private final HistoryCopies copies;
    private final RegisteredItemLabels labels;
    private final ObjectMapper mapper;

    public List<ResultHistoryEntry> previousResults(ReviewAssignmentSnapshot assignment) {
        return results.forAssignment(assignment.item().workspaceId(), assignment.id(), 20).stream()
                .filter(entry -> copies.record(copies.of(entry)))
                .toList();
    }

    public PublicHistoryRead publicSameItemFeedback(ReviewJob job, List<ReviewAssignmentSnapshot> assignments) {
        var selected = new ArrayList<FeedbackHistoryEntry>();
        var itemIds = assignments.stream()
                .map(entry -> entry.item().itemId())
                .distinct()
                .toList();
        var viewers = assignments.stream()
                .map(ReviewAssignmentSnapshot::developerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (itemIds.size() != 1) throw new IllegalArgumentException("Public history requires one item");
        // Issued from the same snapshot as the bounded page; it also covers entries that the page cannot show.
        String publicationContext = viewers.isEmpty()
                ? null
                : feedback.publicationContext(job.getWorkspaceId(), itemIds.getFirst(), job.getReviewId(), viewers);
        for (long itemId : itemIds)
            for (long viewer : viewers) {
                for (var entry : feedback.forItem(job.getWorkspaceId(), itemId, viewer, 100)) {
                    if (entry.developerAccountId() != viewer
                            || entry.destination() != FeedbackDestination.ON_WORK
                            || entry.deliveredAt() == null
                            || entry.body() == null
                            || entry.reviewId().equals(job.getReviewId())
                            || selected.stream()
                                    .anyMatch(prior -> prior.feedbackId().equals(entry.feedbackId()))) continue;
                    if (!copies.of(entry).complete())
                        throw new ResultAdmissionService.PublicHistoryRefusedException(
                                "Historical feedback has no complete copy authority");
                    selected.add(entry);
                }
            }
        var item = assignments.getFirst().item();
        var label = labels.labels(job.getWorkspaceId(), List.of(item)).get(item.itemId());
        var ref = new ItemRefDTO(
                Long.toString(item.itemId()),
                item.kind().value(),
                item.parentId() == null ? null : Long.toString(item.parentId()),
                label == null ? null : label.integration(),
                label == null
                        ? item.kind().value()
                        : label.noun() + (label.number() == null ? "" : " #" + label.number()),
                label == null ? null : label.title(),
                label == null ? null : label.url(),
                label == null ? null : label.container(),
                item.path(),
                null);
        var projection = selected.stream()
                .map(entry -> mapper.treeToValue(entry.publicHistory(), PublicFeedbackSnapshot.Entry.class))
                .toList();
        return new PublicHistoryRead(
                new PublicFeedbackSnapshot(
                        Instant.now(), publicationContext, new PublicFeedbackSnapshot.History(ref, projection)),
                selected.stream().map(copies::of).toList());
    }

    public record PublicHistoryRead(PublicFeedbackSnapshot response, List<HistoryCopy> copies) {
        public PublicHistoryRead {
            copies = List.copyOf(copies);
        }
    }
}
