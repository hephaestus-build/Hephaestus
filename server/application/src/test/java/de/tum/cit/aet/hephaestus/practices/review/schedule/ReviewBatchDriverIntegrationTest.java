package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewBatchDriverIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewBatchRepository batches;

    @Autowired
    ReviewBatchTargetRepository targets;

    @Autowired
    ReviewBatchScopeQuery scope;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    ReviewBudget budget;

    @Autowired
    ReviewBatchProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    ReviewBatchSpend spend;

    final PracticeReviewReadiness readiness = mock(PracticeReviewReadiness.class);
    final ReviewBatchSubmitter submitter = mock(ReviewBatchSubmitter.class);

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    @ParameterizedTest
    @CsvSource({"REVIEW_MODEL_UNBOUND,BACKFILL", "WORKSPACE_UNAVAILABLE,SCHEDULE"})
    void shouldRevisitAPrerequisitePauseWithoutBeingStarvedByAdministrativePauses(
            ReviewBatchPauseReason reason, ReviewBatchOrigin origin) {
        var now = Instant.now();
        var adminPauses = new ArrayList<ReviewBatch>();
        for (int i = 0; i < 5; i++) {
            adminPauses.add(
                    paused(ReviewBatchPauseReason.PAUSED_BY_ADMIN, ReviewBatchOrigin.BACKFILL, now.minusSeconds(60)));
        }
        var batch = paused(reason, origin, now);
        long workspaceId = scenario.workspace().getId();
        long itemId = scenario.item().getId();
        targets.saveAndFlush(new ReviewBatchTarget(batch.getId(), itemId, workspaceId));
        when(readiness.hasRunnableAgent(workspaceId)).thenReturn(true);
        when(submitter.offer(any(), eq(itemId), isNull()))
                .thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Submitted());

        var driver = new ReviewBatchDriver(
                batches, scope, submitter, workspaces, budget, readiness, properties, clock, reviews, spend);
        driver.tick();

        var continued = batches.findById(batch.getId()).orElseThrow();
        assertThat(continued.getStatus()).isEqualTo(ReviewBatchStatus.RUNNING);
        assertThat(continued.getPauseReason()).isNull();
        assertThat(continued.getSubmittedCount()).isOne();
        assertThat(continued.getCursorItemId()).isEqualTo(itemId);
        for (var paused : adminPauses) {
            var stored = batches.findById(paused.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
            assertThat(stored.getPauseReason()).isEqualTo(ReviewBatchPauseReason.PAUSED_BY_ADMIN);
        }
    }

    private ReviewBatch paused(ReviewBatchPauseReason reason, ReviewBatchOrigin origin, Instant at) {
        return batches.saveAndFlush(ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(scenario.workspace().getId())
                .kind("scm.pull_request")
                .origin(origin)
                .fromAt(at.minusSeconds(60))
                .toAt(at)
                .status(ReviewBatchStatus.PAUSED)
                .pauseReason(reason)
                .startedAt(at)
                .createdAt(at)
                .updatedAt(at)
                .build());
    }
}
