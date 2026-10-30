package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewBatchControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewBatchRepository batches;

    @Autowired
    WebTestClient http;

    @Autowired
    ItemDescriptorRegistry catalog;

    @Autowired
    ItemRepository items;

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

    @Test
    void emptyBatchListContainsNoOtherWorkspaceRows() {
        seedBatch(data.scenario(), ReviewBatchStatus.PAUSED, Instant.now());
        get("/practices/review-batches")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    void shouldReturnConflictWhenAnEndedBatchIsResumed() {
        var now = Instant.now();
        var batch = batches.saveAndFlush(ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(scenario.workspace().getId())
                .kind("scm.pull_request")
                .fromAt(now.minusSeconds(60))
                .toAt(now)
                .status(ReviewBatchStatus.CANCELLED)
                .requestedByAccountId(scenario.account().getId())
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(now)
                .build());
        http.patch()
                .uri(root() + "/practices/review-batches/" + batch.getId() + "/status")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .header("If-Match", "\"" + batch.getVersion() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "RUNNING"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/review-batch-conflict")
                .jsonPath("$.status")
                .isEqualTo(409);
    }

    @Test
    void unauthenticatedBatchCreationIsRejected() {
        http.post()
                .uri(root() + "/practices/review-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "itemKind",
                        "scm.pull_request",
                        "from",
                        Instant.now().minusSeconds(60).toString(),
                        "to",
                        Instant.now().toString()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private ReviewBatch seedBatch(Scenario owner, ReviewBatchStatus status, Instant at) {
        return batches.saveAndFlush(ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(owner.workspace().getId())
                .kind("scm.pull_request")
                .fromAt(at.minusSeconds(60))
                .toAt(at)
                .status(status)
                .pauseReason(status == ReviewBatchStatus.PAUSED ? ReviewBatchPauseReason.BUDGET_EXHAUSTED : null)
                .requestedByAccountId(owner.account().getId())
                .createdAt(at)
                .updatedAt(at)
                .build());
    }

    @Test
    void shouldSelectRunningWorkAfterFiveOlderPausedBatchesAcrossTheInstance() {
        var at = Instant.now();
        for (int i = 0; i < 5; i++) seedBatch(data.scenario(), ReviewBatchStatus.PAUSED, at.minusSeconds(100 + i));
        var running = seedBatch(scenario, ReviewBatchStatus.RUNNING, at);
        assertThat(batches.findByStatusOrderByUpdatedAtAscIdAsc(ReviewBatchStatus.RUNNING, PageRequest.ofSize(5)))
                .extracting(ReviewBatch::getId)
                .containsExactly(running.getId());
    }

    @Test
    void shouldRecordAnAdminPauseWithItsOwnReason() {
        var batch = seedBatch(scenario, ReviewBatchStatus.RUNNING, Instant.now());
        http.patch()
                .uri(root() + "/practices/review-batches/" + batch.getId() + "/status")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .header("If-Match", "\"" + batch.getVersion() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "PAUSED"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.pauseReason")
                .isEqualTo("PAUSED_BY_ADMIN");
        assertThat(batches.findByIdAndWorkspaceId(
                                batch.getId(), scenario.workspace().getId())
                        .orElseThrow()
                        .getPauseReason())
                .isEqualTo(ReviewBatchPauseReason.PAUSED_BY_ADMIN);
    }

    @Test
    void shouldFreezeOwnPullRequestsInTheWindowWhenThePullRequestKindHasMembers() {
        assertThat(catalog.descriptorFor(new ItemKind("scm.pull_request"))
                        .orElseThrow()
                        .memberKinds())
                .isNotEmpty();
        var to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var from = to.minus(7, ChronoUnit.DAYS);
        var inWindow = scenario.item();
        opened(inWindow, to.minus(1, ChronoUnit.DAYS));
        opened(pullRequest(scenario.workspace().getId()), from.minus(1, ChronoUnit.DAYS));
        var other = data.scenario();
        opened(other.item(), to.minus(1, ChronoUnit.DAYS));

        http.post()
                .uri(root() + "/practices/review-batches")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemKind", "scm.pull_request", "from", from.toString(), "to", to.toString()))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.itemKind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$.status")
                .isEqualTo("AWAITING_CONFIRMATION")
                .jsonPath("$.estimatedItems")
                .isEqualTo(1)
                .jsonPath("$.budgetUsd")
                .doesNotExist()
                .jsonPath("$.targetItemIds")
                .isEqualTo(List.of(Long.toString(inWindow.getId())));
        var batch = batches.findByWorkspaceId(scenario.workspace().getId(), PageRequest.ofSize(10))
                .getContent()
                .getFirst();
        assertThat(batch.getOrigin()).isEqualTo(ReviewBatchOrigin.BACKFILL);
        assertThat(batch.getFromAt()).isEqualTo(from);
        assertThat(batch.getToAt()).isEqualTo(to);
        assertThat(batch.getStartedAt()).isNull();
        assertThat(batch.getConfirmedByAccountId()).isNull();
    }

    @Test
    void shouldRejectARepositoryBatchWhenTheScanApiOwnsRepositoryReviews() {
        var to = Instant.now();
        http.post()
                .uri(root() + "/practices/review-batches")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "itemKind",
                        "scm.repository",
                        "from",
                        to.minus(1, ChronoUnit.DAYS).toString(),
                        "to",
                        to.toString()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Use the scan API to review a repository.");
        assertThat(batches.findByWorkspaceId(scenario.workspace().getId(), PageRequest.ofSize(1)))
                .isEmpty();
    }

    private Item pullRequest(long workspaceId) {
        var item = new Item();
        item.setWorkspaceId(workspaceId);
        item.setKind(new ItemKind("scm.pull_request"));
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        return items.saveAndFlush(item);
    }

    private void opened(Item item, Instant at) {
        jdbc.sql("""
                INSERT INTO item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)
                VALUES(:id,:workspaceId,:itemId,'scm.pull_request.opened',:version,:at,'WEBHOOK','STARTED',:at)
                """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", item.getWorkspaceId())
                .param("itemId", item.getId())
                .param("version", "scm.pull_request:v1:" + item.getId())
                .param("at", Timestamp.from(at))
                .update();
    }
}
