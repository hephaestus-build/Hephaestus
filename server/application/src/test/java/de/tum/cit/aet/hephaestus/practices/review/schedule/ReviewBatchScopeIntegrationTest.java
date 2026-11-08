package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCadence;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewBatchScopeIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    ItemRepository items;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    ReviewBatchProperties properties;

    @Autowired
    ReviewScheduleRepository schedules;

    @Autowired
    ReviewScheduleBatchOpener opener;

    @Autowired
    JdbcClient jdbc;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Autowired
    ReviewBatchRepository batches;

    @Autowired
    ReviewBatchTargetRepository targets;

    @Autowired
    ReviewBatchScopeQuery scope;

    @Test
    void frozenTargetsIgnoreItemsAddedLater() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        var batch = batches.saveAndFlush(ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(r.getWorkspaceId())
                .kind("scm.pull_request")
                .origin(ReviewBatchOrigin.BACKFILL)
                .fromAt(Instant.now().minusSeconds(60))
                .toAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        targets.saveAndFlush(new ReviewBatchTarget(batch.getId(), r.getItemId(), r.getWorkspaceId()));
        assertThat(scope.next(r.getWorkspaceId(), batch.getId(), null, 20)).containsExactly(r.getItemId());
        assertThat(scope.next(r.getWorkspaceId(), batch.getId(), r.getItemId(), 20))
                .isEmpty();
    }

    @Test
    void shouldOpenTheNextScheduleAfterAnExistingRunUsesItsFixedCap() {
        var now = Instant.now();
        long workspaceId = scenario.workspace().getId();
        var repository = new Item();
        repository.setWorkspaceId(workspaceId);
        repository.setKind(new ItemKind("scm.repository"));
        repository.setSourceId(12345L);
        repository.setCreatedAt(now);
        repository = items.saveAndFlush(repository);
        var stopped = batches.saveAndFlush(ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .kind("scm.repository")
                .origin(ReviewBatchOrigin.SCHEDULE)
                .status(ReviewBatchStatus.PAUSED)
                .pauseReason(ReviewBatchPauseReason.BUDGET_EXHAUSTED)
                .budgetUsd(BigDecimal.ONE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        targets.saveAndFlush(new ReviewBatchTarget(stopped.getId(), repository.getId(), workspaceId));
        var spend = mock(ReviewBatchSpend.class);
        when(spend.remaining(stopped)).thenReturn(new ReviewBatchSpend.Snapshot(BigDecimal.ZERO, true));
        var driver = new ReviewBatchDriver(
                batches,
                scope,
                mock(ReviewBatchSubmitter.class),
                workspaces,
                mock(ReviewBudget.class),
                mock(PracticeReviewReadiness.class),
                properties,
                Clock.fixed(now, ZoneOffset.UTC),
                reviews,
                spend);

        driver.advance(stopped);

        assertThat(batches.findById(stopped.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewBatchStatus.BUDGET_EXHAUSTED);
        jdbc.sql("""
                INSERT INTO item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)
                VALUES(:id,:workspaceId,:itemId,'scm.pull_request.opened',:version,:at,'WEBHOOK','STARTED',:at)
                """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("itemId", scenario.item().getId())
                .param("version", "scm.pull_request:v1:" + scenario.item().getId())
                .param("at", Timestamp.from(now.minusSeconds(1)))
                .update();
        var next = schedules.saveAndFlush(ReviewSchedule.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .kind("scm.pull_request")
                .cadence(ReviewCadence.DAILY)
                .lookbackDays(1)
                .nextRunAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        assertThat(opener.openDueRun(next.getId(), now)).isEqualTo(ReviewScheduleOutcome.OPENED);
    }

    @Test
    void unknownAndForeignBatchHasNoTargets() {
        assertThat(scope.next(scenario.workspace().getId(), UUID.randomUUID(), null, 20))
                .isEmpty();
    }
}
