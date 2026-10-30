package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeedbackReviewControllerIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    WebTestClient client;

    @MockitoBean
    RegisteredItemLabels labels;

    String route() {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/reviews/feedback";
    }

    @Test
    @WithAdminUser
    void shouldExposeExactCopyUncertaintyAndRequiredNullableDetailFields() {
        var parent = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, null);
        summary(parent, FeedbackCopyState.WRITE_STARTED);
        client.get()
                .uri(route() + "/" + parent.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("UNCONFIRMED")
                .jsonPath("$.copyCounts.writeStarted")
                .isEqualTo(1)
                .jsonPath("$.copies[0].writeStartedAt")
                .exists()
                .jsonPath("$.copies[0].externalRef")
                .isEmpty()
                .jsonPath("$.textHidden")
                .isEqualTo(true)
                .jsonPath("$.body")
                .isEmpty()
                .jsonPath("$.contentDigest")
                .isEmpty()
                .jsonPath("$.response")
                .isEmpty()
                .jsonPath("$.basis")
                .isArray();
    }

    @Test
    @WithAdminUser
    void shouldUseTheSameStateAndDatePredicatesForPagesAndCounts() {
        var selected = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, null);
        client.get()
                .uri(route() + "?state=READY&from=2026-10-14T00:00:00Z&to=2026-10-15T00:00:00Z")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(selected.getId().toString());
        client.get()
                .uri(route() + "/locations?state=READY&from=2026-10-14T00:00:00Z&to=2026-10-15T00:00:00Z")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].reason")
                .isEqualTo("PRACTICE_PAGES")
                .jsonPath("$[0].count")
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void shouldTreatTheLatestCompleteClearAsNoCurrentDisagreement() {
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .resolution(FeedbackResolution.DISAGREE)
                .comment("There is more context.")
                .createdAt(AT)
                .build());
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .createdAt(AT.plusSeconds(1))
                .build());
        client.get()
                .uri(route() + "?disagreed=true")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
        client.get()
                .uri(route() + "?disagreed=false")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void shouldHideAnotherWorkspacesFeedbackEvenFromAnInstanceAdministrator() {
        var parent = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, null);
        var other = createWorkspace(
                "feedback-other", "Other workspace", "other-org", AccountType.ORG, persistUser("other-owner"));
        ensureAdminMembership(other);
        client.get()
                .uri("/workspaces/" + other.getWorkspaceSlug() + "/practices/reviews/feedback/" + parent.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/" + other.getWorkspaceSlug() + "/practices/reviews/" + reviewId + "/feedback-checks")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.post()
                .uri("/workspaces/" + other.getWorkspaceSlug() + "/practices/reviews/feedback/" + parent.getId()
                        + "/retry")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(sends.findById(parent.getId())).isEmpty();
    }

    @Test
    @WithMentorUser
    void shouldRequireWorkspaceAdministrationForFeedbackAndDecisions() {
        ensureMembership(workspace, persistUser("mentor"), Membership.WorkspaceRole.MEMBER);
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        client.get()
                .uri(route())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.get()
                .uri(route() + "/" + parent.getId() + "/decisions")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.post()
                .uri(route() + "/" + parent.getId() + "/retry")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(sends.findById(parent.getId())).isEmpty();
        assertThat(decisions.findByWorkspaceIdAndFeedbackIdOrderByDecidedAtDescIdDesc(
                        workspace.getId(), parent.getId()))
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldRejectRetiredOrUnknownStatesAndInvalidPageBounds() {
        client.get()
                .uri(route() + "?state=PREPARED")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        client.get()
                .uri(route() + "?size=101")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldLocateConversationFeedbackByTheOriginOfTheHephMessageThatShowedIt() {
        var inApp = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var inSlack = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var waiting = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        UUID conversation = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,?,?)",
                conversation,
                workspace.getId(),
                developerId,
                Timestamp.from(AT),
                Timestamp.from(AT));
        shownIn(inApp, conversation, 1, "APP");
        shownIn(inSlack, conversation, 2, "SLACK");

        for (var expected : List.of(
                Map.entry("HEPH", inApp), Map.entry("SLACK", inSlack), Map.entry("NEXT_CONVERSATION", waiting))) {
            client.get()
                    .uri(route() + "?location=" + expected.getKey())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].id")
                    .isEqualTo(expected.getValue().getId().toString());
        }
    }

    /** A conversation copy of {@code parent} posted in a Heph message written on {@code surface}. */
    private void shownIn(Feedback parent, UUID conversation, int seq, String surface) {
        UUID entry = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,created_at)"
                        + " VALUES (?,?,?,?,'HEPH',?,'Add a focused regression test.',?)",
                entry,
                workspace.getId(),
                conversation,
                seq,
                surface,
                Timestamp.from(AT));
        UUID id = FeedbackIds.copy(parent.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_key,write_marker,"
                        + "write_started_at,posted_at,external_ref,body,mentor_entry_id,created_at)"
                        + " VALUES (?,?,?,'CHAT_TURN',0,'POSTED',?,?,?,?,?,'Add a focused regression test.',?,?)",
                id,
                workspace.getId(),
                parent.getId(),
                id.toString(),
                FeedbackIds.writeMarker(id),
                Timestamp.from(AT),
                Timestamp.from(AT),
                entry.toString(),
                entry,
                Timestamp.from(AT));
    }

    @Test
    @WithAdminUser
    void shouldApplyLocationBeforePaginationAndUseTheSameFilteredCounts() {
        piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, null);
        var first = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        var second = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        var expected = Stream.of(first, second)
                .min(Comparator.comparing(row -> row.getId().toString()))
                .orElseThrow();
        client.get()
                .uri(route() + "?location=PRACTICE_PAGES&sort=OLDEST&size=1")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(expected.getId().toString());
        client.get()
                .uri(route() + "/locations?location=PRACTICE_PAGES")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].reason")
                .isEqualTo("PRACTICE_PAGES")
                .jsonPath("$[0].count")
                .isEqualTo(2);
    }

    @Test
    @WithAdminUser
    void shouldSelectOnlyFeedbackThatPartlyReachedTheWorkForTheListAndItsCounts() {
        var failedAfterExposure = piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, AT);
        var unconfirmedAfterExposure = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, AT);
        piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, null);
        piece(FeedbackDestination.CONVERSATION, FeedbackState.UNCONFIRMED, AT);
        client.get()
                .uri(route() + "?partlyDelivered=true&sort=OLDEST")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[*].id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactlyInAnyOrder(
                                failedAfterExposure.getId().toString(),
                                unconfirmedAfterExposure.getId().toString()));
        client.get()
                .uri(route() + "?partlyDelivered=false")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3);
        client.get()
                .uri(route() + "/locations?partlyDelivered=true")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].count")
                .isEqualTo(2);
    }
}
