package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.RecentSyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.RecentWindow;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The one rule for a recent window, for every provider that pages its changed work newest-first from a watermark.
 *
 * <p>A completed pass closes its window, and the watermark moves to the window's start, or to the start of the pass.
 * A pass that stopped at the page cap opens a window, or moves it to where it stopped; the window keeps the start of
 * the pass that opened it, because the changes after that start are read again from the watermark. A resumed pass
 * that failed otherwise closes its window and keeps the watermark: a cursor the provider no longer accepts would stop
 * every later run, and reading from the newest item again costs only a re-read.
 */
public final class RecentWindows {
    private RecentWindows() {}

    /**
     * Records one pass: its window, and the kind's watermark when it moves.
     *
     * @param targets      where windows and watermarks are stored
     * @param syncTargetId the sync target
     * @param kind         issues or pull requests, which also names the kind's watermark
     * @param window       the window this pass resumed, or null
     * @param passStart    when this pass started reading
     * @param pass         the pass's outcome
     * @return where the watermark moved, or null while it holds
     */
    public static @Nullable Instant record(
            SyncTargetProvider targets,
            long syncTargetId,
            SyncCursorKind kind,
            @Nullable RecentWindow window,
            Instant passStart,
            RecentSyncResult pass) {
        if (pass.result().isCompleted()) {
            Instant watermark = window == null ? passStart : window.startedAt();
            targets.updateSyncTimestamp(
                    syncTargetId,
                    switch (kind) {
                        case ISSUE -> SyncType.ISSUES;
                        case PULL_REQUEST -> SyncType.PULL_REQUESTS;
                    },
                    watermark);
            if (window != null) targets.updateRecentWindow(syncTargetId, kind, null);
            return watermark;
        }
        String resumeCursor = pass.resumeCursor();
        if (resumeCursor != null) {
            targets.updateRecentWindow(
                    syncTargetId,
                    kind,
                    new RecentWindow(resumeCursor, window == null ? passStart : window.startedAt()));
        } else if (window != null && pass.result().status() == SyncResult.Status.ABORTED_ERROR) {
            targets.updateRecentWindow(syncTargetId, kind, null);
        }
        return null;
    }

    /**
     * The sync error an admin reads for an incomplete pass. A pass that stopped at the page cap is progress, not a
     * failure, so it says that the next run continues.
     */
    public static String describe(String pass, RecentSyncResult outcome) {
        return outcome.resumeCursor() != null
                ? pass + ": More changed than one run reads. The next run continues."
                : pass + ": " + outcome.result().status();
    }

    /** Where an open window resumes, or null to start at the newest item. */
    public static @Nullable String resumeCursor(@Nullable RecentWindow window) {
        return window == null ? null : window.resumeCursor();
    }
}
