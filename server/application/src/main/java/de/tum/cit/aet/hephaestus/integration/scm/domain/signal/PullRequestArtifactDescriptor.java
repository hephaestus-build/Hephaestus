package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITHUB_PULL_REQUEST;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITHUB_PULL_REQUEST_REVIEW;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITLAB_MERGE_REQUEST;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITLAB_NOTE;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declare;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declareManualRequest;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declareRecommended;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The pull/merge request as a reviewable artifact.
 *
 * <p>Declared here in the shared SCM domain rather than in either vendor package, so there is exactly
 * one vocabulary for what happens to a pull request; each vendor then declares which part it can deliver.
 *
 * <p>Unconditional on purpose — a descriptor that came and went with a feature flag would make the
 * vocabulary itself configuration.
 */
@Component
public class PullRequestArtifactDescriptor implements ArtifactDescriptor {

    private static final List<Signal> SIGNALS = List.of(
            // Recommended signals are the moments where work arrives to look at — a merge or a close is the
            // end of the story, and a submitted review is about somebody else's conduct.
            declareRecommended(
                    ScmSignals.PULL_REQUEST_OPENED, "Opened", Set.of(GITHUB_PULL_REQUEST, GITLAB_MERGE_REQUEST)),
            declareRecommended(
                    ScmSignals.PULL_REQUEST_READY,
                    "Marked ready for review",
                    Set.of(GITHUB_PULL_REQUEST, GITLAB_MERGE_REQUEST)),
            // GitHub names the push; GitLab raises it from a merge-request update that moved the head.
            declareRecommended(
                    ScmSignals.PULL_REQUEST_SYNCHRONIZED,
                    "New commits pushed",
                    Set.of(GITHUB_PULL_REQUEST, GITLAB_MERGE_REQUEST)),
            // GitHub has a dedicated review event; GitLab splits the same fact across an approval on the
            // merge request and a review note.
            declare(
                    ScmSignals.PULL_REQUEST_REVIEWED,
                    "Review submitted",
                    Set.of(GITHUB_PULL_REQUEST_REVIEW, GITLAB_MERGE_REQUEST, GITLAB_NOTE)),
            declare(ScmSignals.PULL_REQUEST_MERGED, "Merged", Set.of(GITHUB_PULL_REQUEST, GITLAB_MERGE_REQUEST)),
            declare(
                    ScmSignals.PULL_REQUEST_CLOSED,
                    "Closed without merging",
                    Set.of(GITHUB_PULL_REQUEST, GITLAB_MERGE_REQUEST)),
            // No ingested event raises this one: it is somebody asking for a review by hand. Declaring the
            // empty provenance is what stops a vendor from claiming it can raise it.
            declareManualRequest(ScmSignals.PULL_REQUEST_MANUAL_REVIEW, "Review requested by hand"));

    @Override
    public ArtifactKind kind() {
        return ScmSignals.PULL_REQUEST;
    }

    @Override
    public String displayName() {
        return "Pull or merge request";
    }

    @Override
    public List<Signal> signals() {
        return SIGNALS;
    }

    @Override
    public Set<ActorRole> roles() {
        return Set.of(ActorRole.AUTHOR, ActorRole.ASSIGNEE, ActorRole.REVIEWER, ActorRole.MERGER);
    }

    @Override
    public Set<FeedbackLane> lanes() {
        // A pull request carries a diff, so a finding about it can be anchored to a position in one.
        return Set.of(FeedbackLane.IN_CONTEXT_SUMMARY, FeedbackLane.IN_CONTEXT_INLINE);
    }

    @Override
    public List<ReviewLimitation> reviewLimitations() {
        return List.of(new ReviewLimitation(
                "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                "Repository evidence does not establish behavior in a deployed runtime."));
    }

    @Override
    public boolean reviewable() {
        return true;
    }
}
