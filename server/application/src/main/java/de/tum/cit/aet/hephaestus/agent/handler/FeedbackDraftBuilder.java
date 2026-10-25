package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allocates drafts only from this attempt's admitted results, verified anchors and text that the text policy permits. */
public final class FeedbackDraftBuilder {
    private static final Logger log = LoggerFactory.getLogger(FeedbackDraftBuilder.class);

    private FeedbackDraftBuilder() {}

    public static List<FeedbackDraft> build(
            ReviewJob job,
            PrepareFeedbackRequestDTO request,
            List<AdmittedResultSnapshot> admitted,
            List<ResultHistoryEntry> historical,
            Map<ComposedFeedback.Anchor, FeedbackAnchor> anchors) {
        var allowedResults = Set.copyOf(request.basisResultIds());
        @Nullable String publicationContext = request.publicComposition() ? request.publicationContext() : null;
        var byId = admitted.stream().collect(Collectors.toMap(AdmittedResultSnapshot::id, Function.identity()));
        var historyById =
                historical.stream().collect(Collectors.toMap(ResultHistoryEntry::resultId, Function.identity()));
        var texts = new FeedbackTextPolicy(admitted);
        var drafts = new ArrayList<FeedbackDraft>();
        for (var piece : request.composition()) {
            if (!allowedResults.containsAll(piece.resultIds())) throw new AdmissionConflictException();
            var refusal = texts.refusal(piece);
            if (refusal != null) {
                log.info(
                        "Refused composed {} feedback of review job {}: {}", piece.destination(), job.getId(), refusal);
                throw new AdmissionConflictException();
            }
            var support = piece.resultIds().stream()
                    .filter(byId::containsKey)
                    .map(id -> Objects.requireNonNull(byId.get(id)))
                    .toList();
            if (support.isEmpty()) throw new AdmissionConflictException();
            var prior = piece.resultIds().stream()
                    .filter(id -> !byId.containsKey(id))
                    .map(id -> Objects.requireNonNull(historyById.get(id)))
                    .toList();
            if ((!prior.isEmpty() && piece.destination() != FeedbackDestination.PRACTICE_PAGE)
                    || (!piece.notes().isEmpty() && piece.destination() != FeedbackDestination.ON_WORK))
                throw new AdmissionConflictException();
            Long developer = support.getFirst().candidate().developerId();
            if (developer == null
                    || support.stream()
                            .anyMatch(row -> !developer.equals(row.candidate().developerId()))) {
                throw new AdmissionConflictException();
            }
            if (prior.stream()
                            .anyMatch(row -> !developer.equals(row.developerAccountId())
                                    || row.practiceId()
                                            != support.getFirst().candidate().practiceId())
                    || (piece.destination() == FeedbackDestination.PRACTICE_PAGE
                            && support.stream()
                                    .anyMatch(row -> row.candidate().practiceId()
                                            != support.getFirst().candidate().practiceId())))
                throw new AdmissionConflictException();
            UUID first = support.getFirst().id();
            UUID id = piece.withheldReason() != null
                    ? FeedbackIds.withheld(
                            job.getWorkspaceId(), job.getReviewId(), developer, piece.destination(), first)
                    : switch (piece.destination()) {
                        case ON_WORK -> FeedbackIds.onWork(job.getWorkspaceId(), job.getReviewId(), developer);
                        case PRACTICE_PAGE ->
                            FeedbackIds.practicePage(job.getWorkspaceId(), job.getReviewId(), developer, first);
                        case CONVERSATION ->
                            FeedbackIds.conversation(
                                    job.getWorkspaceId(), job.getReviewId(), developer, piece.resultIds());
                    };
            var trace = piece.trace()
                    .withPublicationContext(
                            piece.destination() == FeedbackDestination.ON_WORK ? publicationContext : null);
            var allRefs = new ArrayList<FeedbackCompositionTrace.Support>();
            trace.copies().forEach(copy -> allRefs.addAll(copy.basedOn()));
            trace.withheld().forEach(decision -> allRefs.addAll(decision.basedOn()));
            if (!Set.copyOf(piece.resultIds())
                    .equals(allRefs.stream()
                            .map(FeedbackCompositionTrace.Support::resultId)
                            .collect(Collectors.toSet()))) throw new AdmissionConflictException();
            for (var ref : allRefs) {
                if (ref.problemId() != null
                        && (byId.get(ref.resultId()) == null
                                || byId.get(ref.resultId()).problems().stream()
                                        .noneMatch(problem -> problem.id().equals(ref.problemId()))))
                    throw new AdmissionConflictException();
            }
            var communicated = trace.copies().stream()
                    .flatMap(copy -> copy.basedOn().stream())
                    .distinct()
                    .toList();
            var retained = communicated.isEmpty() ? allRefs.stream().distinct().toList() : communicated;
            var basis = new ArrayList<FeedbackBasisInput>();
            for (var ref : retained) basis.add(new FeedbackBasisInput(ref.resultId(), ref.problemId(), basis.size()));
            var copies = new ArrayList<FeedbackCopyInput>();
            if (piece.destination() == FeedbackDestination.CONVERSATION) {
                copies.add(copy(id, FeedbackCopyKind.CHAT_TURN, 0, null, null));
            } else if (piece.destination() == FeedbackDestination.ON_WORK) {
                if (piece.summary() != null) copies.add(copy(id, FeedbackCopyKind.SUMMARY, 0, null, null));
                for (var note : piece.notes()) {
                    var anchor = anchors.get(new ComposedFeedback.Anchor(note.problemId(), note.citationIndex()));
                    if (anchor == null) throw new AdmissionConflictException();

                    copies.add(copy(id, FeedbackCopyKind.LINE_NOTE, copies.size(), note.body(), anchor));
                }
            }
            if (piece.withheldReason() == null) {
                if (piece.destination() == FeedbackDestination.PRACTICE_PAGE) {
                    if (trace.copies().size() != 1
                            || trace.copies().getFirst().kind() != FeedbackCopyKind.SUMMARY
                            || trace.copies().getFirst().position() != 0) throw new AdmissionConflictException();
                } else if (copies.size() != trace.copies().size()) throw new AdmissionConflictException();
                for (var planned : copies) {
                    var exact = trace.copies().stream()
                            .filter(copy -> copy.kind() == planned.kind() && copy.position() == planned.position())
                            .findFirst()
                            .orElseThrow(AdmissionConflictException::new);
                    if (exact.basedOn().isEmpty()) throw new AdmissionConflictException();
                    if (planned.kind() == FeedbackCopyKind.LINE_NOTE) {
                        var note = piece.notes().get(planned.position() - (piece.summary() == null ? 0 : 1));
                        if (exact.basedOn().stream()
                                .noneMatch(ref -> note.problemId().equals(ref.problemId())))
                            throw new AdmissionConflictException();
                    }
                }
            } else if (!trace.copies().isEmpty()) throw new AdmissionConflictException();
            if (drafts.stream().anyMatch(draft -> draft.id().equals(id))) throw new AdmissionConflictException();
            drafts.add(new FeedbackDraft(
                    id,
                    job.getWorkspaceId(),
                    job.getReviewId(),
                    piece.destination() == FeedbackDestination.PRACTICE_PAGE
                            ? null
                            : job.getInput().itemId(),
                    developer,
                    piece.destination(),
                    piece.withheldReason() == null ? FeedbackState.READY : FeedbackState.WITHHELD,
                    piece.withheldReason(),
                    piece.summary(),
                    basis,
                    copies,
                    Map.of(),
                    trace,
                    piece.page() == null
                            ? null
                            : new FeedbackDraft.Page(
                                    piece.page().title(),
                                    piece.page().body(),
                                    piece.page().nextStep())));
        }
        return List.copyOf(drafts);
    }

    private static FeedbackCopyInput copy(
            UUID feedbackId,
            FeedbackCopyKind kind,
            int position,
            @Nullable String body,
            @Nullable FeedbackAnchor anchor) {
        UUID id = FeedbackIds.copy(feedbackId, kind, position);
        return new FeedbackCopyInput(
                id,
                kind,
                position,
                body,
                anchor == null ? null : anchor.path(),
                anchor == null ? null : anchor.startLine(),
                anchor == null ? null : anchor.endLine(),
                anchor == null ? null : anchor.side(),
                id.toString(),
                kind == FeedbackCopyKind.CHAT_TURN ? null : FeedbackIds.writeMarker(id));
    }
}
