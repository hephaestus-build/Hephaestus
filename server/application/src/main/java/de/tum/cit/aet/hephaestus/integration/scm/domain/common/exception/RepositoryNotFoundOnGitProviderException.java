package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * The provider did not expose the requested repository to the caller. This can mean a rename,
 * loss of access or deletion; it is never sufficient evidence to delete retained work.
 * Transient failures remain distinct from this unavailable-resource response.
 */
public class RepositoryNotFoundOnGitProviderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String nameWithOwner;

    public RepositoryNotFoundOnGitProviderException(String nameWithOwner) {
        this(nameWithOwner, null);
    }

    public RepositoryNotFoundOnGitProviderException(String nameWithOwner, @Nullable Throwable cause) {
        super("Repository not found on git provider: " + nameWithOwner, cause);
        this.nameWithOwner = nameWithOwner;
    }

    public String getNameWithOwner() {
        return nameWithOwner;
    }
}
