package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;

public final class GateDecisionTestFixtures {

    private GateDecisionTestFixtures() {}

    public static GateDecision.Run automaticRun(Workspace workspace, List<Practice> practices) {
        return new GateDecision.Run(
                WorkspacePortFixtures.reviewWorkspace(workspace),
                practices,
                workspace.getReviewSettings().getRolloutRevision(),
                ReviewOrigin.EVENT);
    }
}
