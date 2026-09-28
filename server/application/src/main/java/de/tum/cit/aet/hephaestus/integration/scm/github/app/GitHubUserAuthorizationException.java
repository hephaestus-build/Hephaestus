package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import org.jspecify.annotations.Nullable;

/** GitHub could not confirm what the person who authorized the GitHub App can reach. Carries no token. */
public class GitHubUserAuthorizationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GitHubUserAuthorizationException(String message) {
        super(message);
    }

    public GitHubUserAuthorizationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
