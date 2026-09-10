package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchedulerLockFixtureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LockProvider locks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldPreserveSchedulerLocksWhileCleaningApplicationData() {
        String name = "fixture-" + UUID.randomUUID();
        var configuration = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
        var first = locks.lock(configuration).orElseThrow();
        try {
            try {
                databaseTestUtils.cleanDatabase();
                assertThat(locks.lock(configuration)).isEmpty();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = ?", Integer.class, name))
                        .isEqualTo(1);
            } finally {
                first.unlock();
            }

            var next = locks.lock(configuration).orElseThrow();
            try {
                assertThat(locks.lock(configuration)).isEmpty();
            } finally {
                next.unlock();
            }
        } finally {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }
}
