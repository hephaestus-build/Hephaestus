package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.interceptor.AsyncExecutionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@Import(ReviewJobEventListenerIntegrationTest.ListenerConfiguration.class)
class ReviewJobEventListenerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReviewJobEventListener listener;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ReviewAssignmentQuery assignments;

    @Test
    void shouldRollBackTheReviewRequestWhenExecutionStopsBeforeEnqueue() {
        databaseTestUtils.cleanDatabase();
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.update(
                "UPDATE review SET status='CANCELLED',stop_reason='CANCELLED',completed_at=now() WHERE workspace_id=978001");
        var reviewId = UUID.randomUUID();
        var interrupted = new AtomicBoolean();
        when(assignments.executable(978001L, reviewId, 978006L)).thenAnswer(invocation -> {
            interrupted.set(true);
            throw new IllegalStateException("Simulated crash before enqueue");
        });

        // Keep async advice off the publisher's transaction thread, and join without a clock deadline.
        if (listener instanceof Advised proxy) {
            for (var advisor : proxy.getAdvisors()) {
                if (advisor.getAdvice() instanceof AsyncExecutionInterceptor async) {
                    async.setExecutor(task -> CompletableFuture.runAsync(task).join());
                }
            }
        }

        var failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)"
                            + " VALUES(?,978001,978006,'ITEM','native-v1','REQUEST','QUEUED',now())",
                    reviewId);
            events.publishEvent(new ReviewRequested(reviewId, 978001L));
        }));

        assertThat(interrupted).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT status FROM review WHERE workspace_id=? AND id=?", String.class, 978001L, reviewId))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_job WHERE workspace_id=? AND review_id=?",
                        Long.class,
                        978001L,
                        reviewId))
                .isZero();
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("Simulated crash before enqueue");
    }

    @TestConfiguration
    static class ListenerConfiguration {
        @Bean
        ReviewJobEventListener reviewJobEventListener(
                ReviewService reviews, ReviewQueue queue, ReviewAssignmentQuery assignments) {
            return new ReviewJobEventListener(reviews, queue, assignments);
        }
    }
}
