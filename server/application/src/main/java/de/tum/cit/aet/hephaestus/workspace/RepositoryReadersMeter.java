package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.metrics.WorkspaceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts monitored repositories by visibility class, proof state and proof reason. A repository whose readers stay
 * unproven waits in mentoring, and a listing that keeps stopping (rate limit, partial answer) does not show in the sync
 * status, so operators read it here. Every replica measures, so the gauge does not depend on which replica syncs.
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("Bounded by the at most eight combinations of class, proof state and reason: one grouped count of"
        + " every workspace's monitored repositories for an operator gauge, with no repository content")
public class RepositoryReadersMeter {

    private static final Logger log = LoggerFactory.getLogger(RepositoryReadersMeter.class);

    private final RepositoryToMonitorRepository monitors;
    private final MultiGauge readers;

    public RepositoryReadersMeter(RepositoryToMonitorRepository monitors, MeterRegistry meterRegistry) {
        this.monitors = monitors;
        this.readers = MultiGauge.builder(WorkspaceMetrics.MONITORED_REPOSITORY_READERS)
                .description("Monitored repositories by visibility class, proof state and proof reason")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void measure() {
        try {
            readers.register(
                    monitors.countByReadersProof().stream()
                            .map(count -> MultiGauge.Row.of(
                                    Tags.of(
                                            "visibility_class",
                                                    count.visibilityClass().name(),
                                            "proof_state", count.proofState().name(),
                                            "proof_reason", count.proofReason().name()),
                                    count.monitors()))
                            .toList(),
                    true);
        } catch (RuntimeException e) {
            // Keep the last counts: a database blip must not read as "every repository is proven".
            log.warn(
                    "Repository readers count failed, keeping last values: errorType={}",
                    e.getClass().getName());
        }
    }
}
