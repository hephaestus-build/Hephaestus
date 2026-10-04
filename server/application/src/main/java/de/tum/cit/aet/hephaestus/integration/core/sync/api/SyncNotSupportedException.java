package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.io.Serial;

/**
 * Thrown when a manual sync/backfill is requested for a kind with no registered
 * {@code IntegrationSyncRunner} (or, for {@code BACKFILL}, one that doesn't support it) — the
 * graceful-degradation path for a kind whose runner is not wired.
 *
 * <p>Mapped to 409 by {@link SyncController}'s local exception handler — deliberately not
 * {@code @ResponseStatus} (see {@link de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateConflictException}
 * for why a blanket global advice can silently out-rank that annotation).
 */
public class SyncNotSupportedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient IntegrationKind kind;

    public SyncNotSupportedException(IntegrationKind kind) {
        super("The integration kind " + kind + " does not support manual sync.");
        this.kind = kind;
    }

    /** The unsupported integration kind — surfaced as a machine-readable 409 extension member. */
    public IntegrationKind kind() {
        return kind;
    }
}
