package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import org.jspecify.annotations.Nullable;

/** What a GitLab sync reports on its job's handle: a phase's warning, and the one cancellation that stopped it. */
public final class GitLabSyncReports {

    private GitLabSyncReports() {}

    /**
     * Reports a phase that did not complete cleanly as the job's warning, with its phase, operation and resource. The
     * job keeps the first warning's detail.
     */
    public static void warn(
            @Nullable SyncExecutionHandle handle,
            SyncResult.Status status,
            SyncPhase phase,
            String operation,
            @Nullable String resource) {
        if (handle == null || status == SyncResult.Status.COMPLETED) return;
        String outcome = switch (status) {
            case ABORTED_RATE_LIMIT -> " stopped at the provider rate limit.";
            case COMPLETED_WITH_WARNINGS -> " completed with warnings.";
            case ABORTED_ERROR -> " did not complete.";
            case COMPLETED -> throw new IllegalStateException("A completed phase has no warning");
        };
        handle.reportWarnings(SyncProgress.ofResource(phase, operation + outcome, resource, null, null));
    }

    /**
     * The cancellation checkpoints of one run. Each checkpoint stands before work that a requested cancel skips. The
     * first checkpoint that sees the cancel reports it, and every later one skips its work without reporting it again,
     * so one cancel is reported once. A cancel that arrives after the last checkpoint stopped nothing, so the run does
     * not report it.
     */
    public static final class Cancellation {

        private final @Nullable SyncExecutionHandle handle;
        private boolean stopped;

        public Cancellation(@Nullable SyncExecutionHandle handle) {
            this.handle = handle;
        }

        /** @return whether the work after this checkpoint is skipped */
        public boolean stopsHere() {
            if (stopped) return true;
            if (handle == null || !handle.isCancellationRequested()) return false;
            handle.reportCancelled();
            stopped = true;
            return true;
        }

        /** The job's handle, for progress and warnings; {@code null} outside a recorded job. */
        public @Nullable SyncExecutionHandle handle() {
            return handle;
        }
    }
}
