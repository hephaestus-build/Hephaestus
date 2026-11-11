package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class LlmCallBacklogIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmCallPermitRepository rows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest
    @EnumSource(
            value = AgentRunBand.class,
            names = {"BATCH", "BACKGROUND"})
    void shouldObserveEveryReviewBandWithoutTakingCapacity(AgentRunBand band) {
        long pool = pool();
        enqueue(pool, band, 2L);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasReviewCallsWaiting(pool)).isTrue();
        assertThat(observer.hasReviewCallsWaiting(pool)).isTrue();
        assertThat(rows.countUnclosed(pool)).isEqualTo(1);
        assertThat(rows.countActive(pool)).isZero();
        assertThat(rows.rates(pool).getRequests()).isZero();
    }

    @Test
    void shouldIgnoreTurnsProductFeedbackAndDemandFromAnotherEndpoint() {
        long first = pool();
        long other = pool();
        enqueue(first, AgentRunBand.INTERACTIVE, 2L);
        enqueue(first, AgentRunBand.BACKGROUND, null);
        enqueue(other, AgentRunBand.BATCH, 2L);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasReviewCallsWaiting(first)).isFalse();
        assertThat(observer.hasReviewCallsWaiting(other)).isTrue();
    }

    @Test
    void shouldIgnoreExpiredWaitersWithoutMutatingThem() {
        long pool = pool();
        UUID id = enqueue(pool, AgentRunBand.BATCH, 2L);
        jdbc.update(
                "UPDATE llm_call_permit SET renewed_at=clock_timestamp()-make_interval(secs => ?) WHERE id=?",
                1 + LlmCallPermitsTestFixtures.PROPERTIES.permitLease().toSeconds(),
                id);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasReviewCallsWaiting(pool)).isFalse();
        assertThat(rows.countUnclosed(pool)).isEqualTo(1);
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldIgnoreActiveAndClosedCallsWithoutChangingTheirDebits() {
        long pool = pool();
        UUID id = enqueue(pool, AgentRunBand.BATCH, 2L);
        transactions.executeWithoutResult(status -> assertThat(jdbc.update(
                        "UPDATE llm_call_permit SET state='ACTIVE',granted_at=clock_timestamp(),requests=1 WHERE id=? AND state='WAITING'",
                        id))
                .isEqualTo(1));
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasReviewCallsWaiting(pool)).isFalse();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        transactions.executeWithoutResult(status -> rows.release(id, "backlog"));
        assertThat(observer.hasReviewCallsWaiting(pool)).isFalse();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(1);
    }

    @Test
    void shouldRefuseAnObservationOfAnUnavailablePool() {
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThatThrownBy(() -> observer.hasReviewCallsWaiting(Long.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configured call pool is unavailable");
    }

    private long pool() {
        var pool = new LlmCallPool();
        pool.setSlug("backlog-" + UUID.randomUUID());
        pool.setDisplayName("Backlog test");
        pool.setMaxConcurrentCalls(1);
        return pools.save(pool).getId();
    }

    /** A call without a workspace is product feedback. */
    private UUID enqueue(long pool, AgentRunBand band, @Nullable Long workspace) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(
                new LlmCallPermitRepository.Request(id, pool, "backlog", band, workspace, UUID.randomUUID(), 0)));
        return id;
    }
}
