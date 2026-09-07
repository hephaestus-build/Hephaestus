package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ExportRetentionSweeperTest extends BaseUnitTest {

    @Mock
    private AccountExportService accountExportService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);

    private ExportRetentionSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new ExportRetentionSweeper(accountExportService, metrics);
    }

    @Test
    void shouldRecordSuccessAndTheExpiredCountWhenTheSweepRuns() {
        when(accountExportService.expireRetention()).thenReturn(4);

        sweeper.sweep();

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_retention")
                        .counter()
                        .count())
                .isEqualTo(4);
    }

    @Test
    void shouldRecordSuccessWhenNothingWasEligible() {
        when(accountExportService.expireRetention()).thenReturn(0);

        sweeper.sweep();

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_retention")
                        .counter()
                        .count())
                .isEqualTo(0);
    }

    @Test
    void shouldRecordFailureAndPropagateWhenTheSweepFails() {
        when(accountExportService.expireRetention()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
