package de.tum.cit.aet.hephaestus.agent.gateway;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A complete workspace is refused, never silently trimmed to fit its advertised budget. */
public final class WorkspaceBudgetExceededException extends ResponseStatusException {
    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspaceBudgetExceededException(long bytes, long budget) {
        super(HttpStatus.CONTENT_TOO_LARGE, "WORKSPACE_BUDGET_EXCEEDED");
        getBody().setProperty("reasonCode", "WORKSPACE_BUDGET_EXCEEDED");
        getBody().setProperty("workspaceBytes", bytes);
        getBody().setProperty("workspaceByteBudget", budget);
    }
}
