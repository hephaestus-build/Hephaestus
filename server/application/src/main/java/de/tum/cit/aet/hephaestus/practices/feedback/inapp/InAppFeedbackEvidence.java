package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.FeedbackObservationVisibility;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What a developer's in-app feedback rests on, and what their work has said about it since: the reads every
 * surface that shows such feedback makes.
 */
@Component
@RequiredArgsConstructor
public class InAppFeedbackEvidence {

    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ObservationRepository observationRepository;
    private final PracticeRevisionRepository practiceRevisionRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;

    /**
     * The observations behind these pieces of feedback that may still be shown, per piece, newest first.
     * Feedback absent from the result has no evidence left to show and is not on the developer's page either.
     *
     * <p>The gate runs again although composition ran it, because the composed body is frozen and the gate
     * is not. Evidence whose source may no longer be cited, or whose rules cannot be verified at all, hides
     * the card; evidence measured by rules the practice has since changed stays, and
     * {@link #practiceChangedAt} closes the card instead.
     */
    public Map<UUID, List<Observation>> visibleEvidence(Long workspaceId, Collection<UUID> feedbackIds) {
        if (feedbackIds.isEmpty()) {
            return Map.of();
        }
        List<FeedbackObservationVisibility> rows =
                feedbackObservationRepository.findForVisibility(workspaceId, List.copyOf(feedbackIds));
        List<Observation> observations =
                rows.stream().map(FeedbackObservationVisibility::getObservation).toList();
        Set<UUID> authorized =
                visibilityPolicy.permitsShown(workspaceId, observations, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
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
     * When the practice behind each piece of feedback's evidence changed its review rules after the evidence
     * was measured, by feedback id. Feedback still measured by the rules in force is absent.
     *
     * <p>The moment is the first revision after the one the evidence was measured against whose review rules
     * actually differ from it — not the practice's newest revision, which a later edit to a name, a
     * why-it-matters or a group moves without touching a rule. The developer is shown this instant as the
     * date the card closed, so it has to be the date the rules moved.
     *
     * <p>The history is asked once per revision the evidence was measured against, however many observations
     * and however many cards were measured against it: a page of cards about one practice is one query, not
     * one per observation on it.
     */
    public Map<UUID, Instant> practiceChangedAt(Map<UUID, List<Observation>> evidenceByFeedback) {
        Map<MeasuredBy, Optional<Instant>> rulesChangedAt = new HashMap<>();
        Map<UUID, Instant> changedAt = new LinkedHashMap<>();
        evidenceByFeedback.forEach((feedbackId, evidence) -> {
            Instant changed = practiceChangedAt(evidence, rulesChangedAt);
            if (changed != null) {
                changedAt.put(feedbackId, changed);
            }
        });
        return changedAt;
    }

    /** The revision one observation was measured against: what the moment the rules moved depends on. */
    private record MeasuredBy(Long practiceId, int revisionNumber) {}

    private @Nullable Instant practiceChangedAt(
            List<Observation> evidence, Map<MeasuredBy, Optional<Instant>> rulesChangedAt) {
        return evidence.stream()
                .filter(observation -> currentness(observation) == ReviewClaimCurrentness.STALE)
                .map(observation -> rulesChangedAt(observation, rulesChangedAt))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private @Nullable Instant rulesChangedAt(
            Observation observation, Map<MeasuredBy, Optional<Instant>> rulesChangedAt) {
        PracticeRevision measuredBy = observation.getPracticeRevision();
        if (measuredBy != null && measuredBy.getReviewRuleFingerprint() != null) {
            String fingerprint = measuredBy.getReviewRuleFingerprint();
            Optional<Instant> changed = rulesChangedAt.computeIfAbsent(
                    new MeasuredBy(observation.getPractice().getId(), measuredBy.getRevisionNumber()),
                    key -> practiceRevisionRepository
                            .findFirstByPracticeIdAndRevisionNumberGreaterThanAndReviewRuleFingerprintNotOrderByRevisionNumberAsc(
                                    key.practiceId(), key.revisionNumber(), fingerprint)
                            .map(PracticeRevision::getCreatedAt));
            if (changed.isPresent()) {
                return changed.get();
            }
        }
        // Nothing in the history names the moment: evidence from before revisions carried a fingerprint, or a
        // staleness the fingerprints alone do not explain. The practice's current revision is then the only
        // instant we can stand behind.
        PracticeRevision current = observation.getPractice().getCurrentRevision();
        return current == null ? null : current.getCreatedAt();
    }

    /**
     * When the developer's own answer resolved this piece of feedback, or {@code null} while their answer
     * leaves it open: an answer resolves unless it disputes the feedback.
     */
    public static @Nullable Instant resolvedByDeveloperAt(@Nullable FeedbackResponseDTO response) {
        return response != null
                        && response.resolution() != null
                        && response.resolution().resolves()
                ? response.respondedAt()
                : null;
    }

    private static ReviewClaimCurrentness currentness(Observation observation) {
        return ReviewClaimCurrentness.of(observation.getPracticeRevision(), observation.getPractice());
    }

    /**
     * How the developer's work has answered each piece of feedback since it was prepared, keyed by feedback
     * id: the {@link WorkResolution} of the practice the feedback is about, read off its newest visible
     * evidence. Feedback without visible evidence is absent, since it is not shown anywhere either.
     *
     * <p>Read the way the practice standing reads its window: the same query, the same visibility gate, then
     * each claim narrowed to its latest run ({@link LatestRun#perClaim}), so that the card and the practice
     * profile's summary resolve a piece of feedback off the same observations. Work that may not be shown may
     * not resolve anything either.
     *
     * @param practiceChangedAt {@link #practiceChangedAt} over the same evidence
     * @param now the moment the caller reads at: work reviewed after it does not count
     */
    public Map<UUID, WorkResolution> workResolutions(
            Long workspaceId,
            Long developerId,
            Collection<Feedback> feedback,
            Map<UUID, List<Observation>> evidenceByFeedback,
            Map<UUID, Instant> practiceChangedAt,
            Instant now) {
        Optional<Instant> oldestPreparedAt = feedback.stream()
                .filter(piece -> evidenceByFeedback.containsKey(piece.getId()))
                .map(Feedback::getCreatedAt)
                .min(Comparator.naturalOrder());
        if (oldestPreparedAt.isEmpty()) {
            return Map.of();
        }
        List<Observation> later = observationRepository.findByDeveloperAndWorkspaceBetween(
                developerId, workspaceId, oldestPreparedAt.get(), now);
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, later, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        Map<String, List<Observation>> laterByPractice =
                LatestRun.perClaim(later.stream()
                                .filter(observation -> visible.contains(observation.getId()))
                                .toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                observation -> observation.getPractice().getSlug()));
        return workResolutionsFrom(feedback, evidenceByFeedback, practiceChangedAt, laterByPractice);
    }

    /**
     * {@link #workResolutions} over observations the caller already holds,
     * per practice slug, each claim at its latest run. Each practice's work is bundled once, however many
     * cards are about it.
     *
     * <p>Feedback closed because its practice changed is absent, as feedback without evidence is: the work
     * that came after was measured by other rules and cannot answer what these ones asked.
     */
    public static Map<UUID, WorkResolution> workResolutionsFrom(
            Collection<Feedback> feedback,
            Map<UUID, List<Observation>> evidenceByFeedback,
            Map<UUID, Instant> practiceChangedAt,
            Map<String, List<Observation>> observationsByPractice) {
        Map<String, WorkResolution.Opportunities> opportunitiesByPractice = new LinkedHashMap<>();
        Map<UUID, WorkResolution> resolutions = new LinkedHashMap<>();
        for (Feedback piece : feedback) {
            List<Observation> evidence = evidenceByFeedback.get(piece.getId());
            if (evidence == null || practiceChangedAt.containsKey(piece.getId())) {
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
