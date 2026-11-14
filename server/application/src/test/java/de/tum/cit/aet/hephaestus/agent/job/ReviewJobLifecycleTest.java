package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewJobLifecycleTest extends BaseUnitTest {

    private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");

    @Test
    void shouldStartAQueuedJob() {
        var job = new ReviewJob();
        job.start(AT);

        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(AT);
    }

    @Test
    void shouldRefuseToStartAJobThatIsRunningAlready() {
        var job = new ReviewJob();
        job.start(AT);

        assertThatThrownBy(() -> job.start(AT.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(job.getStartedAt()).isEqualTo(AT);
    }

    @Test
    void shouldRefuseToCancelAFailedJob() {
        var job = new ReviewJob();
        job.fail(AT);

        assertThatThrownBy(() -> job.cancel(ReviewJobCancellationReason.DRAIN_GRACEFUL, "cancelled", AT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(job.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
    }
}
