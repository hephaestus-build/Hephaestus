package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether an automatic package's summary reads exactly as the summary last posted on the same work to the same
 * person, keyed the way the ledger records it: by this review's observations. Each summary's first line is its own
 * review's marker, which nobody reads.
 */
@Component
class RepeatedSummaryCheck {

    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;

    RepeatedSummaryCheck(ObservationRepository observationRepository, FeedbackRepository feedbackRepository) {
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
    }

    boolean repeatsLastPosted(FeedbackDispatch dispatch, AgentJob job) {
        List<Observation> observations = observationRepository.findByAgentJobId(job.getId(), dispatch.getWorkspaceId());
        if (observations.isEmpty()) {
            return false;
        }
        Observation any = observations.getFirst();
        String summary = withoutMarker(dispatch.getBody());
        return feedbackRepository
                .findLatestDeliveredNote(
                        dispatch.getWorkspaceId(),
                        any.getAboutUserId(),
                        any.getArtifactKind().value(),
                        any.getArtifactId())
                .map(RepeatedSummaryCheck::withoutMarker)
                .filter(summary::equals)
                .isPresent();
    }

    private static String withoutMarker(String summary) {
        int firstLineEnd = summary.indexOf('\n');
        return summary.startsWith(PullRequestCommentPoster.SUMMARY_MARKER_PREFIX) && firstLineEnd >= 0
                ? summary.substring(firstLineEnd + 1)
                : summary;
    }
}
