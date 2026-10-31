package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.UUID;
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
            value = LlmCallClass.class,
            names = {"REQUEST", "EVENT", "SCHEDULE", "BACKFILL", "COMPOSITION"})
    void shouldObserveEveryBackgroundCallClassWithoutTakingCapacity(LlmCallClass kind) {
        long pool = pool();
        enqueue(pool, kind);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasBackgroundWaiters(pool)).isTrue();
        assertThat(observer.hasBackgroundWaiters(pool)).isTrue();
        assertThat(rows.countUnclosed(pool)).isEqualTo(1);
        assertThat(rows.countActive(pool)).isZero();
        assertThat(rows.rates(pool).getRequests()).isZero();
    }

    @Test
    void shouldIgnoreTurnsProductFeedbackAndDemandFromAnotherEndpoint() {
        long first = pool();
        long other = pool();
        enqueue(first, LlmCallClass.TURN);
        enqueue(first, LlmCallClass.PRODUCT_FEEDBACK);
        enqueue(other, LlmCallClass.REQUEST);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasBackgroundWaiters(first)).isFalse();
        assertThat(observer.hasBackgroundWaiters(other)).isTrue();
    }

    @Test
    void shouldIgnoreExpiredWaitersWithoutMutatingThem() {
        long pool = pool();
        UUID id = enqueue(pool, LlmCallClass.COMPOSITION);
        jdbc.update("UPDATE llm_call_permit SET renewed_at=clock_timestamp()-make_interval(secs => ?) WHERE id=?",
                1 + LlmCallPermitsTestFixtures.PROPERTIES.permitLease().toSeconds(), id);
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasBackgroundWaiters(pool)).isFalse();
        assertThat(rows.countUnclosed(pool)).isEqualTo(1);
        assertThat(rows.countActive(pool)).isZero();
    }

    @Test
    void shouldIgnoreActiveAndClosedCallsWithoutChangingTheirDebits() {
        long pool = pool();
        UUID id = enqueue(pool, LlmCallClass.REQUEST);
        transactions.executeWithoutResult(
                status -> assertThat(rows.grant(id, 0, "backlog")).isEqualTo(1));
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThat(observer.hasBackgroundWaiters(pool)).isFalse();
        assertThat(rows.countActive(pool)).isEqualTo(1);
        transactions.executeWithoutResult(status -> rows.release(id, "backlog"));
        assertThat(observer.hasBackgroundWaiters(pool)).isFalse();
        assertThat(rows.rates(pool).getRequests()).isEqualTo(1);
    }

    @Test
    void shouldRefuseAnObservationOfAnUnavailablePool() {
        var observer = LlmCallPermitsTestFixtures.worker(context, "observer");

        assertThatThrownBy(() -> observer.hasBackgroundWaiters(Long.MAX_VALUE))
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

    private UUID enqueue(long pool, LlmCallClass kind) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> rows.enqueue(
                new LlmCallPermitRepository.Request(id, pool, "backlog", kind.name(), kind == LlmCallClass.PRODUCT_FEEDBACK ? null : 2L, UUID.randomUUID(), 0)));
        return id;
    }
}
