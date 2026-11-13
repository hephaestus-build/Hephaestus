package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The backlog gauges read PostgreSQL. Rows of other tests share the table, so each check is a lower bound that this
 * test's own action sets.
 */
class ActionBacklogSamplerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentActions actions;

    @Autowired
    private AgentActionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ActionBacklogSampler sampler;

    @BeforeEach
    void createSampler() {
        // Micrometer gauges hold their state object by weak reference. The fixture keeps the sampler, which owns that
        // state, for the meter's lifetime, as the Spring singleton does.
        sampler = new ActionBacklogSampler(repository, meters);
    }

    @Test
    void shouldCountAnAdmittedActionAndHowLongItHasBeenDue() {
        String key = "key:" + UUID.randomUUID();
        UUID id = actions.enqueue(new ActionRequest(
                ActionKind.EMAIL,
                null,
                "lane:" + UUID.randomUUID(),
                key,
                JsonNodeFactory.instance.objectNode().put("n", 1),
                null,
                3,
                null));
        jdbc.update("UPDATE agent_action SET next_attempt_at = now() - interval '10 minutes' WHERE id = ?", id);

        sampler.sample();

        assertThat(meters.get(CoreMetrics.ACTION_BACKLOG)
                        .tag("kind", "EMAIL")
                        .tag("state", "ADMITTED")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get(CoreMetrics.ACTION_OLDEST_DUE_SECONDS)
                        .tag("kind", "EMAIL")
                        .gauge()
                        .value())
                .as("the oldest due action waited at least as long as this one")
                .isGreaterThanOrEqualTo(600);
    }

    @Test
    void shouldCountAnActionWhoseLeaseExpiredAsDueFromTheEndOfItsLease() {
        UUID id = actions.enqueue(new ActionRequest(
                ActionKind.EMAIL,
                null,
                "lane:" + UUID.randomUUID(),
                "key:" + UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode().put("n", 1),
                null,
                3,
                null));
        // The holder's replica stopped: its claim's lease ended 20 minutes ago, and no replica took the action again.
        jdbc.update(
                "UPDATE agent_action SET state = 'EXECUTING', lease_epoch = lease_epoch + 1, attempts = attempts + 1,"
                        + " leased_until = now() - interval '20 minutes' WHERE id = ?",
                id);

        sampler.sample();

        assertThat(meters.get(CoreMetrics.ACTION_OLDEST_DUE_SECONDS)
                        .tag("kind", "EMAIL")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(1200);
    }
}
