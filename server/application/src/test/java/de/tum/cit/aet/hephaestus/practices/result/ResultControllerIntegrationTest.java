package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

class ResultControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ResultPresentation presentation;

    @Autowired
    ResultCommunicationRepository communication;

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

    @Test
    void detailUsesDecimalItemAndVersionIds() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practices/results/" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(r.getId().toString())
                .jsonPath("$.reviewId")
                .isEqualTo(r.getReviewId().toString())
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(r.getItemId()))
                .jsonPath("$.practiceVersionId")
                .isEqualTo(Long.toString(Objects.requireNonNull(r.getPracticeVersionId())))
                .jsonPath("$.outcome")
                .isEqualTo("MET");
    }

    @Test
    void shouldReadLatestFeedbackFactsAndKeepThemScopedToTheResultOwner() {
        var at = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        var result = data.result(scenario, ResultOutcome.NOT_MET, at);
        var feedback = UUID.randomUUID();
        var copy = FeedbackIds.copy(feedback, FeedbackCopyKind.SUMMARY, 0);
        long ws = scenario.workspace().getId();
        var url = "https://github.com/example/project/pull/1#issuecomment-1";
        jdbc.sql(
                        "INSERT INTO"
                                + " feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)"
                                + " VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Review this change.',:at,:at)")
                .param("id", feedback)
                .param("ws", ws)
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", scenarioAccountId)
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:result,:developer,0)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("result", result.getId())
                .param("developer", scenarioAccountId)
                .update();
        jdbc.sql("INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_key,write_marker,"
                        + "write_started_at,external_ref,external_url,posted_at,created_at)"
                        + " VALUES(:id,:ws,:feedback,'SUMMARY',0,'POSTED',:key,:marker,:at,'copy-1',:url,:at,:at)")
                .param("id", copy)
                .param("ws", ws)
                .param("feedback", feedback)
                .param("key", copy.toString())
                .param("marker", FeedbackIds.writeMarker(copy))
                .param("at", Timestamp.from(at))
                .param("url", url)
                .update();
        jdbc.sql("INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,usefulness,comment,created_at)"
                        + " VALUES(:id,:ws,:feedback,'DISAGREE','HELPFUL','This is already covered.',:at)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("at", Timestamp.from(at))
                .update();
        get("/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[0].id")
                .isEqualTo(feedback.toString())
                .jsonPath("$.feedback[0].response.resolution")
                .isEqualTo("DISAGREE")
                .jsonPath("$.feedback[0].response.usefulness")
                .isEqualTo("HELPFUL")
                .jsonPath("$.feedback[0].copies[0].kind")
                .isEqualTo("SUMMARY")
                .jsonPath("$.feedback[0].copies[0].externalUrl")
                .isEqualTo(url);
        jdbc.sql("INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note,decided_at)"
                        + " VALUES(:id,:ws,:feedback,'WITHDRAW',:actor,'Withdraw this note.',:at)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("actor", scenarioAccountId)
                .param("at", Timestamp.from(at))
                .update();
        assertThat(presentation.boundFeedback(result)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(feedback);
            assertThat(row.withdrawn()).isTrue();
            assertThat(row.deliveredAt()).isEqualTo(at);
        });
        assertThat(presentation.disagreements(result))
                .singleElement()
                .satisfies(row -> assertThat(row.comment()).isEqualTo("This is already covered."));
        // The developer's own result leaves a withdrawn piece out; the admin's bound read above keeps it.
        get("/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback.length()")
                .isEqualTo(0);
        var other = data.scenario();
        assertThat(communication.boundFeedback(other.workspace().getId(), result.getId()))
                .isEmpty();
        assertThat(communication.ownFeedback(
                        ws, Objects.requireNonNull(other.account().getId()), result.getId()))
                .isEmpty();
        jdbc.sql("INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note,decided_at)"
                        + " VALUES(:id,:ws,:feedback,'RESTORE',:actor,'Restore this note.',:at)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("actor", scenarioAccountId)
                .param("at", Timestamp.from(at.plusSeconds(2)))
                .update();
        jdbc.sql("INSERT INTO feedback_response(id,workspace_id,feedback_id,resolution,created_at)"
                        + " VALUES(:id,:ws,:feedback,'ADDRESSED',:at)")
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedback)
                .param("at", Timestamp.from(at.plusSeconds(1)))
                .update();
        assertThat(presentation.disagreements(result)).isEmpty();
        get("/practices/results/" + result.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback[0].response.resolution")
                .isEqualTo("ADDRESSED")
                .jsonPath("$.feedback[0].response.comment")
                .isEmpty();
    }

    @Test
    void foreignWorkspaceResultIsNotVisible() {
        var other = data.scenario();
        var r = data.result(other, ResultOutcome.MET, Instant.now());
        get("/practices/results/" + r.getId()).expectStatus().isNotFound().expectBody(Void.class);
    }

    @Test
    void anotherDevelopersResultIsNotVisible() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        var other = data.member(scenario.workspace().getId());
        http.get()
                .uri(root() + "/practices/results/" + r.getId())
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + other.getId()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void currentAndEndedResultsRemainInHistory() {
        var old = data.result(scenario, ResultOutcome.MET, Instant.now().minusSeconds(60));
        data.end(old, Instant.now().minusSeconds(30));
        data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/results")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2);
    }

    @Test
    void markedResultRemainsInHistory() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        data.mark(r, ResultMarkKind.INCORRECT, scenarioAccountId);
        get("/practices/results/" + r.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.outcome")
                .isEqualTo("NOT_MET")
                .jsonPath("$.markedIncorrectAt")
                .isNotEmpty();
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        http.get()
                .uri(root() + "/practices/results")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }
}
