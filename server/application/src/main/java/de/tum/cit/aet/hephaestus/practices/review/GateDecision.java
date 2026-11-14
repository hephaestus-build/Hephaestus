package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.util.List;

public sealed interface GateDecision permits GateDecision.Run, GateDecision.Skip {
    record Run(ReviewWorkspace workspace, List<Practice> matchedPractices, long rolloutRevision, ReviewOrigin origin)
            implements GateDecision {
        public Run {
            matchedPractices = List.copyOf(matchedPractices);
        }
    }

    record Skip(String reason, ReviewRequestReason eventReason) implements GateDecision {}
}
