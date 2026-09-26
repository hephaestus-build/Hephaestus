package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import java.io.Serial;

/**
 * An expected refusal to change a GitLab connection's webhook signing mode: the connection is not active,
 * or GitLab or the configuration kept the webhook out of the requested mode. Answered as 409; any other
 * failure stays a 500.
 */
public class GitLabSigningModeConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GitLabSigningModeConflictException(String message) {
        super(message);
    }
}
