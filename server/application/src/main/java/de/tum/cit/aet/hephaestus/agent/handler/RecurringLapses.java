package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppFeedbackRouter;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Uses earlier negative observations to shorten repeated feedback on the work. The window and
 * threshold match the practice page ({@link InAppFeedbackRouter}).
 */
@Component
class RecurringLapses {

    /** Distinct earlier pieces of work a practice was negative on before it counts as a habit. */
    static final int RECURRING_MIN_ARTIFACTS = 3;

    private static final int WINDOW_DAYS = InAppFeedbackRouter.PATTERN_WINDOW_DAYS;
    private static final PageRequest RECENT = PageRequest.of(0, 200);

    private final ObservationRepository observationRepository;
    private final Clock clock;

    RecurringLapses(ObservationRepository observationRepository, Clock clock) {
        this.observationRepository = observationRepository;
        this.clock = clock;
    }

    /**
     * The slugs among this job's negative observations that were negative for the same person on at
     * least {@link #RECURRING_MIN_ARTIFACTS} other pieces of work in the window.
     */
    Set<String> recurringSlugs(List<Observation> persisted) {
        Instant since = Instant.now(clock).minus(Duration.ofDays(WINDOW_DAYS));
        Set<String> recurring = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Observation observation : persisted) {
            if (observation.getOutcome() != Outcome.NEGATIVE || observation.getPractice() == null) {
                continue;
            }
            String slug = observation.getPractice().getSlug();
            if (slug == null || !seen.add(slug)) {
                continue;
            }
            Long artifact = observation.getArtifactId();
            // Each piece of work counts by its latest review, newest first: a lapse a draft review found
            // and the next review found fixed is not part of a habit.
            Map<Long, Outcome> latestByArtifact = new HashMap<>();
            observationRepository
                    .findRecentForSubjectAndPractice(
                            observation.getWorkspaceId(), observation.getAboutUserId(), slug, since, RECENT)
                    .forEach(o -> latestByArtifact.putIfAbsent(o.getArtifactId(), o.getOutcome()));
            long others = latestByArtifact.entrySet().stream()
                    .filter(e -> e.getValue() == Outcome.NEGATIVE && !Objects.equals(e.getKey(), artifact))
                    .count();
            if (others >= RECURRING_MIN_ARTIFACTS) {
                recurring.add(slug);
            }
        }
        return recurring;
    }
}
