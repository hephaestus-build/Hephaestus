package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Transaction durations over PostgreSQL")
class TransactionDurationsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldTimeEachTransactionByItsBoundaryAndOutcomeWhenItEnds() {
        String outer = "Probe" + UUID.randomUUID().toString().replace("-", "") + ".outer";
        String inner = "Probe" + UUID.randomUUID().toString().replace("-", "") + ".inner";
        TransactionTemplate outerTemplate = named("de.tum.cit.aet.hephaestus.probe." + outer);
        TransactionTemplate innerTemplate = named("de.tum.cit.aet.hephaestus.probe." + inner);
        innerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outerTemplate.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            innerTemplate.executeWithoutResult(innerStatus -> {
                jdbc.queryForObject("SELECT 1", Integer.class);
                innerStatus.setRollbackOnly();
            });
        });

        Timer committed = timer(outer, "commit");
        Timer rolledBack = timer(inner, "rollback");
        assertThat(committed.count()).isEqualTo(1);
        assertThat(rolledBack.count()).isEqualTo(1);
        assertThat(committed.totalTime(TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(rolledBack.totalTime(TimeUnit.NANOSECONDS));
        assertThat(Arrays.stream(committed.takeSnapshot().histogramCounts())
                        .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
                        .toList())
                .contains(1.0, 5.0, 30.0);
    }

    private TransactionTemplate named(String name) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName(name);
        return template;
    }

    private Timer timer(String boundary, String outcome) {
        return Objects.requireNonNull(registry.find(CoreMetrics.DATABASE_TRANSACTION_DURATION)
                .tags("boundary", boundary, "outcome", outcome)
                .timer());
    }
}
