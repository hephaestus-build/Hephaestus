package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A retained failed run whose frozen assignment no longer exists must not stop Heph's context. Uses only the public
 * contribution boundary that predates the fix, so the same file runs against the earlier source and shows its failure.
 */
class RetainedReviewContextRegressionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ReviewAttemptsContentSource attempts;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldContributeTruthfulCoverageWhenARetainedJobHasNoReadableFrozenAssignment() {
        var scenario = data.scenario();
        var accountId = Objects.requireNonNull(scenario.account().getId());
        var now = Instant.now();
        var reviewId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        reviews.saveAndFlush(Review.builder()
                .id(reviewId)
                .workspaceId(scenario.workspace().getId())
                .itemId(scenario.item().getId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(accountId)
                .status(ReviewStatus.PARTIAL)
                .stopReason(ReviewStopReason.LEGACY_SCOPE_UNKNOWN)
                .itemVersion("retained-work-version")
                .orderedAt(now)
                .createdAt(now)
                .admittedAt(now)
                .completedAt(now)
                .build());
        // Retain only an inert failed invocation and its original key. No assignment, result or evidence is invented,
        // and no persisted row is changed to manufacture unavailable history.
        jdbc.sql("""
            INSERT INTO review_job(id,review_id,workspace_id,status,attempt,processing_status,trace_id,input,config_snapshot,
                submitted_at,available_at,started_at,completed_at)
            VALUES(:job,:review,:ws,'FAILED',1,'DONE',:trace,
                jsonb_build_object('itemId',CAST(:item AS bigint),'assignments',jsonb_build_array(
                    jsonb_build_object('reviewId',CAST(:review AS text),'practiceId',CAST(:practice AS bigint),
                        'itemId',CAST(:item AS bigint)))),
                '{}'::jsonb,now(),now(),now(),now())
            """)
                .param("job", jobId)
                .param("review", reviewId)
                .param("ws", scenario.workspace().getId())
                .param("trace", jobId.toString().replace("-", ""))
                .param("item", scenario.item().getId())
                .param("practice", scenario.practice().getId())
                .update();
        var files = new LinkedHashMap<String, byte[]>();

        attempts.contribute(
                new MentorContextRequest(scenario.workspace().getId(), accountId, UUID.randomUUID(), null), files);

        assertThat(files).containsOnlyKeys(ReviewAttemptsContentSource.OUTPUT_KEY);
        byte[] raw = Objects.requireNonNull(files.get(ReviewAttemptsContentSource.OUTPUT_KEY));
        var payload = new JsonMapper().readTree(raw);
        assertThat(payload.path("attempts")).isEmpty();
        assertThat(payload.path("coverage").path("attributionComplete").asBoolean(true))
                .isFalse();
        assertThat(payload.path("coverage").path("attributionNote").asString()).contains("left out");
        assertThat(payload.toString()).doesNotContain(jobId.toString(), reviewId.toString());
    }
}
