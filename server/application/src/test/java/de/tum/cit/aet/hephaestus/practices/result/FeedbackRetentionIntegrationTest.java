package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Feedback and the results it cites are erased once the feedback retention has passed, and not a day before. */
class FeedbackRetentionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ResultRetentionService retention;

    @Autowired
    private FeedbackRetentionProperties properties;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldEraseFeedbackAndItsResultsAt401DaysAndKeepThemAt399() {
        Scenario scenario = data.scenario();
        long workspace = scenario.workspace().getId();
        // The database keeps microseconds and rounds, so a finer instant could read back later than its own end.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(properties.feedbackRetention());
        assertThat(properties.feedbackRetention()).isEqualTo(Duration.ofDays(400));

        // Delivered 399 days ago: the delivery counts, not the older creation, so both stay.
        UUID keptResult = result(scenario, days(now, 410));
        UUID deliveredRecently = feedback(scenario, keptResult, days(now, 410), days(now, 399));
        // Delivered 401 days ago.
        UUID erasedResult = result(scenario, days(now, 402));
        UUID deliveredLongAgo = feedback(scenario, erasedResult, days(now, 402), days(now, 401));
        // Never delivered: its creation counts.
        UUID undeliveredResult = result(scenario, days(now, 401));
        UUID undeliveredOld = feedback(scenario, undeliveredResult, days(now, 401), null);
        UUID youngResult = result(scenario, days(now, 399));
        UUID undeliveredYoung = feedback(scenario, youngResult, days(now, 399), null);
        // Expired, but its post started and never confirmed: it waits for the copy's cleanup receipt.
        UUID unresolvedResult = result(scenario, days(now, 402));
        UUID unresolved = feedback(scenario, unresolvedResult, days(now, 402), days(now, 401));
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_started_at)"
                        + " VALUES(?,?,?,'SUMMARY',0,'WRITE_STARTED',?)",
                UUID.randomUUID(),
                workspace,
                unresolved,
                Timestamp.from(days(now, 401)));
        // A running review reuses this one, so it stays until that review ends; a completed review's reuse does not.
        UUID readByRunning = result(scenario, days(now, 403));
        reuse(scenario, readByRunning, now, true);
        UUID readByCompleted = result(scenario, days(now, 403));
        reuse(scenario, readByCompleted, now, false);
        // No feedback cites these: their recording counts, whether a later result replaced them or not.
        UUID unreferencedYoung = result(scenario, days(now, 399));
        UUID unreferencedOld = current(scenario, days(now, 401));

        assertThat(feedback.workspacesWithExpiredFeedback(cutoff)).contains(workspace);
        retention.purgeWorkspace(workspace, cutoff);

        assertThat(exists("feedback", deliveredRecently)).isTrue();
        assertThat(exists("result", keptResult)).isTrue();
        assertThat(exists("feedback", undeliveredYoung)).isTrue();
        assertThat(exists("result", youngResult)).isTrue();
        assertThat(exists("result", unreferencedYoung)).isTrue();
        assertThat(exists("result", readByRunning)).isTrue();
        assertThat(exists("result", readByCompleted)).isFalse();
        assertThat(exists("feedback", unresolved)).isTrue();
        assertThat(exists("result", unresolvedResult)).isTrue();

        assertThat(exists("feedback", deliveredLongAgo)).isFalse();
        assertThat(exists("result", erasedResult)).isFalse();
        assertThat(exists("feedback", undeliveredOld)).isFalse();
        assertThat(exists("result", undeliveredResult)).isFalse();
        assertThat(exists("result", unreferencedOld)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_basis WHERE workspace_id = ? AND feedback_id IN (?, ?)",
                        Long.class,
                        workspace,
                        deliveredLongAgo,
                        undeliveredOld))
                .isZero();
    }

    private static Instant days(Instant now, int days) {
        return now.minus(Duration.ofDays(days));
    }

    /** A result that a later one replaced, as all but the current result of a practice, item and developer are. */
    private UUID result(Scenario scenario, Instant recordedAt) {
        var result = data.result(scenario, ResultOutcome.NOT_MET, recordedAt, recordedAt);
        data.end(result, recordedAt);
        return result.getId();
    }

    private UUID current(Scenario scenario, Instant recordedAt) {
        return data.result(scenario, ResultOutcome.NOT_MET, recordedAt, recordedAt)
                .getId();
    }

    private UUID feedback(Scenario scenario, UUID resultId, Instant createdAt, @Nullable Instant deliveredAt) {
        UUID id = UUID.randomUUID();
        var result = jdbc.queryForMap("SELECT review_id, item_id, developer_id FROM result WHERE id = ?", resultId);
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES(?,?,?,?,?,'PRACTICE_PAGE',?,'Explain the purpose.',?,?)",
                id,
                scenario.workspace().getId(),
                result.get("review_id"),
                result.get("item_id"),
                result.get("developer_id"),
                deliveredAt == null ? "READY" : "DELIVERED",
                Timestamp.from(createdAt),
                deliveredAt == null ? null : Timestamp.from(deliveredAt));
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES(?,?,?,?,?,0)",
                UUID.randomUUID(),
                scenario.workspace().getId(),
                id,
                resultId,
                result.get("developer_id"));
        return id;
    }

    /** A newer review of the same item that reused {@code resultId}, still running or completed. */
    private void reuse(Scenario scenario, UUID resultId, Instant now, boolean running) {
        var review = data.result(
                scenario, ResultOutcome.NOT_MET, now, running ? ReviewStatus.RUNNING : ReviewStatus.COMPLETE);
        data.end(review, now);
        jdbc.update(
                "UPDATE review_item SET state='REUSED',result_id=? WHERE review_id=? AND workspace_id=?",
                resultId,
                review.getReviewId(),
                scenario.workspace().getId());
    }

    private boolean exists(String table, UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id));
    }
}
