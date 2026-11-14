package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.unit.DataSize;

class DatabaseSizeMonitorTest extends BaseUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    void shouldReportTheDatabaseSizeAndTheVolumeThatTheChartGaveIt() {
        var monitor = new DatabaseSizeMonitor(
                jdbcTemplate, new DatabaseVolumeProperties(DataSize.ofMegabytes(20_480)), meters);
        when(jdbcTemplate.queryForObject(DatabaseSizeMonitor.QUERY, Long.class)).thenReturn(18_000_000_000L);

        monitor.sample();

        assertThat(gauge(CoreMetrics.DATABASE_SIZE)).isEqualTo(18_000_000_000d);
        assertThat(gauge(CoreMetrics.DATABASE_VOLUME_CAPACITY)).isEqualTo(21_474_836_480d);
    }

    @Test
    void shouldReportNoValueBeforeTheFirstReadAndAfterAFailedRead() {
        var monitor = new DatabaseSizeMonitor(jdbcTemplate, new DatabaseVolumeProperties(null), meters);
        assertThat(gauge(CoreMetrics.DATABASE_SIZE)).isNaN();
        when(jdbcTemplate.queryForObject(DatabaseSizeMonitor.QUERY, Long.class))
                .thenReturn(1_000L)
                .thenThrow(new DataAccessResourceFailureException("unreachable"));

        monitor.sample();
        monitor.sample();

        assertThat(gauge(CoreMetrics.DATABASE_SIZE)).isNaN();
        assertThat(meters.get(CoreMetrics.DATABASE_SIZE_SAMPLER_FAILURES)
                        .counter()
                        .count())
                .isEqualTo(1);
        // An external database has no volume that the chart sized.
        assertThat(meters.find(CoreMetrics.DATABASE_VOLUME_CAPACITY).gauge()).isNull();
    }
}
