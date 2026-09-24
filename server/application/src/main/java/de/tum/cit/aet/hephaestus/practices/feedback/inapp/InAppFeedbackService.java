package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the current developer's own practice feedback, and records that they read it.
 *
 * <p>Self-scoped with no way to ask about anybody else: the recipient is resolved from the security
 * context, never from a parameter. That is not a convenience — the pull is what makes this surface safe,
 * and a {@code userId} parameter would turn a private surface into a roster.
 */
@Service
@RequiredArgsConstructor
public class InAppFeedbackService {

    /**
     * How many cards one read returns. The practice surface is a short list of habits to work on, not a log;
     * a developer who has to scroll it has been handed more than they can act on.
     */
    private static final int MAX_CARDS = 20;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final UserRepository userRepository;

    /**
     * The current developer's practice pages.
     *
     * <p>Opening a card marks it delivered for the recipient. An administrator's read-only user view
     * must not claim that the recipient opened it.
     *
     * @return empty when the caller is not a synced developer, exactly as the sibling read models do —
     *     a first login before any work has been mirrored is not an error
     */
    @Transactional
    public List<InAppFeedbackDTO> getInAppFeedback(Long workspaceId) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            return List.of();
        }
        Long recipientUserId = currentUser.get().getId();
        List<Feedback> units = feedbackRepository.findReadableInAppForRecipient(
                workspaceId, recipientUserId, PageRequest.of(0, MAX_CARDS));
        if (units.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Observation>> evidenceByUnit = visibleEvidence(workspaceId, units);

        List<InAppFeedbackDTO> cards = new ArrayList<>(units.size());
        List<UUID> toMarkDelivered = new ArrayList<>(units.size());
        for (Feedback unit : units) {
            List<Observation> evidence = evidenceByUnit.getOrDefault(unit.getId(), List.of());
            // Hidden, not deleted. A unit whose measurements have since gone non-current — the practice's
            // review rules changed, or an evidence source's authorization was withdrawn — must stop being
            // shown, but the ledger still records that we said it, which is the whole point of a ledger.
            if (evidence.isEmpty()) {
                continue;
            }
            cards.add(toCard(unit, evidence));
            if (UserViewContextHolder.get() == null && unit.getDeliveryState() == FeedbackDeliveryState.PREPARED) {
                toMarkDelivered.add(unit.getId());
            }
        }
        Instant now = Instant.now();
        for (UUID id : toMarkDelivered) {
            feedbackRepository.markInAppDelivered(workspaceId, id, now);
        }
        return List.copyOf(cards);
    }

    /**
     * The observations behind this page's units, narrowed to what may still be shown.
     *
     * <p>The gate runs again here even though composition ran it: the composed body is frozen text and
     * the gate is not, so a claim can lose currentness or authorization after it was written. One batch
     * query for the page, one authorization round trip, as {@code ObservationVisibilityPolicy} is built
     * for.
     */
    private Map<UUID, List<Observation>> visibleEvidence(Long workspaceId, List<Feedback> units) {
        List<UUID> ids = units.stream().map(Feedback::getId).toList();
        var rows = feedbackObservationRepository.findForVisibility(workspaceId, ids);
        List<Observation> all = rows.stream()
                .map(FeedbackObservationRepository.FeedbackObservationVisibility::getObservation)
                .toList();
        Set<UUID> visible = visibilityPolicy.permitsAll(workspaceId, all, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        Map<UUID, List<Observation>> byUnit = new LinkedHashMap<>();
        for (var row : rows) {
            Observation observation = row.getObservation();
            if (observation.getId() == null || !visible.contains(observation.getId())) {
                continue;
            }
            byUnit.computeIfAbsent(row.getFeedbackId(), key -> new ArrayList<>())
                    .add(observation);
        }
        // Newest occurrence first: the card's evidence list reads as "this is still happening", which the
        // oldest-first order would invert.
        Comparator<Observation> newestFirst = Comparator.comparing(
                Observation::getObservedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
        byUnit.values().forEach(list -> list.sort(newestFirst));
        return byUnit;
    }

    private static InAppFeedbackDTO toCard(Feedback unit, List<Observation> evidence) {
        Practice practice = evidence.getFirst().getPractice();
        PracticeGroup group = practice == null ? null : practice.getGroup();
        String headline = InAppFeedbackBody.headlineOf(unit.getBody());
        return new InAppFeedbackDTO(
                unit.getId(),
                headline != null ? headline : (practice == null ? "" : practice.getName()),
                InAppFeedbackBody.messageOf(unit.getBody()),
                practice == null ? "" : practice.getSlug(),
                practice == null ? "" : practice.getName(),
                group == null ? null : group.getSlug(),
                group == null ? null : group.getName(),
                practice == null ? null : practice.getWhyItMatters(),
                practice == null ? null : practice.getWhatGoodLooksLike(),
                evidence.stream().map(InAppEvidenceDTO::from).toList(),
                evidence.size(),
                unit.getCreatedAt(),
                unit.getDeliveredAt());
    }
}
