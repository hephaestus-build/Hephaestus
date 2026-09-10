package de.tum.cit.aet.hephaestus.workspace.spi;

import java.time.Instant;
import java.util.List;

/** Approved access facts for external delivery; pending requests never confer eligibility. */
public interface WorkspaceApprovedAccessQuery {
    List<Approval> currentApprovals(Long workspaceId, Instant now);

    record Approval(Long requestId, Long accountId, Instant expiresAt, List<Long> teamIds, List<Long> identityLinkIds) {
        public Approval {
            teamIds = List.copyOf(teamIds);
            identityLinkIds = List.copyOf(identityLinkIds);
        }
    }
}
