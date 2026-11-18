package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceResultVisibility;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Whether workspace admins see practice results per person. While the workspace has it off, the admin reads name no
 * developer on results, problems and private feedback, and refuse a filter by person. Feedback on the work keeps its
 * developer, because approving it authorizes a comment to that person on their work.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PerPersonResults {
    private final WorkspaceResultVisibility workspaces;

    public boolean shown(long workspaceId) {
        return workspaces.perPersonResultsForAdmins(workspaceId);
    }

    /** Refuses a filter by person while per-person results are off. */
    public void requirePersonFilterAllowed(long workspaceId, @Nullable Long developerId) {
        if (developerId != null && !shown(workspaceId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Per-person results are off in this workspace, so results cannot be filtered by person.");
        }
    }
}
