package de.tum.cit.aet.hephaestus.core;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The single owner of the two privacy-job meters. Micrometer registers a meter once per name and tag
 * set, so a second registration site would decide the description by whichever job scraped first;
 * the enums below are also the only thing keeping the tag values bounded.
 *
 * <p>Lives in the {@code core} base package rather than beside the auth jobs because the jobs that
 * publish these meters sit in two application modules, and a module reaches {@code core.auth} only
 * through its {@code auth-spi} named interface.
 */
@ConditionalOnServerRole
@Component
public class PrivacyJobMetrics {

    public enum Job {
        ACCOUNT_ERASURE("account_erasure"),
        EXPORT_GENERATION("export_generation"),
        EXPORT_RETENTION("export_retention"),
        LLM_USAGE_RETENTION("llm_usage_retention");

        private final String tag;

        Job(String tag) {
            this.tag = tag;
        }
    }

    public enum Outcome {
        SUCCESS("success"),
        /**
         * The run finished without error but left work it was eligible to do — a bounded pass that ran
         * out of budget. Separate from {@link #SUCCESS} so an alert on the daily run cannot read a
         * permanently truncated job as healthy.
         */
        INCOMPLETE("incomplete"),
        FAILURE("failure");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry registry;

    public PrivacyJobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Job job, Outcome outcome) {
        Counter.builder(CoreMetrics.PRIVACY_JOB_COMPLETED)
                .description("Terminal privacy-job executions, tagged by bounded job and outcome values.")
                .tag("job", job.tag)
                .tag("outcome", outcome.tag)
                .register(registry)
                .increment();
    }

    public void recordAffected(Job job, long affected) {
        Counter.builder(CoreMetrics.PRIVACY_JOB_AFFECTED)
                .description("Rows or subjects affected by privacy-job executions.")
                .tag("job", job.tag)
                .register(registry)
                .increment((double) affected);
    }
}
