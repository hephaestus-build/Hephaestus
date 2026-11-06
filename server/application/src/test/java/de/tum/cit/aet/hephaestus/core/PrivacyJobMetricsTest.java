package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class PrivacyJobMetricsTest extends BaseUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);

    @Test
    void recordsCompletedAndAffectedCountersPerJob() {
        metrics.record(Job.EXPORT_GENERATION, Outcome.FAILURE);
        metrics.record(Job.LLM_USAGE_RETENTION, Outcome.SUCCESS);
        metrics.recordAffected(Job.EXPORT_GENERATION, 3);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_generation", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "llm_usage_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_generation")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void reportsEachJobAtZeroBeforeItsFirstRun() {
        for (Job job : Job.values()) {
            assertThat(registry.get("privacy.job.completed")
                            .tags("job", job.name().toLowerCase(Locale.ROOT), "outcome", "success")
                            .counter()
                            .count())
                    .isZero();
        }
    }

    @Test
    void carriesNoTagBeyondTheBoundedJobAndOutcomeDimensions() {
        metrics.record(Job.ACCOUNT_ERASURE, Outcome.SUCCESS);
        metrics.recordAffected(Job.ACCOUNT_ERASURE, 1);

        assertThat(registry.get("privacy.job.completed").counter().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("job", "outcome");
        assertThat(registry.get("privacy.job.affected").counter().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly("job");
    }
}
