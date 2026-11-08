package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.dto.FeedbackDecisionDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackCheckDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackCopyDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackSendDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackDetailDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReasonCountDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackReadService {
    private final FeedbackRepository feedback;
    private final FeedbackBasisRead basisRead;
    private final FeedbackCopyRepository copies;
    private final FeedbackSendRepository sends;
    private final FeedbackDecisionRepository decisions;
    private final FeedbackResponseRepository responses;
    private final FeedbackContentDigest digests;
    private final ProductReferenceQuery references;
    private final ReviewRepository reviews;
    private final FeedbackQueryRepository queries;
    private final FeedbackListReadQuery listRead;
    private final ObjectMapper mapper;

    public Page<ReviewFeedbackDTO> list(long workspaceId, FeedbackQueryFilter filter, Pageable pageable) {
        return listRead.list(workspaceId, filter, pageable);
    }

    public List<ReasonCountDTO> locations(long workspaceId, FeedbackQueryFilter filter) {
        return counts(workspaceId, filter, true);
    }

    public List<ReasonCountDTO> reasons(long workspaceId, FeedbackQueryFilter filter) {
        return counts(workspaceId, filter, false);
    }

    private List<ReasonCountDTO> counts(long workspaceId, FeedbackQueryFilter filter, boolean byLocation) {
        return queries.counts(workspaceId, mapper.writeValueAsString(filter), byLocation).stream()
                .map(row -> new ReasonCountDTO(row.getReason(), description(row.getReason()), row.getCount()))
                .toList();
    }

    private static String description(String value) {
        String words = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    public ReviewFeedbackDetailDTO detail(long workspaceId, UUID id) {
        var row = required(workspaceId, id);
        var summary = listRead.summary(
                row, queries.locations(workspaceId, List.of(id)).getFirst().getLocation());
        var selected = basisRead.read(workspaceId, id);
        var bound = basisRead.bound(workspaceId, selected);
        boolean readable = row.getDestination() == FeedbackDestination.ON_WORK && selected.readable();
        var actualCopies = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, id).stream()
                .map(copy -> redactedCopy(copy, readable))
                .toList();
        var activeSend = sends.findByFeedbackIdAndWorkspaceId(id, workspaceId).map(FeedbackSendDTO::from).stream()
                .toList();
        return new ReviewFeedbackDetailDTO(
                summary.id(),
                summary.reviewId(),
                summary.item(),
                summary.developer(),
                summary.destination(),
                summary.state(),
                summary.stopReason(),
                summary.location(),
                summary.replacesId(),
                summary.createdAt(),
                summary.deliveredAt(),
                summary.bodyPreview(),
                summary.bodyTruncated(),
                summary.textHidden(),
                summary.unverifiedHistory(),
                summary.resultCount(),
                summary.copyCounts(),
                summary.withdrawn(),
                summary.disagreedAt(),
                readable ? row.getBody() : null,
                bound,
                actualCopies,
                activeSend,
                decisionHistory(workspaceId, id),
                responses
                        .findCurrent(workspaceId, id)
                        .map(FeedbackResponseDTO::from)
                        .orElse(null),
                checks(row),
                readable ? digests.current(workspaceId, id) : null);
    }

    public List<FeedbackDecisionDTO> decisionHistory(long workspaceId, UUID id) {
        required(workspaceId, id);
        var rows = decisions.findByWorkspaceIdAndFeedbackIdOrderByDecidedAtDescIdDesc(workspaceId, id);
        var actors = references.accounts(
                workspaceId,
                rows.stream()
                        .map(row -> row.getActorAccountId())
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        return rows.stream()
                .map(row -> FeedbackDecisionDTO.from(row, actors.get(row.getActorAccountId())))
                .toList();
    }

    public List<FeedbackCheckDTO> reviewChecks(long workspaceId, UUID reviewId) {
        var review = reviews.findByIdAndWorkspaceId(reviewId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Review", reviewId.toString()));
        return feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(workspaceId, review.getId()).stream()
                .flatMap(row -> checks(row).stream())
                .toList();
    }

    private static List<FeedbackCheckDTO> checks(Feedback row) {
        return row.getChecks().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().checks().stream())
                .map(FeedbackCheckDTO::from)
                .toList();
    }

    private Feedback required(long workspaceId, UUID id) {
        return feedback.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", id.toString()));
    }

    private static FeedbackCopyDTO redactedCopy(FeedbackCopy copy, boolean readable) {
        if (readable) return FeedbackCopyDTO.from(copy);
        return new FeedbackCopyDTO(
                copy.getId(),
                copy.getKind(),
                copy.getPosition(),
                null,
                null,
                null,
                null,
                null,
                copy.getState(),
                copy.getWriteStartedAt(),
                copy.getExternalUrl(),
                copy.getExternalRef(),
                copy.getMentorEntryId(),
                copy.getPostedAt(),
                copy.getStopReason(),
                copy.getLastError(),
                copy.getReadback());
    }
}
