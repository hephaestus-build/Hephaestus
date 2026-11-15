package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public final class RepositoryExcludedFromWorkspacesException extends ErrorResponseException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RepositoryExcludedFromWorkspacesException(String nameWithOwner) {
        super(HttpStatus.CONFLICT, problem(nameWithOwner), null);
    }

    private static ProblemDetail problem(String nameWithOwner) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                LoggingUtils.sanitizeForLog(
                        "This instance keeps “" + nameWithOwner + "” out of workspaces, so it cannot be monitored."));
        problem.setTitle("Repository kept out of workspaces");
        return problem;
    }
}
