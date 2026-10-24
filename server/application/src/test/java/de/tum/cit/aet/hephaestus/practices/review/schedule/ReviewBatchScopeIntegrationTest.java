package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewBatchScopeIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

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
    void unknownAndForeignBatchHasNoTargets() {
        assertThat(scope.next(scenario.workspace().getId(), UUID.randomUUID(), null, 20))
                .isEmpty();
    }
}
