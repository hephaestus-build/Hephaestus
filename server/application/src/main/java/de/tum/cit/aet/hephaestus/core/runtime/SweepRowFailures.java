package de.tum.cit.aet.hephaestus.core.runtime;

import de.tum.cit.aet.hephaestus.core.TransientFailures;
import org.slf4j.Logger;

/**
 * Decides what a scheduled sweep does when one row fails. A transient failure, such as a database that is down, is
 * not about the row: it stops the pass, and {@link ScheduledJobErrorHandler} records the run once. Any other failure
 * belongs to the row: the sweep records it as an ERROR and goes on with its other rows, and its next run tries the row
 * again. A deadlock or a lock timeout counts as transient too, so contention on one row ends the pass until the next run.
 */
public final class SweepRowFailures {
    private SweepRowFailures() {}

    public static void skipUnlessTransient(
            Logger log, String sweep, long workspaceId, Object rowId, RuntimeException failure) {
        if (TransientFailures.isTransient(failure)) throw failure;
        log.atError()
                .addKeyValue("event.name", "scheduled.row.failed")
                .addKeyValue("sweep.name", sweep)
                .addKeyValue("workspace.id", workspaceId)
                .addKeyValue("row.id", rowId)
                .setCause(failure)
                .log("A scheduled sweep skipped one row that failed; its next run tries the row again");
    }
}
