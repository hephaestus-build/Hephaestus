package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationEligibility;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackCompositionContextService {
    private final ReviewSnapshotQuery reviews;
    private final ResultRepository results;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final PracticePageSupportReader pageSupport;
    private final PracticePageFeedbackRouter pageRouter;
    private final FeedbackGate gate;
    private final ResultHistoryQuery history;
    private final MentorReadinessQuery mentor;
    private final Clock clock;

    public FeedbackCompositionContext read(long workspaceId, UUID reviewId, List<UUID> admittedResultIds) {
        var now = clock.instant();
        var review = reviews.find(workspaceId, reviewId).orElseThrow();
        if (review.kind() != ReviewKind.ITEM) return refused(now, "SCAN_HAS_NO_DEVELOPER_FEEDBACK");
        var ids = admittedResultIds.stream().distinct().toList();
        var rows = results.findByWorkspaceIdAndIdIn(workspaceId, ids);
        if (ids.size() != admittedResultIds.size()
                || rows.size() != ids.size()
                || rows.stream()
                        .anyMatch(row -> row.getWorkspaceId() != workspaceId
                                || !row.getReviewId().equals(reviewId)
                                || row.getItemId() != review.item().itemId()
                                || review.practices().stream().noneMatch(p -> p.practiceId() == row.getPracticeId())))
            throw new IllegalArgumentException("Composition must use this review's admitted results.");
        var ordered = rows.stream().collect(Collectors.toMap(Result::getId, row -> row));
        var negatives = ids.stream()
                .map(ordered::get)
                .filter(Objects::nonNull)
                .filter(row -> row.getOutcome() == ResultOutcome.NOT_MET)
                .toList();
        if (negatives.isEmpty()) return complete(now, List.of(), List.of());
        var developers =
                negatives.stream().map(Result::getDeveloperId).distinct().toList();
        if (developers.size() != 1 || developers.getFirst() == null)
            return new FeedbackCompositionContext(
                    new PracticePageSupport(PracticePageSupport.State.UNAVAILABLE, now, List.of(), null),
                    new ConversationEligibility(ConversationEligibility.State.UNAVAILABLE, now, List.of()));
        long developer = Objects.requireNonNull(developers.getFirst());
        var page = new ArrayList<PracticePageSupport.PracticeSupport>();
        boolean pageUnavailable = false;
        for (long practiceId : negatives.stream()
                .map(Result::getPracticeId)
                .distinct()
                .sorted()
                .toList()) {
            var about = negatives.stream()
                    .filter(row -> row.getPracticeId() == practiceId)
                    .findFirst()
                    .orElseThrow();
            var practice =
                    practices.findByIdAndWorkspaceId(practiceId, workspaceId).orElseThrow();
            var selection = pageSupport.select(workspaceId, developer, practiceId, now);
            var support = selection.evidence();
            var decision = pageDecision(about, support, selection, now);
            List<PracticePageSupport.Occurrence> occurrences = List.of();
            if (decision.decision() == PracticePageDecision.ADMIT) {
                var copied = new ArrayList<PracticePageSupport.Occurrence>();
                for (var row : support) {
                    var entry = history.find(
                                    workspaceId, row.getId(), developer, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                            .filter(e -> e.resultId().equals(row.getId())
                                    && e.itemId() == row.getItemId()
                                    && e.copyAuthorityComplete()
                                    && e.developerAccountId() != null
                                    && Objects.equals(e.developerAccountId(), developer)
                                    && e.practiceId() == practiceId
                                    && e.outcome() == ResultOutcome.NOT_MET
                                    && e.summary() != null
                                    && e.endedAt() == null);
                    if (entry.isEmpty()) {
                        pageUnavailable = true;
                        break;
                    }
                    var e = entry.orElseThrow();
                    Long versionId = row.getPracticeVersionId();
                    copied.add(new PracticePageSupport.Occurrence(
                            e.resultId(),
                            e.itemKind(),
                            Long.toString(e.itemId()),
                            e.outcome(),
                            e.origin(),
                            Objects.requireNonNull(e.summary()),
                            e.explanation(),
                            e.evidence(),
                            versionId == null ? null : versionId.toString(),
                            e.recordedAt()));
                }
                if (!pageUnavailable) occurrences = List.copyOf(copied);
            }
            page.add(new PracticePageSupport.PracticeSupport(
                    practice.getSlug(), decision.decision(), decision.stopReason(), occurrences));
        }
        var routes = negatives.stream()
                .map(row -> {
                    var admission = conversationDecision(review, row);
                    return new ConversationEligibility.ResultDecision(
                            row.getId(), admission.decision(), admission.stopReason());
                })
                .toList();
        if (pageUnavailable)
            return new FeedbackCompositionContext(
                    new PracticePageSupport(
                            PracticePageSupport.State.UNAVAILABLE, now, List.of(), "HISTORY_UNAVAILABLE"),
                    new ConversationEligibility(ConversationEligibility.State.COMPLETE, now, routes));
        return complete(now, page, routes);
    }

    public record PageAdmission(
            PracticePageDecision decision, @Nullable FeedbackStopReason stopReason) {}

    public PageAdmission pageDecision(
            Result about, List<Result> support, PracticePageSupportReader.Selection selection, Instant now) {
        if (role(about) == null)
            return new PageAdmission(
                    PracticePageDecision.UNATTRIBUTABLE_SUBJECT, FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE);
        if (gate.mode(about) == FeedbackMode.OFF)
            return new PageAdmission(PracticePageDecision.FEEDBACK_OFF, FeedbackStopReason.FEEDBACK_OFF);
        if (pageSupport.isBackfillOnly(about.getWorkspaceId(), support))
            return new PageAdmission(PracticePageDecision.BACKFILL_HELD, FeedbackStopReason.BACKFILL_QUIET);
        var review = reviews.find(about.getWorkspaceId(), about.getReviewId()).orElseThrow();
        var check = gate.evaluateComposition(
                review,
                Objects.requireNonNull(about.getDeveloperId()),
                support.isEmpty() ? List.of(about) : support,
                FeedbackDestination.PRACTICE_PAGE);
        if (!check.allowed())
            return new PageAdmission(PracticePageDecision.GATE_REFUSED, Objects.requireNonNull(check.reason()));
        if (support.isEmpty())
            return new PageAdmission(PracticePageDecision.NO_EVIDENCE, FeedbackStopReason.NO_EVIDENCE);
        if (!pageRouter.supportsPattern(about, support))
            return new PageAdmission(PracticePageDecision.UNCORROBORATED, FeedbackStopReason.UNCORROBORATED);
        if (selection
                .previous()
                .filter(card -> card.preparedAt().isAfter(now.minus(Duration.ofDays(14))))
                .isPresent())
            return new PageAdmission(PracticePageDecision.RECENTLY_SURFACED, FeedbackStopReason.NOTHING_NEW);
        return new PageAdmission(PracticePageDecision.ADMIT, null);
    }

    public record ConversationAdmission(
            ConversationDecision decision, @Nullable FeedbackStopReason stopReason) {}

    public ConversationAdmission conversationDecision(ReviewSnapshot review, Result result) {
        if (result.getOutcome() != ResultOutcome.NOT_MET)
            throw new IllegalArgumentException("Conversation feedback requires a NOT_MET result.");
        Long developer = result.getDeveloperId();
        if (developer == null)
            return new ConversationAdmission(
                    ConversationDecision.UNATTRIBUTABLE_SUBJECT, FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE);
        if (review.origin() == ReviewOrigin.BACKFILL)
            return new ConversationAdmission(ConversationDecision.BACKFILL_QUIET, FeedbackStopReason.BACKFILL_QUIET);
        if (gate.mode(result) == FeedbackMode.OFF)
            return new ConversationAdmission(ConversationDecision.FEEDBACK_OFF, FeedbackStopReason.FEEDBACK_OFF);
        if (!"AUTHOR".equals(role(result)))
            return new ConversationAdmission(
                    ConversationDecision.REVIEWER_DEFERRED, FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE);
        var check = gate.evaluateComposition(review, developer, List.of(result), FeedbackDestination.CONVERSATION);
        if (!check.allowed())
            return new ConversationAdmission(ConversationDecision.GATE_REFUSED, Objects.requireNonNull(check.reason()));
        if (!mentor.isReady(review.workspaceId()))
            return new ConversationAdmission(
                    ConversationDecision.MENTOR_UNAVAILABLE, FeedbackStopReason.RECIPIENT_UNAVAILABLE);
        // A place or a delivery on the work does not close the conversation: each destination is judged on its own.
        return new ConversationAdmission(ConversationDecision.ADMIT, null);
    }

    private @Nullable String role(Result result) {
        Long versionId = result.getPracticeVersionId();
        return versionId == null
                ? null
                : versions.findByIdAndWorkspaceId(versionId, result.getWorkspaceId())
                        .map(v -> v.getDefinition().path("about"))
                        .filter(JsonNode::isString)
                        .map(JsonNode::asString)
                        .orElse(null);
    }

    private static FeedbackCompositionContext complete(
            Instant now,
            List<PracticePageSupport.PracticeSupport> practices,
            List<ConversationEligibility.ResultDecision> results) {
        return new FeedbackCompositionContext(
                new PracticePageSupport(PracticePageSupport.State.COMPLETE, now, practices, null),
                new ConversationEligibility(ConversationEligibility.State.COMPLETE, now, results));
    }

    private static FeedbackCompositionContext refused(Instant now, String reason) {
        return new FeedbackCompositionContext(
                new PracticePageSupport(PracticePageSupport.State.REFUSED, now, List.of(), reason),
                new ConversationEligibility(ConversationEligibility.State.REFUSED, now, List.of()));
    }
}
