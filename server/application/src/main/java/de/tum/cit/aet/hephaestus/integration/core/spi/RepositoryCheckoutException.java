package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.io.Serial;

/**
 * Why {@link RepositoryCheckouts} made no checkout. The message never names a repository that the workspace cannot
 * see, so it is safe to show to the caller.
 */
@SourceControlContract
public class RepositoryCheckoutException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Code {
        /** Unknown, not monitored, hidden from contributions, or another workspace's repository: one answer for all. */
        REPOSITORY_NOT_VISIBLE,
        /** The repository is visible, but the ref or the base does not resolve to a commit. */
        REF_NOT_FOUND,
        /** Git mirrors are disabled, no credential is available, or the mirror cannot be fetched. */
        MIRROR_UNAVAILABLE
    }

    private final Code code;

    public RepositoryCheckoutException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RepositoryCheckoutException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
