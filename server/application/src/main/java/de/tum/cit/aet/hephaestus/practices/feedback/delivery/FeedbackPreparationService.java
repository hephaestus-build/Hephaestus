package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.practices.feedback.PracticePageFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackPreparationService {
    private final ReviewSnapshotQuery reviews;
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final FeedbackBasisRepository basis;
    private final ItemDescriptorRegistry catalog;
    private final Clock clock;
    private final PracticeVersionRepository versions;
    private final RepeatedSummaryCheck repeated;
    private final FeedbackResponseSuppressionFilter suppression;
    private final FeedbackSupersession replacements;
    private final PracticePageSupportReader pageSupport;
    private final FeedbackCompositionContextService composition;

    public record Preparation(
            FeedbackDraft draft, @Nullable UUID replacesId, Map<UUID, FeedbackStopReason> withheldCopies) {
        public Preparation {
            withheldCopies = Map.copyOf(withheldCopies);
        }
    }

    public Preparation prepare(FeedbackDraft draft, Optional<Feedback> existing) {
        var page = draft.page();
        String rendered = page == null
                ? draft.body()
                : PracticePageFeedbackBody.render(page.title(), page.body(), page.nextStep());
        if ((rendered != null && rendered.length() > 8000)
                || draft.copies().stream()
                                .filter(copy -> copy.kind() == FeedbackCopyKind.LINE_NOTE
                                        || copy.kind() == FeedbackCopyKind.FILE_NOTE)
                                .count()
                        > 30
                || draft.copies().stream()
                        .anyMatch(copy -> copy.body() != null && copy.body().length() > 2000))
            throw new IllegalArgumentException("Composition exceeds its text bounds.");
        var snapshot = reviews.find(draft.workspaceId(), draft.reviewId()).orElseThrow();
        var descriptor = catalog.descriptorFor(snapshot.item().kind()).orElseThrow();
        var ids = draft.basis().stream()
                .map(FeedbackBasisInput::resultId)
                .distinct()
                .toList();
        var support = results.findByWorkspaceIdAndIdIn(draft.workspaceId(), ids);
        if (support.size() != ids.size()
                || support.stream()
                        .anyMatch(row -> !Objects.equals(row.getDeveloperId(), draft.developerId())
                                || (draft.destination() != FeedbackDestination.PRACTICE_PAGE
                                        && !row.getReviewId().equals(draft.reviewId()))))
            throw conflict("Composition must use this review's admitted results for one developer.");
        var primary = support.stream()
                .filter(row -> row.getReviewId().equals(draft.reviewId()))
                .findFirst()
                .orElseThrow();
        String body = clean(rendered);
        FeedbackStopReason reason = draft.stopReason();
        UUID replacesId = null;
        var trace = draft.composition();
        if (trace != null && trace.replacesId() != null) {
            if (draft.destination() != FeedbackDestination.CONVERSATION || trace.primaryResultId() == null)
                throw conflict("Only conversation feedback can replace a queued conversation concern.");
            var primaryResult = support.stream()
                    .filter(row -> row.getId().equals(trace.primaryResultId()))
                    .findFirst()
                    .orElseThrow(() -> conflict("Replacement needs its current primary practice."));
            if (existing.isEmpty()
                    && !replacements.canReplaceConversation(
                            draft.workspaceId(),
                            trace.replacesId(),
                            draft.developerId(),
                            primaryResult.getPracticeId()))
                throw conflict("The conversation target is no longer queued for this developer and practice.");
            replacesId = trace.replacesId();
        }
        if (draft.destination() == FeedbackDestination.PRACTICE_PAGE) {
            if (support.stream().anyMatch(row -> row.getPracticeId() != primary.getPracticeId()))
                throw conflict("A practice-page piece must address one practice.");
            if (existing.isPresent()) {
                var retainedIds =
                        basis
                                .findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(draft.workspaceId(), draft.id())
                                .stream()
                                .map(FeedbackBasis::getResultId)
                                .distinct()
                                .toList();
                if (support.stream()
                        .anyMatch(row ->
                                !row.getReviewId().equals(draft.reviewId()) && !retainedIds.contains(row.getId())))
                    throw conflict("A replay must use its retained historical basis.");
                var retainedRows = results.findByWorkspaceIdAndIdIn(draft.workspaceId(), retainedIds).stream()
                        .collect(Collectors.toMap(Result::getId, row -> row));
                support = retainedIds.stream()
                        .map(retainedRows::get)
                        .filter(Objects::nonNull)
                        .toList();
            } else {
                var selection = pageSupport.select(
                        draft.workspaceId(), draft.developerId(), primary.getPracticeId(), clock.instant());
                var selectedIds =
                        selection.evidence().stream().map(Result::getId).toList();
                if (support.stream()
                        .anyMatch(row ->
                                !row.getReviewId().equals(draft.reviewId()) && !selectedIds.contains(row.getId())))
                    throw conflict("Historical support must come from the selected practice-page window.");
                support = selection.evidence();
                var prior = selection.previous();
                var decision = composition.pageDecision(primary, support, selection, clock.instant());
                if (decision.stopReason() != null) reason = decision.stopReason();
                else if (ids.stream().anyMatch(id -> !selectedIds.contains(id)))
                    reason = FeedbackStopReason.EVIDENCE_NOT_PERMITTED;
                if (prior.isPresent() && !prior.get().id().equals(draft.id())) {
                    if (prior.get().isOpen()) replacesId = prior.get().id();
                    else if (reason == null
                            && support.stream()
                                    .noneMatch(row -> row.getRecordedAt()
                                            .isAfter(prior.get().nextEvidenceSince(Instant.EPOCH))))
                        reason = FeedbackStopReason.NOTHING_NEW;
                }
            }
        }
        if (draft.destination() == FeedbackDestination.CONVERSATION && existing.isEmpty()) {
            for (var result : support) {
                if (result.getOutcome() != ResultOutcome.NOT_MET)
                    throw conflict("Conversation feedback requires NOT_MET results.");
                var decision = composition.conversationDecision(snapshot, result);
                if (decision.decision() != ConversationDecision.ADMIT) {
                    reason = Objects.requireNonNull(decision.stopReason());
                    break;
                }
            }
        }
        if (draft.destination() == FeedbackDestination.ON_WORK) {
            if (support.stream()
                    .anyMatch(row -> row.getPracticeVersionId() == null
                            || row.isMigratedHistory()
                            || versions.findByIdAndWorkspaceId(row.getPracticeVersionId(), draft.workspaceId())
                                    .filter(version -> AboutRole.AUTHOR
                                            .name()
                                            .equals(version.getDefinition()
                                                    .path("about")
                                                    .asString()))
                                    .isEmpty())) reason = FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE;
            if (body != null
                    && repeated.repeats(draft.workspaceId(), snapshot.item().itemId(), draft.developerId(), body))
                reason = FeedbackStopReason.REPEATS_DELIVERED_NOTE;
            if (!descriptor.copyCapabilities().contains(CopyCapability.SUMMARY)
                    && !descriptor.copyCapabilities().contains(CopyCapability.LINE_NOTE))
                reason = FeedbackStopReason.RECIPIENT_UNAVAILABLE;
        }
        var wideResults = draft.basis().stream()
                .filter(edge -> edge.problemId() == null)
                .map(FeedbackBasisInput::resultId)
                .distinct()
                .toList();
        var communicatedProblems = new HashSet<UUID>();
        draft.basis().stream()
                .map(FeedbackBasisInput::problemId)
                .filter(Objects::nonNull)
                .forEach(communicatedProblems::add);
        var resultProblems = new HashMap<UUID, List<UUID>>();
        if (!wideResults.isEmpty())
            problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(draft.workspaceId(), wideResults)
                    .forEach(problem -> {
                        communicatedProblems.add(problem.getId());
                        resultProblems
                                .computeIfAbsent(problem.getResultId(), ignored -> new ArrayList<>())
                                .add(problem.getId());
                    });
        var suppressed = suppression.suppressedProblems(draft.workspaceId(), communicatedProblems);
        var withheldCopies = new HashMap<UUID, FeedbackStopReason>();
        if (draft.destination() == FeedbackDestination.ON_WORK
                && trace != null
                && !draft.copies().isEmpty()) {
            for (var copy : draft.copies()) {
                var exact = trace.copies().stream()
                        .filter(entry -> entry.kind() == copy.kind() && entry.position() == copy.position())
                        .findFirst()
                        .orElseThrow(() -> conflict("A public copy needs its exact support."));
                var copyReasons = exact.basedOn().stream()
                        .flatMap(ref -> ref.problemId() == null
                                ? resultProblems.getOrDefault(ref.resultId(), List.of()).stream()
                                : Stream.of(ref.problemId()))
                        .map(suppressed::get)
                        .filter(Objects::nonNull)
                        .toList();
                if (!copyReasons.isEmpty()) withheldCopies.put(copy.id(), suppressionReason(copyReasons));
            }
            if (reason == null && withheldCopies.size() == draft.copies().size())
                reason = suppressionReason(new ArrayList<>(withheldCopies.values()));
        } else if (reason == null && !suppressed.isEmpty()) {
            reason = suppressionReason(new ArrayList<>(suppressed.values()));
        }
        List<FeedbackBasisInput> edges = new ArrayList<>(draft.basis());
        int ordinal = edges.stream().mapToInt(FeedbackBasisInput::ordinal).max().orElse(-1) + 1;
        for (var row : support)
            if (!ids.contains(row.getId())) edges.add(new FeedbackBasisInput(row.getId(), null, ordinal++));
        List<FeedbackCopyInput> planned = new ArrayList<>();
        for (var copy : draft.copies()) {
            if ((copy.kind() == FeedbackCopyKind.LINE_NOTE || copy.kind() == FeedbackCopyKind.FILE_NOTE)
                    && !descriptor.copyCapabilities().contains(CopyCapability.LINE_NOTE))
                throw conflict("This item does not support anchored feedback.");
            String words = clean(copy.body());
            if (copy.kind() != FeedbackCopyKind.SUMMARY && copy.kind() != FeedbackCopyKind.CHAT_TURN && words == null)
                continue;
            if (copy.kind() == FeedbackCopyKind.SUMMARY && body == null) continue;
            planned.add(new FeedbackCopyInput(
                    copy.id(),
                    copy.kind(),
                    copy.position(),
                    words,
                    copy.path(),
                    copy.startLine(),
                    copy.endLine(),
                    copy.side(),
                    copy.writeKey(),
                    copy.writeMarker()));
        }
        if (reason == null && body == null && planned.stream().noneMatch(copy -> copy.body() != null))
            reason = FeedbackStopReason.EMPTY_AFTER_SANITIZE;
        return new Preparation(
                new FeedbackDraft(
                        draft.id(),
                        draft.workspaceId(),
                        draft.reviewId(),
                        draft.destination() == FeedbackDestination.PRACTICE_PAGE
                                ? null
                                : snapshot.item().itemId(),
                        draft.developerId(),
                        draft.destination(),
                        reason == null ? draft.state() : FeedbackState.WITHHELD,
                        reason,
                        body,
                        edges,
                        planned,
                        draft.checks(),
                        draft.composition(),
                        null),
                replacesId,
                withheldCopies);
    }

    private static FeedbackStopReason suppressionReason(List<FeedbackStopReason> reasons) {
        return reasons.contains(FeedbackStopReason.REACTED_DISAGREE)
                ? FeedbackStopReason.REACTED_DISAGREE
                : FeedbackStopReason.REACTED_NOT_APPLICABLE;
    }

    public void recorded(Preparation preparation, Feedback row) {
        if (preparation.replacesId() != null
                && row.getState() == FeedbackState.READY
                && !replacements.replaceOpen(row.getWorkspaceId(), preparation.replacesId(), row.getId()))
            throw conflict("The queued feedback could not be replaced.");
    }

    private static @Nullable String clean(@Nullable String text) {
        if (text == null) return null;
        var sanitized = DeveloperTextSanitizer.sanitize(text);
        return sanitized.isBlank() ? null : sanitized;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
