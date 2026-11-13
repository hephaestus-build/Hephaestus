package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeedbackResponseControllerIntegrationTest extends AbstractFeedbackIntegrationTest {

    @Autowired
    WebTestClient client;

    String route(UUID id) {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/feedback/" + id + "/response";
    }

    @Test
    @WithAdminUser
    void shouldReturnCompleteResponseAndAppendClearHistory() {
        var parent = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, AT);
        client.put()
                .uri(route(parent.getId()))
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(
                        new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.resolution")
                .isEqualTo("ADDRESSED")
                .jsonPath("$.usefulness")
                .isEqualTo("HELPFUL")
                .jsonPath("$.createdAt")
                .exists();
        client.delete()
                .uri(route(parent.getId()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.resolution")
                .doesNotExist()
                .jsonPath("$.usefulness")
                .doesNotExist()
                .jsonPath("$.comment")
                .doesNotExist()
                .jsonPath("$.createdAt")
                .exists();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_response WHERE workspace_id=? AND" + " feedback_id=?",
                        Integer.class,
                        workspace.getId(),
                        parent.getId()))
                .isEqualTo(2);
    }

    @Test
    @WithAdminUser
    void shouldHideUnknownVisibilityAndAnotherDevelopersFeedback() {
        var unknown = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, null);
        client.get()
                .uri(route(unknown.getId()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        var shown = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        long other = Objects.requireNonNull(persistAccount("Other developer").getId());
        jdbc.update(
                "INSERT INTO"
                        + " membership(workspace_id,account_id,role,joined_at,review_selected,hidden,public_activity_seen)"
                        + " VALUES (?,?,'MEMBER',now(),false,false,false)",
                workspace.getId(),
                other);
        jdbc.update("UPDATE feedback SET developer_id=? WHERE id=?", other, shown.getId());
        client.get()
                .uri(route(shown.getId()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRejectDisagreementWithoutAnExplanation() {
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        client.put()
                .uri(route(parent.getId()))
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.DISAGREE, " "))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(responses.findCurrent(workspace.getId(), parent.getId())).isEmpty();
    }

    @Test
    void shouldRejectUnauthenticatedResponseReads() {
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        client.get()
                .uri(route(parent.getId()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }
}
