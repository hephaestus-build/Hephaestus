package de.tum.cit.aet.hephaestus.practices.feedback.page;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.PracticePageFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.dto.PracticePageCleanWorkDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.dto.PracticePageEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.dto.PracticePageFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackClosedBy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** The weekly digest that Heph reads on a worker counts waiting Practice page feedback, so this lives on both. */
@Service
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class PracticePageFeedbackService {

    static final int MAX_CARDS = 20;
    static final Duration CLOSED_CARD_STAYS = Duration.ofDays(30);

    private final FeedbackRepository feedbackRepository;
    private final PracticePageFeedbackBasis basis;
    private final FeedbackResponseRepository responses;
    private final FeedbackVisibilityService visibility;
    private final ProductReferenceQuery items;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final WorkspaceReviewDefaultsProvider defaults;
    private final Clock clock;
    private final FeedbackGate gate;
    private final PersonDataWriteFence privacy;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final FeedbackStoreService store;

    @Transactional
    public List<PracticePageFeedbackDTO> getOwnPracticePageFeedback(long workspaceId) {
        var view = UserViewContextHolder.get();
        var current = currentDeveloperLookup.currentDeveloperId();
        if (view == null && current.isEmpty()) {
            return List.of();
        }
        long developerId = view == null ? current.orElseThrow() : view.accountId();
        if (view != null && view.workspaceId() != workspaceId) {
            return List.of();
        }
        if (view == null && !privacy.holdForAccountWrite(developerId)) {
            return List.of();
        }
        List<Feedback> feedback = feedbackRepository.findReadablePracticePage(workspaceId, List.of(developerId));
        if (view == null) {
            for (Feedback piece : feedback.stream()
                    .sorted(Comparator.comparing(piece -> piece.getId().toString()))
                    .toList()) {
                Objects.requireNonNull(entityManager).refresh(piece, LockModeType.PESSIMISTIC_WRITE);
            }
        }
        feedback = eligibleReady(feedback, view == null);
        Instant now = clock.instant();
        var page = page(workspaceId, feedback, now);
        var itemIds = page.stream()
                .flatMap(card -> Stream.concat(
                        card.evidence().stream().map(Result::getItemId),
                        card.resolution().cleanWork().stream().map(WorkResolution.Work::itemId)))
                .collect(Collectors.toSet());
        var references = items.items(workspaceId, itemIds);
        return page.stream()
                .map(card -> {
                    Instant readAt = card.feedback().getDeliveredAt();
                    if (view == null
                            && !card.withdrawn()
                            && card.feedback().getState() == FeedbackState.READY
                            && feedbackRepository.recordFirstRead(
                                            workspaceId,
                                            card.feedback().getId(),
                                            developerId,
                                            now,
                                            FeedbackDestination.PRACTICE_PAGE,
                                            FeedbackState.READY,
                                            FeedbackState.DELIVERED)
                                    == 1) {
                        readAt = now;
                    }
                    return toCard(card, references, readAt);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> countOpen(long workspaceId, Collection<Long> developerIds) {
        if (developerIds.isEmpty()) {
            return Map.of();
        }
        var feedback = eligibleReady(feedbackRepository.findReadablePracticePage(workspaceId, developerIds), false);
        var byDeveloper = feedback.stream().collect(Collectors.groupingBy(Feedback::getDeveloperId));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (long developerId : developerIds) {
            counts.put(developerId, (int)
                    page(workspaceId, byDeveloper.getOrDefault(developerId, List.of()), clock.instant()).stream()
                            .filter(card -> !card.withdrawn() && card.closedAt() == null)
                            .count());
        }
        return counts;
    }

    /**
     * When each unread card on the developer's Practice page was created: the page would show it now, it is
     * {@code READY} and it is not withdrawn. The weekly digest counts these as waiting.
     */
    @Transactional(readOnly = true)
    public List<Instant> unreadCreatedAt(long workspaceId, long developerId) {
        var feedback =
                eligibleReady(feedbackRepository.findReadablePracticePage(workspaceId, List.of(developerId)), false);
        return page(workspaceId, feedback, clock.instant()).stream()
                .filter(card -> !card.withdrawn() && card.feedback().getState() == FeedbackState.READY)
                .map(card -> card.feedback().getCreatedAt())
                .toList();
    }

    private record Card(
            Feedback feedback,
            List<Result> evidence,
            Practice practice,
            WorkResolution resolution,
            @Nullable FeedbackResponseDTO response,
            @Nullable Instant closedAt,
            @Nullable FeedbackClosedBy closedBy,
            boolean withdrawn) {}

    private List<Card> page(long workspaceId, List<Feedback> feedback, Instant now) {
        if (feedback.isEmpty()) {
            return List.of();
        }
        var ids = feedback.stream().map(Feedback::getId).toList();
        var evidence = basis.visibleEvidence(workspaceId, ids);
        var practices = basis.practices(
                workspaceId,
                evidence.values().stream().flatMap(Collection::stream).toList());
        var changed = basis.practiceChangedAt(evidence);
        var resolutions = basis.workResolutions(workspaceId, feedback, evidence, changed, now);
        var withdrawn = visibility.withdrawnAt(workspaceId, ids);
        var answers = responses.findCurrentForFeedbackIds(workspaceId, ids).stream()
                .collect(Collectors.toMap(FeedbackResponse::getFeedbackId, FeedbackResponseDTO::from));
        FeedbackMode workspaceMode = defaults.forWorkspace(workspaceId).defaultFeedbackMode();
        return feedback.stream()
                .filter(piece -> evidence.containsKey(piece.getId()))
                .map(piece -> {
                    var support = Objects.requireNonNull(evidence.get(piece.getId()));
                    Practice practice = practices.get(support.getFirst().getPracticeId());
                    if (practice == null
                            || FeedbackModeResolver.effectiveModeOf(practice, workspaceMode) == FeedbackMode.OFF) {
                        return null;
                    }
                    boolean hidden = withdrawn.containsKey(piece.getId());
                    if (hidden && piece.getDeliveredAt() == null) {
                        return null;
                    }
                    var resolution = resolutions.getOrDefault(piece.getId(), WorkResolution.NONE);
                    var answer = answers.get(piece.getId());
                    FeedbackClosure closure = FeedbackClosure.of(
                            resolution.resolvedAt(),
                            PracticePageFeedbackBasis.resolvedByDeveloperAt(answer),
                            changed.get(piece.getId()));
                    if (!hidden
                            && closure != null
                            && closure.at().plus(CLOSED_CARD_STAYS).isBefore(now)) {
                        return null;
                    }
                    return new Card(
                            piece,
                            support,
                            practice,
                            resolution,
                            answer,
                            closure == null ? null : closure.at(),
                            closure == null ? null : closure.by(),
                            hidden);
                })
                .filter(Objects::nonNull)
                .limit(MAX_CARDS)
                .toList();
    }

    private PracticePageFeedbackDTO toCard(Card card, Map<Long, ItemRefDTO> references, @Nullable Instant readAt) {
        var feedback = card.feedback();
        var practice = card.practice();
        var group = practice.getGroup();
        String name = Objects.requireNonNull(practice.getCurrentVersion().getName());
        String headline = PracticePageFeedbackBody.headlineOf(feedback.getBody());
        return new PracticePageFeedbackDTO(
                feedback.getId(),
                card.withdrawn() || headline == null ? name : headline,
                card.withdrawn() || feedback.getBody() == null
                        ? null
                        : PracticePageFeedbackBody.messageOf(feedback.getBody()),
                card.withdrawn() ? null : PracticePageFeedbackBody.nextStepOf(feedback.getBody()),
                practice.getSlug(),
                name,
                group == null ? null : group.getSlug(),
                group == null ? null : group.getName(),
                guidance(practice, "whyItMatters"),
                guidance(practice, "whatGoodLooksLike"),
                card.withdrawn()
                        ? List.of()
                        : card.evidence().stream()
                                .map(result -> new PracticePageEvidenceDTO(
                                        result.getId(),
                                        result.getReviewId(),
                                        Objects.requireNonNull(references.get(result.getItemId())),
                                        result.getSummary(),
                                        result.getRecordedAt(),
                                        result.getOutcome()))
                                .toList(),
                feedback.getCreatedAt(),
                readAt,
                WorkResolution.CLEAN_NEEDED,
                card.withdrawn()
                        ? List.of()
                        : card.resolution().cleanWork().stream()
                                .map(work -> new PracticePageCleanWorkDTO(
                                        basis.resultId(feedback.getWorkspaceId(), practice.getId(), work),
                                        work.reviewId(),
                                        Objects.requireNonNull(references.get(work.itemId())),
                                        work.at()))
                                .toList(),
                card.withdrawn() ? null : card.closedAt(),
                card.withdrawn() ? null : card.closedBy(),
                card.withdrawn() ? null : card.response(),
                card.withdrawn(),
                card.evidence().stream().anyMatch(result -> result.getEvidenceVersion() == 0));
    }

    private static @Nullable String guidance(Practice practice, String key) {
        JsonNode value =
                practice.getCurrentVersion().getDefinition().path("guidance").get(key);
        return value != null && value.isString() ? value.asString() : null;
    }

    private List<Feedback> eligibleReady(List<Feedback> pieces, boolean record) {
        return pieces.stream()
                .filter(piece -> piece.getState() != FeedbackState.READY
                        || (record ? store.check(piece, false) : gate.evaluate(piece, FeedbackCheckStage.EGRESS, false))
                                .allowed())
                .toList();
    }
}
