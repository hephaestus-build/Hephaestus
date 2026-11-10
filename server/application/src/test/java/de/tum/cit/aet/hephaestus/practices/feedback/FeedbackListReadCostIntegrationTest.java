package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeedbackListReadCostIntegrationTest extends BaseIntegrationTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final FeedbackQueryFilter ALL = new FeedbackQueryFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private FeedbackReadService feedback;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private SqlRequestTrace requests;

    @Autowired
    private WebTestClient http;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QueryPlans plans;

    private ReviewIntegrationData.Scenario scenario;
    private final List<UUID> ids = new ArrayList<>();
    private UUID foreignId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        var account = Objects.requireNonNull(scenario.account().getId());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                account.toString(), new String[0], "feedback-read-cost-token"));
        ids.add(recordedFeedback(scenario, 0));
        ids.add(recordedFeedback(scenario, 1));
        foreignId = recordedFeedback(data.scenario(), 99);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepOperatorFeedbackListReadsBoundedAsThePageGrows() {
        var small = reads.measure(() -> feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));
        assertThat(small.value().getContent())
                .extracting(ReviewFeedbackDTO::id)
                .containsExactlyInAnyOrderElementsOf(ids);
        growPage();

        var larger = reads.measure(() -> feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));

        assertThat(larger.value().getContent())
                .extracting(ReviewFeedbackDTO::id)
                .containsExactlyInAnyOrderElementsOf(ids)
                .doesNotContain(foreignId);
        assertThat(larger.value().getContent())
                .containsAll(small.value().getContent())
                .allSatisfy(row -> {
                    assertThat(row.resultCount()).isEqualTo(1);
                    assertThat(row.bodyPreview()).isNull();
                    assertThat(row.textHidden()).isTrue();
                    assertThat(row.withdrawn()).isFalse();
                });
        assertThat(larger.cost().statements())
                .as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    @Test
    void shouldKeepOperatorFeedbackRequestCostBoundedAsThePageGrows() {
        var path = "/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practices/reviews/feedback?size=32";
        var small = requests.measure(
                http,
                client -> client.get()
                        .uri(path)
                        .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.content.length()")
                        .isEqualTo(2));
        growPage();

        var larger = requests.measure(
                http,
                client -> client.get()
                        .uri(path)
                        .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.content.length()")
                        .isEqualTo(8)
                        .jsonPath("$.content[?(@.id=='" + foreignId + "')]")
                        .isEmpty());

        assertThat(larger.statements())
                .as("small=%s, larger=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements());
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    private void growPage() {
        for (int index = 2; index < 8; index++) ids.add(recordedFeedback(scenario, index));
    }

    @Test
    void shouldKeepTotalsOnAnEmptyPage() {
        var page = feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(99, 2));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getNumber()).isEqualTo(99);
    }

    @Test
    void shouldUseTheLatestResponseIncludingUuidTiesAndNullResolution() {
        var id = ids.getFirst();
        var at = AT.plusSeconds(10);
        for (var response : List.of(
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"))) {
            jdbc.sql("""
                INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,comment,created_at)
                VALUES(:id,:ws,:feedback,:resolution,'Recorded response.',:at)
                """)
                    .param("id", response)
                    .param("ws", scenario.workspace().getId())
                    .param("feedback", id)
                    .param("resolution", response.toString().startsWith("7") ? "DISAGREE" : "ADDRESSED")
                    .param("at", Timestamp.from(at))
                    .update();
        }
        var first = feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32));
        assertThat(row(first, id).disagreedAt()).isNull();
        jdbc.sql("""
            INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,comment,created_at)
            VALUES(:id,:ws,:feedback,'DISAGREE','Recorded disagreement.',:at)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", scenario.workspace().getId())
                .param("feedback", id)
                .param("at", Timestamp.from(at.plusSeconds(1)))
                .update();
        var disagreed = feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32));
        assertThat(row(disagreed, id).disagreedAt()).isEqualTo(at.plusSeconds(1));
        jdbc.sql("INSERT INTO feedback_response(id,workspace_id,feedback_id,created_at) VALUES(:id,:ws,:feedback,:at)")
                .param("id", UUID.randomUUID())
                .param("ws", scenario.workspace().getId())
                .param("feedback", id)
                .param("at", Timestamp.from(at.plusSeconds(2)))
                .update();
        var cleared = feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32));
        assertThat(row(cleared, id).disagreedAt()).isNull();
    }

    @Test
    void shouldReflectWithdrawalAndLaterRestoration() {
        for (var id : ids) decision(id, "WITHDRAW", AT.plusSeconds(10));
        decision(ids.getLast(), "RESTORE", AT.plusSeconds(11));

        var page = feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32));

        assertThat(row(page, ids.getFirst()).withdrawn()).isTrue();
        assertThat(row(page, ids.getLast()).withdrawn()).isFalse();
    }

    private void decision(UUID id, String action, Instant at) {
        jdbc.sql("""
            INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note,decided_at)
            VALUES(:id,:ws,:feedback,:action,:actor,'Recorded visibility decision.',:at)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", scenario.workspace().getId())
                .param("feedback", id)
                .param("action", action)
                .param("actor", scenario.account().getId())
                .param("at", Timestamp.from(at))
                .update();
    }

    private static ReviewFeedbackDTO row(Page<ReviewFeedbackDTO> page, UUID id) {
        return page.getContent().stream()
                .filter(row -> row.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private UUID recordedFeedback(ReviewIntegrationData.Scenario owner, int index) {
        var result = data.result(data.onOtherWork(owner), ResultOutcome.NOT_MET, AT.plusSeconds(index));
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
            VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Recorded feedback.',:at,:at)
            """)
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", result.getDeveloperId())
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        jdbc.sql("""
            INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)
            VALUES(:id,:ws,:feedback,:result,:developer,0)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", result.getWorkspaceId())
                .param("feedback", id)
                .param("result", result.getId())
                .param("developer", result.getDeveloperId())
                .update();
        return id;
    }
}
