package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.ConversationEligibilityDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticePageSupportDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationEligibility;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Checks historical copy authority and prepares one feedback package from the permitted composition. */
@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class FeedbackCompositionService {
    /** The newest matching pieces of earlier private feedback one composition reads. */
    static final int PRIVATE_HISTORY_LIMIT = 30;

    private final FeedbackStore feedback;
    private final ReviewAssignmentStore members;
    private final ResultHistoryQuery history;
    private final HistoryCopies copies;
    private final FeedbackHistoryQuery feedbackHistory;

    public PrepareFeedbackResponseDTO prepare(
            ReviewJob job,
            PrepareFeedbackRequestDTO request,
            List<AdmittedResultSnapshot> rows,
            Read context,
            Map<ComposedFeedback.Anchor, FeedbackAnchor> anchors,
            boolean publicAlreadyAccepted) {
        var drafts = FeedbackDraftBuilder.build(job, request, rows, context.support(), anchors);
        // A public package holds only the review on the work, and a private package holds none of it.
        if (drafts.stream()
                .anyMatch(draft -> (draft.destination() == FeedbackDestination.ON_WORK) != request.publicComposition()))
            throw new AdmissionConflictException();
        if (request.publicComposition() && publicAlreadyAccepted && job.getPublicReviewDigest() == null)
            throw new AdmissionConflictException();
        // An accepted package replays below by its digest; only a first acceptance is checked against current history.
        if (request.publicComposition() && !publicAlreadyAccepted)
            for (var draft : drafts) {
                Long itemId = draft.itemId();
                if (draft.destination() != FeedbackDestination.ON_WORK
                        || draft.state() == FeedbackState.WITHHELD
                        || draft.copies().isEmpty()
                        || itemId == null) continue;
                var refusal = feedbackHistory.publicationRefusal(
                        job.getWorkspaceId(),
                        itemId,
                        job.getReviewId(),
                        draft.developerId(),
                        request.publicationContext());
                if (refusal.isPresent()) throw new PublicationContextRefusedException(refusal.get());
            }
        var prepared = feedback.prepareAll(job.getWorkspaceId(), job.getReviewId(), drafts);
        if (request.publicComposition()
                && publicAlreadyAccepted
                && !prepared.packageDigest().equals(job.getPublicReviewDigest()))
            throw new AdmissionConflictException();
        if (request.publicComposition()) {
            members.publicReviewed(
                    job.getWorkspaceId(), job.getReviewId(), job.getId(), job.getAttempt(), prepared.preparedAt());
            job.setPublicReviewDigest(prepared.packageDigest());
        }
        for (var draft : drafts) {
            var receipt = prepared.pieces().stream()
                    .filter(piece -> piece.feedbackId().equals(draft.id()))
                    .findFirst()
                    .orElseThrow();
            if (draft.destination() == FeedbackDestination.PRACTICE_PAGE)
                job.setPracticePagePreparedAt(receipt.preparedAt());
            else if (draft.destination() == FeedbackDestination.CONVERSATION)
                job.setConversationPreparedAt(receipt.preparedAt());
        }
        return new PrepareFeedbackResponseDTO(
                prepared.pieces().stream().map(piece -> piece.feedbackId()).toList(),
                prepared.packageDigest(),
                prepared.preparedAt());
    }

    /** What private composition is given: its routing, its page support, and the earlier feedback on its open routes. */
    public Read read(ReviewJob job, List<AdmittedResultSnapshot> admitted) {
        return read(job, admitted, true);
    }

    /** Preparation rechecks routing and page support only; earlier private feedback is no input to it. */
    public Read readForPreparation(ReviewJob job, List<AdmittedResultSnapshot> admitted) {
        return read(job, admitted, false);
    }

    /** The private feedback this review already prepared on these admitted results, for the one person they name. */
    public PrivateFeedbackProgress privateProgress(ReviewJob job, List<AdmittedResultSnapshot> admitted) {
        var people = admitted.stream()
                .map(row -> row.candidate().developerId())
                .distinct()
                .toList();
        if (people.size() != 1 || people.getFirst() == null) return PrivateFeedbackProgress.NONE;
        return feedback.privateProgress(
                job.getWorkspaceId(),
                job.getReviewId(),
                Objects.requireNonNull(people.getFirst()),
                admitted.stream().map(AdmittedResultSnapshot::id).toList());
    }

    private Read read(ReviewJob job, List<AdmittedResultSnapshot> admitted, boolean withHistory) {
        var people = admitted.stream()
                .map(row -> row.candidate().developerId())
                .distinct()
                .toList();
        if (people.size() != 1 || people.getFirst() == null)
            return new Read(new FeedbackCompositionContext(null, null), List.of(), List.of(), null);
        long developer = Objects.requireNonNull(people.getFirst());
        var context = feedback.compositionContext(
                job.getWorkspaceId(),
                job.getReviewId(),
                admitted.stream().map(AdmittedResultSnapshot::id).toList());
        var support = context.practicePageSupport();
        var retained = new ArrayList<ResultHistoryEntry>();
        if (support != null && support.state() == PracticePageSupport.State.COMPLETE) {
            supported:
            for (var practice : support.practices())
                for (var occurrence : practice.occurrences()) {
                    var result = history.find(
                                    job.getWorkspaceId(),
                                    occurrence.resultId(),
                                    developer,
                                    SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                            .orElse(null);
                    if (result == null
                            || !copies.of(result).complete()
                            || !Long.valueOf(developer).equals(result.developerAccountId())
                            || result.outcome() != ResultOutcome.NOT_MET
                            || !practice.practiceSlug().equals(result.practiceSlug())
                            || !occurrence.itemId().equals(Long.toString(result.itemId()))
                            || !occurrence.itemKind().equals(result.itemKind())
                            || !Objects.equals(occurrence.summary(), result.summary())
                            || !Objects.equals(occurrence.explanation(), result.explanation())
                            || !Objects.equals(occurrence.evidence(), result.evidence())
                            || !occurrence.recordedAt().equals(result.recordedAt())) {
                        context = new FeedbackCompositionContext(
                                new PracticePageSupport(
                                        PracticePageSupport.State.REFUSED,
                                        support.readAt(),
                                        List.of(),
                                        "Copy authority for historical support is unavailable"),
                                context.conversationEligibility());
                        retained.clear();
                        break supported;
                    }
                    if (retained.stream().noneMatch(entry -> entry.resultId().equals(result.resultId())))
                        retained.add(result);
                }
        }
        var routes = withHistory ? routes(context, admitted, retained) : List.<PrivateFeedbackHistory.Route>of();
        PrivateFeedbackHistory said = routes.isEmpty()
                ? null
                : feedbackHistory
                        .forPrivateComposition(job.getWorkspaceId(), developer, routes, PRIVATE_HISTORY_LIMIT)
                        .orElse(null);
        var readCopies = new ArrayList<HistoryCopy>();
        if (said != null)
            said.entries().stream().map(entry -> copies.of(entry.feedback())).forEach(readCopies::add);
        retained.stream().map(copies::of).forEach(readCopies::add);
        return new Read(context, readCopies, retained, said);
    }

    /**
     * The private destinations this same read opened, each for its own practice: a practice page by the support it
     * admits, a conversation by the NOT_MET result it admits. A practice open on one destination opens no other.
     */
    private static List<PrivateFeedbackHistory.Route> routes(
            FeedbackCompositionContext context,
            List<AdmittedResultSnapshot> admitted,
            List<ResultHistoryEntry> retained) {
        var routes = new LinkedHashSet<PrivateFeedbackHistory.Route>();
        var page = context.practicePageSupport();
        if (page != null && page.state() == PracticePageSupport.State.COMPLETE)
            for (var practice : page.practices())
                if (practice.decision() == PracticePageDecision.ADMIT)
                    retained.stream()
                            .filter(entry -> entry.practiceSlug().equals(practice.practiceSlug()))
                            .map(entry -> new PrivateFeedbackHistory.Route(
                                    entry.practiceId(), FeedbackDestination.PRACTICE_PAGE))
                            .forEach(routes::add);
        var conversation = context.conversationEligibility();
        if (conversation != null && conversation.state() == ConversationEligibility.State.COMPLETE)
            for (var decision : conversation.results())
                if (decision.decision() == ConversationDecision.ADMIT)
                    admitted.stream()
                            .filter(row -> row.id().equals(decision.resultId())
                                    && row.candidate().outcome() == ResultOutcome.NOT_MET)
                            .map(row -> new PrivateFeedbackHistory.Route(
                                    row.candidate().practiceId(), FeedbackDestination.CONVERSATION))
                            .forEach(routes::add);
        return List.copyOf(routes);
    }

    public Read readForCapture(ReviewJob job, List<AdmittedResultSnapshot> admitted) {
        var read = read(job, admitted);
        if (read.copies().stream().anyMatch(copy -> !copies.record(copy)))
            throw new IllegalStateException("Historical support cannot be copied into this capture");
        return read;
    }

    public static @Nullable PracticePageSupportDTO page(Read read) {
        var support = read.context().practicePageSupport();
        if (support == null) return null;
        if (support.state() != PracticePageSupport.State.COMPLETE)
            return new PracticePageSupportDTO.Closed(support.state(), support.readAt(), support.refusal());
        return new PracticePageSupportDTO.Complete(
                support.state(),
                support.readAt(),
                support.practices().stream()
                        .map(practice -> new PracticePageSupportDTO.PracticeSupportDTO(
                                practice.practiceSlug(),
                                practice.decision(),
                                practice.stopReason(),
                                practice.occurrences().stream()
                                        .map(row -> new PracticePageSupportDTO.SupportOccurrenceDTO(
                                                row.resultId(),
                                                row.itemKind(),
                                                row.itemId(),
                                                row.outcome(),
                                                row.origin(),
                                                row.summary(),
                                                row.explanation(),
                                                row.evidence(),
                                                row.practiceVersionId(),
                                                row.recordedAt()))
                                        .toList()))
                        .toList());
    }

    public static @Nullable ConversationEligibilityDTO conversation(Read read) {
        var eligibility = read.context().conversationEligibility();
        return eligibility == null
                ? null
                : new ConversationEligibilityDTO(
                        eligibility.state(),
                        eligibility.readAt(),
                        eligibility.results().stream()
                                .map(row -> new ConversationEligibilityDTO.ResultDecisionDTO(
                                        row.resultId(), row.decision(), row.stopReason()))
                                .toList());
    }

    public static @Nullable PrivateFeedbackHistoryDTO privateHistory(Read read) {
        var said = read.privateHistory();
        if (said == null) return null;
        var delivered = new ArrayList<PrivateFeedbackHistoryDTO.Delivered>();
        var queued = new ArrayList<PrivateFeedbackHistoryDTO.Queued>();
        for (var entry : said.entries()) {
            var row = entry.feedback();
            var basedOn = entry.basedOn().stream()
                    .map(basis -> new PrivateFeedbackHistoryDTO.Basis(
                            basis.resultId(), basis.practiceSlug(), basis.practiceVersionId(), basis.outcome()))
                    .toList();
            // The history owner decides which words may be copied. Words of a claim that no longer holds are still what
            // was said; recordedClaimCurrentness says the claim's state, never whether it was said.
            String body = row.body();
            var deliveredAt = row.deliveredAt();
            String practice = entry.replaceablePracticeSlug();
            if (deliveredAt != null)
                delivered.add(new PrivateFeedbackHistoryDTO.Delivered(
                        row.feedbackId(),
                        row.destination(),
                        deliveredAt,
                        entry.currentness(),
                        entry.withdrawn(),
                        basedOn,
                        body == null
                                ? null
                                : row.destination() == FeedbackDestination.CONVERSATION
                                        ? PrivateFeedbackHistoryDTO.BodyRole.CONVERSATION_WORDS
                                        : PrivateFeedbackHistoryDTO.BodyRole.PRACTICE_PAGE_CARD,
                        body));
            else if (practice != null)
                // Its feedback ID is its thread key: preparation replaces exactly this queued piece.
                queued.add(new PrivateFeedbackHistoryDTO.Queued(
                        row.feedbackId().toString(),
                        row.feedbackId(),
                        row.destination(),
                        practice,
                        row.createdAt(),
                        entry.currentness(),
                        basedOn,
                        body == null ? null : PrivateFeedbackHistoryDTO.BodyRole.MENTOR_NOTES,
                        body));
        }
        return new PrivateFeedbackHistoryDTO(
                said.readAt(),
                new PrivateFeedbackHistoryDTO.Coverage(said.maxEntries(), said.hasMore(), said.withoutCopyAuthority()),
                delivered,
                queued);
    }

    /**
     * @param privateHistory null when it was not read: no single developer, no open private route, a preparation
     *     read, or no membership
     */
    public record Read(
            FeedbackCompositionContext context,
            List<HistoryCopy> copies,
            List<ResultHistoryEntry> support,
            @Nullable PrivateFeedbackHistory privateHistory) {
        public Read {
            copies = List.copyOf(copies);
            support = List.copyOf(support);
        }
    }
}
