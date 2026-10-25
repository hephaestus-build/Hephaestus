package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemMergeTimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ReviewTimingFactsIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewTimingFactsQuery facts;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ItemMergeTimeQuery mergeTimes;

    @MockitoBean
    ReviewRuntimeQuery runtime;

    @MockitoBean
    ReviewModelBindings models;

    @MockitoBean
    ReviewBudget budget;

    @MockitoBean
    ReviewAttemptGuard ownership;

    @MockitoBean
    HistoryCustodyQuery custody;

    ReviewIntegrationData.Scenario scenario;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        when(mergeTimes.mergedAt(anyLong(), anyList())).thenReturn(Map.of());
    }

    @Test
    void shouldRetainEventDelayAndActualDomainMergeTime() {
        var at = Instant.parse("2026-10-14T07:00:00Z");
        var result = data.result(scenario, ResultOutcome.MET, at);
        var event = UUID.randomUUID();
        jdbc.sql("INSERT INTO"
                        + " item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)"
                        + " VALUES(:id,:ws,:item,'scm.pull_request.merged',:version,:at,'WEBHOOK','STARTED',:at)")
                .param("id", event)
                .param("ws", scenario.workspace().getId())
                .param("item", scenario.item().getId())
                .param("at", Timestamp.from(at.minusSeconds(30)))
                .param("version", Objects.requireNonNull(result.getItemVersion()))
                .update();
        jdbc.sql("UPDATE review SET origin='EVENT',requested_by_id=NULL,event_id=:event,ordered_at=:ordered"
                        + " WHERE workspace_id=:ws AND id=:id")
                .param("event", event)
                .param("ordered", Timestamp.from(at.minusSeconds(30)))
                .param("ws", result.getWorkspaceId())
                .param("id", result.getReviewId())
                .update();
        when(mergeTimes.mergedAt(anyLong(), anyList()))
                .thenReturn(Map.of(scenario.item().getId(), at.minusSeconds(30)));
        var timeline = facts.timelines(result.getWorkspaceId(), List.of(result.getReviewId()))
                .getFirst();
        assertThat(timeline.eventAt()).isEqualTo(at.minusSeconds(30));
        assertThat(timeline.mergedAt()).isEqualTo(at.minusSeconds(30));
        assertThat(timeline.firstAutomaticPostedAt()).isNull();
        var aggregate = facts.facts(
                result.getWorkspaceId(), List.of(result.getReviewId()), at.minusSeconds(60), at.plusSeconds(60));
        assertThat(aggregate.asked()).isZero();
        assertThat(aggregate.finishedAsked()).isEqualTo(30);
        assertThat(aggregate.finishedAfterMerge()).isOne();
        assertThat(aggregate.finishedAfterMergeStartedByMerge()).isOne();
    }

    @Test
    void shouldCountMergeWithholdingInTheFeedbackCreationWindow() {
        var at = Instant.parse("2026-10-14T07:00:00Z");
        var result = data.result(scenario, ResultOutcome.MET, at.minusSeconds(3600));
        withheld(result.getReviewId(), at);
        withheld(result.getReviewId(), at.minusSeconds(60));
        var aggregate = facts.facts(result.getWorkspaceId(), List.of(), at, at.plusSeconds(60));
        assertThat(aggregate.feedbackWithheldAsMerged()).isOne();
    }

    @Test
    void shouldKeepUnknownEventTimeNullableAndRejectForeignTimelineRows() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        jdbc.sql("UPDATE review SET origin='EVENT',requested_by_id=NULL,event_id=NULL WHERE workspace_id=:ws"
                        + " AND id=:id")
                .param("ws", result.getWorkspaceId())
                .param("id", result.getReviewId())
                .update();
        assertThat(facts.timelines(result.getWorkspaceId(), List.of(result.getReviewId()))
                        .getFirst()
                        .eventAt())
                .isNull();
        assertThat(facts.timelines(result.getWorkspaceId() + 1, List.of(result.getReviewId())))
                .isEmpty();
    }

    @Test
    void shouldSelectTheRequestedReviewWindowWithoutDependingOnRuntimeRows() {
        var at = Instant.parse("2026-10-14T07:00:00Z");
        var earlier = data.result(scenario, ResultOutcome.MET, at.minusSeconds(60));
        data.end(earlier, at.minusSeconds(1));
        var selected = data.result(scenario, ResultOutcome.NOT_MET, at);
        var foreign = data.scenario();
        data.result(foreign, ResultOutcome.MET, at);
        assertThat(facts.window(scenario.workspace().getId(), at, at.plusSeconds(60)))
                .extracting(row -> row.reviewId())
                .containsExactly(selected.getReviewId());
    }

    private void withheld(UUID reviewId, Instant at) {
        jdbc.sql("INSERT INTO"
                        + " feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,stop_reason,created_at)"
                        + " VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','WITHHELD','ITEM_MERGED',:at)")
                .param("id", UUID.randomUUID())
                .param("ws", scenario.workspace().getId())
                .param("review", reviewId)
                .param("item", scenario.item().getId())
                .param("developer", scenario.account().getId())
                .param("at", Timestamp.from(at))
                .update();
    }
}
