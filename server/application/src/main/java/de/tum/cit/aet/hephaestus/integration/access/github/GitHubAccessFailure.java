package de.tum.cit.aet.hephaestus.integration.access.github;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Only redacted, actionable reasons cross the provider boundary or enter a job's failure ledger. */
public class GitHubAccessFailure extends RuntimeException {
    public enum Reason {
        NOT_CONFIGURED,
        CREDENTIALS,
        SUSPENDED,
        UNINSTALLED,
        PERMISSIONS,
        TARGET_CHANGED,
        IDENTITY_CHANGED,
        AUTHORITY_LOST,
        RATE_LIMITED,
        UNAVAILABLE,
        INCOMPLETE,
        WRITE_UNCONFIRMED,
        SILENT_MODE,
        CANCELLED
    }

    private final Reason reason;
    private final @Nullable Instant retryAt;

    public GitHubAccessFailure(Reason reason, String message) {
        this(reason, message, null);
    }

    public GitHubAccessFailure(Reason reason, String message, @Nullable Instant retryAt) {
        super(message);
        this.reason = reason;
        this.retryAt = retryAt;
    }

    public Reason reason() {
        return reason;
    }

    public @Nullable Instant retryAt() {
        return retryAt;
    }
}
