package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITHUB_ISSUES;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.GITLAB_ISSUE;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declare;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declareManualRequest;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmEventSources.declareRecommended;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The issue as a reviewable artifact.
 *
 * <p>An issue has no diff, so it has no inline lane or reviewer relation.
 */
@Component
public class IssueArtifactDescriptor implements ArtifactDescriptor {

    private static final List<Signal> SIGNALS = List.of(
            declareRecommended(ScmSignals.ISSUE_OPENED, "Opened", Set.of(GITHUB_ISSUES, GITLAB_ISSUE)),
            // Both providers raise it, but from different vocabularies: GitHub has an action per edit,
            // GitLab one update action whose changes diff says what moved. Neither can report a type
            // change on GitLab, which is why the display name does not promise one.
            declareRecommended(ScmSignals.ISSUE_UPDATED, "Details changed", Set.of(GITHUB_ISSUES, GITLAB_ISSUE)),
            declare(ScmSignals.ISSUE_CLOSED, "Closed", Set.of(GITHUB_ISSUES, GITLAB_ISSUE)),
            declareManualRequest(ScmSignals.ISSUE_MANUAL_REVIEW, "Review requested by hand"));

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "IMPLEMENTATION_NOT_OBSERVED",
                        "Issue evidence does not establish whether the described work was implemented correctly.")),
                List.of(new ReviewStateDimension(
                        "state",
                        "State",
                        List.of(
                                new ReviewStateDimension.Value("OPEN", "Open"),
                                new ReviewStateDimension.Value("CLOSED", "Closed")),
                        Set.of("OPEN", "CLOSED"))),
                Set.of(),
                Set.of());
    }

    @Override
    public ArtifactKind kind() {
        return ScmSignals.ISSUE;
    }

    @Override
    public String displayName() {
        return "Issue";
    }

    @Override
    public List<Signal> signals() {
        return SIGNALS;
    }

    @Override
    public Set<ActorRole> roles() {
        return Set.of(ActorRole.AUTHOR, ActorRole.ASSIGNEE);
    }

    @Override
    public Set<FeedbackLane> lanes() {
        return Set.of(FeedbackLane.IN_CONTEXT_SUMMARY);
    }

    @Override
    public boolean reviewable() {
        return true;
    }
}
