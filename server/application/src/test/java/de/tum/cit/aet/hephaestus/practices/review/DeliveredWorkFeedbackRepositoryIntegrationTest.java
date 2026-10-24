package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DeliveredWorkFeedbackRepositoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
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
    DeliveredWorkFeedbackRepository ledger;

    @Autowired
    JdbcClient jdbc;

    @Test
    void confirmedCopyRemainsVisibleWhenItsSiblingWriteIsUnconfirmed() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var feedback = feedback(result, "UNCONFIRMED");
        copy(result, feedback, 0, true);
        copy(result, feedback, 1, false);
        var metadata = ledger.findDelivered(result.getWorkspaceId(), scenarioAccountId, result.getItemId());
        assertThat(metadata).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(feedback);
            assertThat(f.copies()).hasSize(1);
            assertThat(f.copies().getFirst().externalRef()).isEqualTo("comment-0");
        });
    }

    @Test
    void aWriteFenceWithoutReceiptDoesNotClaimDelivery() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var feedback = feedback(result, "UNCONFIRMED");
        copy(result, feedback, 0, false);
        assertThat(ledger.findDelivered(result.getWorkspaceId(), scenarioAccountId, result.getItemId()))
                .isEmpty();
    }

    @Test
    void replacedFeedbackIsOmittedEvenWhenItHasAConfirmedCopy() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var feedback = feedback(result, "REPLACED");
        copy(result, feedback, 0, true);
        assertThat(ledger.findDelivered(result.getWorkspaceId(), scenarioAccountId, result.getItemId()))
                .isEmpty();
    }

    UUID feedback(Result result, String state) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO"
                        + " feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,stop_reason,body,checks,created_at)"
                        + " VALUES(:id,:ws,:review,:item,:developer,'ON_WORK',:state,'DELIVERY_UNCONFIRMED','Recorded"
                        + " feedback','{}'::jsonb,:at)")
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", scenarioAccountId)
                .param("state", state)
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:result,:developer,0)")
                .param("id", UUID.randomUUID())
                .param("ws", result.getWorkspaceId())
                .param("feedback", id)
                .param("result", result.getId())
                .param("developer", scenarioAccountId)
                .update();
        return id;
    }

    void copy(Result result, UUID feedback, int position, boolean posted) {
        var id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO"
                                + " feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_started_at,write_key,write_marker,external_ref,external_url,created_at,posted_at)"
                                + " VALUES(:id,:ws,:feedback,'SUMMARY',:position,:state,:at,:key,:marker,:ref,:url,:at,:posted)")
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("feedback", feedback)
                .param("position", position)
                .param("state", posted ? "POSTED" : "WRITE_STARTED")
                .param("at", Timestamp.from(result.getRecordedAt()))
                .param("key", id.toString())
                .param("marker", FeedbackIds.writeMarker(id))
                .param("ref", posted ? "comment-" + position : null)
                .param("url", posted ? "https://example.test/comment/" + position : null)
                .param("posted", posted ? Timestamp.from(result.getRecordedAt()) : null)
                .update();
    }
}
