package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The real query against the test PostgreSQL, which runs with {@code archive_mode} off. */
class WalArchiveMonitorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WalArchiveMonitor monitor;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldReadTheArchiverAndClaimNothingWhenArchiveModeIsOff() {
        WalArchiveMonitor.Reading reading = Objects.requireNonNull(monitor.read());
        assertThat(reading.archiving()).isFalse();

        double failuresBefore = sampleFailures();
        monitor.sample();

        assertThat(sampleFailures()).as("the query runs").isEqualTo(failuresBefore);
        assertThat(meterRegistry.get("database.wal.archive.failing").gauge().value())
                .isNaN();
    }

    private double sampleFailures() {
        return meterRegistry
                .get("database.wal.archive.sample.failures")
                .counter()
                .count();
    }
}
