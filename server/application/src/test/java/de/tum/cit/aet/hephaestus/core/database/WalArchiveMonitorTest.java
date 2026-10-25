package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WalArchiveMonitorTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MeterRegistry registry;
    private WalArchiveMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        monitor = new WalArchiveMonitor(jdbcTemplate, registry);
    }

    @Test
    void shouldClaimNothingWhenNothingWasReadYet() {
        assertThat(failing()).isNaN();
    }

    @Test
    void shouldClaimNothingWhenArchiveModeIsOff() {
        reads(new WalArchiveMonitor.Reading(false, NOW.minusSeconds(60), NOW));

        monitor.sample();

        assertThat(failing()).isNaN();
    }

    @Test
    void shouldReportFailingWhenAFailureFollowsTheLastSuccess() {
        reads(new WalArchiveMonitor.Reading(true, NOW.minusSeconds(600), NOW.minusSeconds(30)));

        monitor.sample();

        assertThat(failing()).isEqualTo(1);
    }

    @Test
    void shouldReportHealthyWhenASuccessFollowsTheLastFailure() {
        reads(new WalArchiveMonitor.Reading(true, NOW.minusSeconds(30), NOW.minusSeconds(600)));

        monitor.sample();

        assertThat(failing()).isZero();
    }

    @Test
    void shouldReportFailingWhenNoSegmentWasEverArchived() {
        reads(new WalArchiveMonitor.Reading(true, null, NOW.minusSeconds(30)));

        monitor.sample();

        assertThat(failing()).isEqualTo(1);
    }

    @Test
    void shouldReportNoFailureWhenNothingWasAttemptedYet() {
        reads(new WalArchiveMonitor.Reading(true, null, null));

        monitor.sample();

        assertThat(failing()).isZero();
    }

    /** The last value is unchecked after a failed read, whether it said healthy or failing. */
    @Test
    void shouldClaimNothingWhenAReadFails() {
        when(jdbcTemplate.queryForObject(eq(WalArchiveMonitor.QUERY), anyMapper()))
                .thenReturn(new WalArchiveMonitor.Reading(true, NOW.minusSeconds(30), null))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        monitor.sample();
        assertThat(failing()).isZero();
        monitor.sample();

        assertThat(failing()).isNaN();
        assertThat(registry.get("database.wal.archive.sample.failures")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldConvertAnOffsetTimeToAnInstantAndKeepANullTime() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBoolean("archiving")).thenReturn(true);
        when(row.getObject("last_archived_time", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.ofHours(2)));
        when(row.getObject("last_failed_time", OffsetDateTime.class)).thenReturn(null);

        assertThat(WalArchiveMonitor.Reading.of(row)).isEqualTo(new WalArchiveMonitor.Reading(true, NOW, null));
    }

    private void reads(WalArchiveMonitor.Reading reading) {
        when(jdbcTemplate.queryForObject(eq(WalArchiveMonitor.QUERY), anyMapper()))
                .thenReturn(reading);
    }

    private static RowMapper<WalArchiveMonitor.Reading> anyMapper() {
        return any();
    }

    private double failing() {
        return registry.get("database.wal.archive.failing").gauge().value();
    }
}
