package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Auth-audit port for product feedback administration, so {@code productfeedback} never reaches into
 * {@code core.auth.audit}. The details name repositories and ids only, never report text or a reporter.
 *
 * <p>Each method stages its event in the caller's transaction and reports a failed write, so the change
 * rolls back instead of going unaudited.
 */
public interface ProductFeedbackAudit {
    /** @return whether the event was staged in the caller's transaction */
    boolean recordDestinationsChanged(
            long actingAccountId,
            @Nullable String publicRepository,
            @Nullable String privateRepository,
            boolean paused,
            int noticeVersion);

    /** @return whether the event was staged in the caller's transaction */
    boolean recordCleanupConfirmed(long actingAccountId, UUID cleanupId, String step, String repository);

    /** Records that an admin sent a saved report into filing. Holds the report's id only, never its text. */
    boolean recordFiledByAdmin(long actingAccountId, UUID feedbackId);
}
