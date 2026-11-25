package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the release of the public usage page and counts each run by its outcome, so an operator sees a release that
 * keeps failing (ADR 0153). A run that finds nothing due is a success.
 */
@ConditionalOnServerRole
@Service
class UsageReleaseService {

    private static final Logger log = LoggerFactory.getLogger(UsageReleaseService.class);

    private final UsageReleaser releaser;
    private final Clock clock;
    private final Counter successes;
    private final Counter failures;
    private final Counter releases;

    /**
     * Both outcomes are registered at start, so the first failed run raises the failure count from zero and the alert
     * sees it; a counter born with its first failure has no earlier sample to rise from.
     */
    UsageReleaseService(UsageReleaser releaser, Clock clock, MeterRegistry meters) {
        this.releaser = releaser;
        this.clock = clock;
        this.successes = outcome(meters, "success");
        this.failures = outcome(meters, "failure");
        // Tells the operator when to save a copy of the released rows; it counts only after the commit, so it is a
        // prompt, not a proof that a copy is complete.
        this.releases = Counter.builder(PracticeMetrics.USAGE_RELEASES)
                .description("Half-years of Hephaestus in numbers released and committed")
                .register(meters);
    }

    /** @return how many periods this run released */
    public int releaseDue() {
        try {
            var released = releaser.releaseDue(clock.instant());
            // After the transaction committed, so a period that rolled back is never logged as released.
            released.forEach(period -> log.info("Released public usage period: period={}", period.key()));
            releases.increment(released.size());
            successes.increment();
            return released.size();
        } catch (ReleaseDiscarded discarded) {
            // Not a failure: an admin changed the settings while the run counted, and the next run counts again.
            log.info("Discarded a public usage release: the settings changed while it ran");
            successes.increment();
            return 0;
        } catch (RuntimeException failure) {
            failures.increment();
            throw failure;
        }
    }

    private static Counter outcome(MeterRegistry meters, String outcome) {
        return Counter.builder(PracticeMetrics.USAGE_RELEASE_RUNS)
                .description("Runs of the release of Hephaestus in numbers, by outcome")
                .tag("outcome", outcome)
                .register(meters);
    }
}
