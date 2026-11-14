package de.tum.cit.aet.hephaestus.core.runtime;

import de.tum.cit.aet.hephaestus.core.TransientFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ErrorHandler;

/**
 * Records a failed run of a scheduled job. The job's next run retries it, so a transient failure is a WARN; any other
 * failure is the run's verdict, an ERROR with its stack trace. Spring's default handler logs every failed run at
 * ERROR: an outage then writes one ERROR for each job on each run.
 */
final class ScheduledJobErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobErrorHandler.class);

    @Override
    public void handleError(Throwable failure) {
        if (TransientFailures.isTransient(failure)) {
            log.atWarn()
                    .addKeyValue("event.name", "scheduled.job.failed")
                    .addKeyValue("error.transient", true)
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Scheduled job failed on a transient failure; its next run retries it");
            return;
        }
        log.atError()
                .addKeyValue("event.name", "scheduled.job.failed")
                .addKeyValue("error.transient", false)
                .setCause(failure)
                .log("Scheduled job failed");
    }
}
