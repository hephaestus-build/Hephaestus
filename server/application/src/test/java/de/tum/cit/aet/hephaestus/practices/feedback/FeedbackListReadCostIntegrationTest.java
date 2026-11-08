package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeedbackListReadCostIntegrationTest extends BaseIntegrationTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final FeedbackQueryFilter ALL = new FeedbackQueryFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Autowired private ReviewIntegrationData data;
    @Autowired private FeedbackReadService feedback;
    @Autowired private SqlReadMeasurement reads;
    @Autowired private SqlRequestTrace requests;
    @Autowired private WebTestClient http;
    @Autowired private JdbcClient jdbc;

    private ReviewIntegrationData.Scenario scenario;
    private final List<UUID> ids = new ArrayList<>();
    private UUID foreignId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        var account = Objects.requireNonNull(scenario.account().getId());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "feedback-reader", account.toString(), new String[0], "feedback-read-cost-token"));
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
        assertThat(small.value().getContent()).extracting(ReviewFeedbackDTO::id).containsExactlyInAnyOrderElementsOf(ids);
        growPage();

        var larger = reads.measure(() -> feedback.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));

        assertThat(larger.value().getContent()).extracting(ReviewFeedbackDTO::id)
                .containsExactlyInAnyOrderElementsOf(ids).doesNotContain(foreignId);
        assertThat(larger.value().getContent()).containsAll(small.value().getContent()).allSatisfy(row -> {
            assertThat(row.resultCount()).isEqualTo(1);
            assertThat(row.bodyPreview()).isNull();
            assertThat(row.textHidden()).isTrue();
            assertThat(row.withdrawn()).isFalse();
        });
        assertThat(larger.cost().statements()).as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
    }

    @Test
    void shouldKeepOperatorFeedbackRequestCostBoundedAsThePageGrows() {
        var path = "/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practices/reviews/feedback?size=32";
        var small = requests.measure(http, client -> client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.content.length()").isEqualTo(2));
        growPage();

        var larger = requests.measure(http, client -> client.get().uri(path)
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.content.length()").isEqualTo(8)
                .jsonPath("$.content[?(@.id=='" + foreignId + "')]").isEmpty());

        assertThat(larger.statements()).as("small=%s, larger=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements());
    }

    private void growPage() {
        for (int index = 2; index < 8; index++) ids.add(recordedFeedback(scenario, index));
    }

    private UUID recordedFeedback(ReviewIntegrationData.Scenario owner, int index) {
        var result = data.result(data.onOtherWork(owner), ResultOutcome.NOT_MET, AT.plusSeconds(index));
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
            VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Recorded feedback.',:at,:at)
            """)
                .param("id", id).param("ws", result.getWorkspaceId()).param("review", result.getReviewId())
                .param("item", result.getItemId()).param("developer", result.getDeveloperId())
                .param("at", Timestamp.from(result.getRecordedAt())).update();
        jdbc.sql("""
            INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)
            VALUES(:id,:ws,:feedback,:result,:developer,0)
            """)
                .param("id", UUID.randomUUID()).param("ws", result.getWorkspaceId()).param("feedback", id)
                .param("result", result.getId()).param("developer", result.getDeveloperId()).update();
        return id;
    }
}
