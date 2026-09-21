package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.FeedbackObservationVisibility;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * What a developer's in-app feedback rests on, and what their work has said about it since: the two reads
 * every surface that shows such feedback makes, so that the developer's page and the practice profile
 * cannot disagree about which pieces of feedback are still shown or which the work has resolved.
 */
@Component
@RequiredArgsConstructor
public class InAppFeedbackEvidence {

    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ObservationRepository observationRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final EvidenceAuthorization evidenceAuthorization;

    /**
     * The observations behind these pieces of feedback that may still be shown, per piece, newest first.
     * Feedback absent from the result has no evidence left to show and is not on the developer's page either.
     *
     * <p>The gate runs again here even though composition ran it: the composed body is frozen text and the
     * gate is not, so a claim can lose currentness or authorization after it was written. The two are not
     * the same loss. Evidence whose source may no longer be cited is hidden, and the card with it. Evidence
     * measured by review rules the practice has since changed stays, and {@link #practiceChangedAt} says
     * when the practice moved on: the card is closed by that, and closed is something the developer may
     * see. A claim whose rules cannot be verified at all is hidden as before. One batch query and one
     * authorization round trip, as {@link EvidenceAuthorization#permitsAll} is built for.
     */
    public Map<UUID, List<Observation>> visibleEvidence(Long workspaceId, Collection<UUID> feedbackIds) {
        if (feedbackIds.isEmpty()) {
            return Map.of();
        }
        List<FeedbackObservationVisibility> rows =
                feedbackObservationRepository.findForVisibility(workspaceId, List.copyOf(feedbackIds));
        List<Observation> verifiable = rows.stream()
                .map(FeedbackObservationVisibility::getObservation)
                .filter(observation -> currentness(observation) != ReviewClaimCurrentness.UNVERIFIABLE)
                .toList();
        Set<UUID> authorized =
                evidenceAuthorization.permitsAll(workspaceId, verifiable, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        Map<UUID, List<Observation>> byFeedback = new LinkedHashMap<>();
        for (FeedbackObservationVisibility row : rows) {
            Observation observation = row.getObservation();
            if (observation.getId() != null && authorized.contains(observation.getId())) {
                byFeedback
                        .computeIfAbsent(row.getFeedbackId(), ignored -> new ArrayList<>())
                        .add(observation);
            }
        }
        // Newest occurrence first: the evidence reads as "this is still happening", which the oldest-first
        // order would invert.
        Comparator<Observation> newestFirst =
                Comparator.comparing(Observation::getObservedAt, Comparator.reverseOrder());
        byFeedback.values().forEach(list -> list.sort(newestFirst));
        return byFeedback;
    }

    /**
     * When the practice behind this evidence changed its review rules after the evidence was measured, or
     * {@code null} while the rules are the ones it was measured by. Read off the practice's current
     * revision, which every edit appends: the instant is when the practice last changed, and the evidence
     * being stale against it is what says the change reached the rules.
     */
    public static @Nullable Instant practiceChangedAt(List<Observation> evidence) {
        return evidence.stream()
                .filter(observation -> currentness(observation) == ReviewClaimCurrentness.STALE)
                .map(observation -> observation.getPractice().getCurrentRevision())
                .filter(Objects::nonNull)
                .map(PracticeRevision::getCreatedAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * When a piece of feedback stopped being open, or {@code null} while it is: resolved by the work or by
     * the developer, or closed because the practice changed — whichever came first, which is what the card
     * and the summary report ({@code docs/contributor/practice-review-glossary.mdx} § How feedback resolves).
     */
    public static @Nullable Instant closedAt(
            @Nullable Instant resolvedByWorkAt,
            @Nullable Instant resolvedByDeveloperAt,
            @Nullable Instant practiceChangedAt) {
        return Stream.of(resolvedByWorkAt, resolvedByDeveloperAt, practiceChangedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static ReviewClaimCurrentness currentness(Observation observation) {
        return ReviewClaimCurrentness.of(observation.getPracticeRevision(), observation.getPractice());
    }

    /**
     * How the developer's work has answered each piece of feedback since it was prepared, keyed by feedback
     * id: the {@link WorkResolution} of the practice the feedback is about, read off its newest visible
     * evidence. Feedback without visible evidence is absent, since it is not shown anywhere either.
     *
     * <p>One query for the developer's observations since the oldest piece of feedback was prepared and one
     * authorization round trip, whatever the number of cards; the same visibility gate as the evidence, since
     * work that may not be shown may not resolve anything either.
     */
    public Map<UUID, WorkResolution> workResolutions(
            Long workspaceId,
            Long developerId,
            Collection<Feedback> feedback,
            Map<UUID, List<Observation>> evidenceByFeedback) {
        Optional<Instant> oldestPreparedAt = feedback.stream()
                .filter(piece -> evidenceByFeedback.containsKey(piece.getId()))
                .map(Feedback::getCreatedAt)
                .min(Comparator.naturalOrder());
        if (oldestPreparedAt.isEmpty()) {
            return Map.of();
        }
        List<Observation> later = observationRepository.findRecentByDeveloperAndWorkspace(
                developerId, workspaceId, oldestPreparedAt.get(), false, Pageable.unpaged());
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, later, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        Map<String, List<Observation>> laterByPractice = later.stream()
                .filter(observation -> visible.contains(observation.getId()))
                .collect(Collectors.groupingBy(
                        observation -> observation.getPractice().getSlug()));
        return workResolutions(feedback, evidenceByFeedback, laterByPractice);
    }

    /**
     * {@link #workResolutions(Long, Long, Collection, Map)} over observations the caller already holds, per
     * practice slug: every visible observation about the developer since the oldest piece of feedback was
     * prepared, each piece of work at its latest run. Each practice's work is bundled once, however many
     * cards are about it.
     *
     * <p>Feedback closed because its practice changed is absent, as feedback without evidence is: the work
     * that came after was measured by other rules and cannot answer what these ones asked.
     */
    public Map<UUID, WorkResolution> workResolutions(
            Collection<Feedback> feedback,
            Map<UUID, List<Observation>> evidenceByFeedback,
            Map<String, List<Observation>> observationsByPractice) {
        Map<String, WorkResolution.Opportunities> opportunitiesByPractice = new LinkedHashMap<>();
        Map<UUID, WorkResolution> resolutions = new LinkedHashMap<>();
        for (Feedback piece : feedback) {
            List<Observation> evidence = evidenceByFeedback.get(piece.getId());
            if (evidence == null || practiceChangedAt(evidence) != null) {
                continue;
            }
            String practiceSlug = evidence.getFirst().getPractice().getSlug();
            WorkResolution.Opportunities opportunities = opportunitiesByPractice.computeIfAbsent(
                    practiceSlug,
                    slug -> WorkResolution.Opportunities.of(
                            observationsByPractice.getOrDefault(slug, List.of()), Instant.EPOCH));
            resolutions.put(piece.getId(), opportunities.resolve(piece.getCreatedAt()));
        }
        return resolutions;
    }
}
