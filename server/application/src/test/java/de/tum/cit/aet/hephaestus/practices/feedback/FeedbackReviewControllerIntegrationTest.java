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
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class FeedbackReviewControllerIntegrationTest extends AbstractFeedbackIntegrationTest {
    /** A read of the developer's conversation; a column such as {@code conversation_entry_id} is not one. */
    private static final Pattern CONVERSATION_TABLE =
            Pattern.compile("\\bconversation(_entry|_turn)?\\b", Pattern.CASE_INSENSITIVE);

    /** The location value, which names no table. Only this literal is removed, so no identifier can hide. */
    private static final String LOCATION_LITERAL = "'CONVERSATION'";

    @Autowired
    WebTestClient client;

    @Autowired
    SqlRequestTrace requests;

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
                .jsonPath("$.answer")
                .isEmpty()
                .jsonPath("$.basis")
                .isArray();
    }

    /** A rating, and every note but a disagreement's explanation, stay the developer's alone (ADR 0047). */
    @Test
    @WithAdminUser
    void shouldShowAnAdminTheAnswerAndOnlyADisagreementsExplanation() {
        var addressed = answered(FeedbackResolution.ADDRESSED, FeedbackUsefulness.HELPFUL, "Fixed it, thanks.");
        var notApplicable = answered(FeedbackResolution.NOT_APPLICABLE, null, "This file is generated.");
        var disagreed = answered(
                FeedbackResolution.DISAGREE,
                FeedbackUsefulness.UNHELPFUL,
                "The rule allows this.",
                Instant.now().minus(Duration.ofDays(1)));
        var ratedOnly = answered(null, FeedbackUsefulness.HELPFUL, "Private note.");

        answer(addressed)
                .jsonPath("$.answer.resolution")
                .isEqualTo("ADDRESSED")
                .jsonPath("$.answer.createdAt")
                .exists()
                .jsonPath("$.answer.comment")
                .isEmpty()
                .jsonPath("$.answer.usefulness")
                .doesNotExist()
                .jsonPath("$.response")
                .doesNotExist();
        answer(notApplicable)
                .jsonPath("$.answer.resolution")
                .isEqualTo("NOT_APPLICABLE")
                .jsonPath("$.answer.comment")
                .isEmpty()
                .jsonPath("$.answer.usefulness")
                .doesNotExist();
        answer(disagreed)
                .jsonPath("$.answer.resolution")
                .isEqualTo("DISAGREE")
                .jsonPath("$.answer.comment")
                .isEqualTo("The rule allows this.")
                .jsonPath("$.answer.usefulness")
                .doesNotExist();
        answer(ratedOnly).jsonPath("$.answer").isEmpty();
    }

    /** ADR 0125 decision 3: a disagreement's explanation reaches an admin only as a correction, for 30 days. */
    @Test
    @WithAdminUser
    void shouldShowADisagreementsExplanationOnlyFor30Days() {
        var now = Instant.now();
        var fresh = answered(FeedbackResolution.DISAGREE, null, "Still a correction.", now.minus(Duration.ofDays(29)));
        var old =
                answered(FeedbackResolution.DISAGREE, null, "No longer a correction.", now.minus(Duration.ofDays(31)));

        answer(fresh).jsonPath("$.answer.comment").isEqualTo("Still a correction.");
        answer(old)
                .jsonPath("$.answer.resolution")
                .isEqualTo("DISAGREE")
                .jsonPath("$.answer.createdAt")
                .exists()
                .jsonPath("$.answer.comment")
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldKeepTheAnswerTimeWhenOnlyThePrivateRatingOrNoteChanges() {
        var addressed = answered(FeedbackResolution.ADDRESSED, FeedbackUsefulness.HELPFUL, "Fixed it.");
        respond(addressed, FeedbackResolution.ADDRESSED, FeedbackUsefulness.UNHELPFUL, "Changed my mind.", 1);
        var disagreed = answered(FeedbackResolution.DISAGREE, FeedbackUsefulness.HELPFUL, "The rule allows this.");
        respond(disagreed, FeedbackResolution.DISAGREE, FeedbackUsefulness.UNHELPFUL, "The rule allows this.", 1);
        var explained = answered(FeedbackResolution.DISAGREE, null, "The rule allows this.");
        respond(explained, FeedbackResolution.DISAGREE, null, "The rule allows this here.", 1);
        var answeredAgain = answered(FeedbackResolution.ADDRESSED, null, "Fixed it.");
        respond(answeredAgain, FeedbackResolution.NOT_APPLICABLE, null, null, 1);
        respond(answeredAgain, FeedbackResolution.ADDRESSED, null, null, 2);

        answer(addressed).jsonPath("$.answer.createdAt").isEqualTo(AT.toString());
        answer(disagreed)
                .jsonPath("$.answer.createdAt")
                .isEqualTo(AT.toString())
                .jsonPath("$.disagreedAt")
                .isEqualTo(AT.toString());
        // The explanation of a disagreement is part of the answer that an admin reads, so its change is a new answer.
        answer(explained)
                .jsonPath("$.answer.createdAt")
                .isEqualTo(AT.plusSeconds(1).toString())
                .jsonPath("$.disagreedAt")
                .isEqualTo(AT.plusSeconds(1).toString());
        answer(answeredAgain)
                .jsonPath("$.answer.createdAt")
                .isEqualTo(AT.plusSeconds(2).toString());
    }

    private Feedback answered(
            @Nullable FeedbackResolution resolution, @Nullable FeedbackUsefulness usefulness, String comment) {
        return answered(resolution, usefulness, comment, AT);
    }

    private Feedback answered(
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            String comment,
            Instant at) {
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        respond(parent, resolution, usefulness, comment, at);
        return parent;
    }

    private void respond(
            Feedback parent,
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment,
            long secondsLater) {
        respond(parent, resolution, usefulness, comment, AT.plusSeconds(secondsLater));
    }

    private void respond(
            Feedback parent,
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment,
            Instant at) {
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .resolution(resolution)
                .usefulness(usefulness)
                .comment(comment)
                .createdAt(at)
                .build());
    }

    private WebTestClient.BodyContentSpec answer(Feedback parent) {
        return client.get()
                .uri(route() + "/" + parent.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
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

    /**
     * The staff review locates feedback for a developer's conversation without reading that conversation: one location,
     * whether an answer in the app or in Slack raised it, or none did yet. The developer is another account than the
     * admin who reads. The trace covers the statements that each request prepares on its own thread, the page count
     * included.
     */
    @Test
    @WithAdminUser
    void shouldLocateConversationFeedbackWithoutReadingTheDevelopersConversation() {
        long developer =
                Objects.requireNonNull(persistAccount("Conversation developer").getId());
        workspaceMembershipService.createMembership(workspace, developer, Membership.WorkspaceRole.MEMBER);
        var inApp = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var inSlack = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var waiting = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        for (var piece : List.of(inApp, inSlack, waiting)) {
            jdbc.update("UPDATE feedback SET person_account_id = ? WHERE id = ?", developer, piece.getId());
        }
        UUID conversation = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,?,?)",
                conversation,
                workspace.getId(),
                developer,
                Timestamp.from(AT),
                Timestamp.from(AT));
        shownIn(inApp, conversation, 1, "APP");
        shownIn(inSlack, conversation, 2, "SLACK");

        readsNoConversation(
                "the first page of one, which counts the rest",
                requests.measure(
                                client,
                                http -> http.get()
                                        .uri(route() + "?location=CONVERSATION&size=1")
                                        .headers(TestAuthUtils.withCurrentUser())
                                        .exchange()
                                        .expectStatus()
                                        .isOk()
                                        .expectBody()
                                        .jsonPath("$.totalElements")
                                        .isEqualTo(3)
                                        .jsonPath("$.content[*].location")
                                        .isEqualTo(List.of("CONVERSATION")))
                        .sql());
        readsNoConversation(
                "the location counts",
                requests.measure(
                                client,
                                http -> http.get()
                                        .uri(route() + "/locations")
                                        .headers(TestAuthUtils.withCurrentUser())
                                        .exchange()
                                        .expectStatus()
                                        .isOk()
                                        .expectBody()
                                        .jsonPath("$.length()")
                                        .isEqualTo(1)
                                        .jsonPath("$[0].reason")
                                        .isEqualTo("CONVERSATION")
                                        .jsonPath("$[0].count")
                                        .isEqualTo(3))
                        .sql());
        readsNoConversation(
                "the reason counts",
                requests.measure(
                                client,
                                http -> http.get()
                                        .uri(route() + "/reasons")
                                        .headers(TestAuthUtils.withCurrentUser())
                                        .exchange()
                                        .expectStatus()
                                        .isOk()
                                        .expectBody(Void.class))
                        .sql());
        for (var piece : List.of(inApp, inSlack, waiting)) {
            readsNoConversation(
                    "the detail of " + piece.getId(),
                    requests.measure(
                                    client,
                                    http -> http.get()
                                            .uri(route() + "/" + piece.getId())
                                            .headers(TestAuthUtils.withCurrentUser())
                                            .exchange()
                                            .expectStatus()
                                            .isOk()
                                            .expectBody()
                                            .jsonPath("$.location")
                                            .isEqualTo("CONVERSATION"))
                            .sql());
            readsNoConversation(
                    "the decisions of " + piece.getId(),
                    requests.measure(
                                    client,
                                    http -> http.get()
                                            .uri(route() + "/" + piece.getId() + "/decisions")
                                            .headers(TestAuthUtils.withCurrentUser())
                                            .exchange()
                                            .expectStatus()
                                            .isOk()
                                            .expectBody(Void.class))
                            .sql());
            readsNoConversation(
                    "the lineage of " + piece.getId(),
                    requests.measure(
                                    client,
                                    http -> http.get()
                                            .uri("/workspaces/" + workspace.getWorkspaceSlug()
                                                    + "/practices/reviews/lineage?kind=FEEDBACK&feedbackId="
                                                    + piece.getId())
                                            .headers(TestAuthUtils.withCurrentUser())
                                            .exchange()
                                            .expectStatus()
                                            .isOk()
                                            .expectBody(Void.class))
                            .sql());
        }
        readsNoConversation(
                "the review's feedback checks",
                requests.measure(
                                client,
                                http -> http.get()
                                        .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/practices/reviews/"
                                                + reviewId + "/feedback-checks")
                                        .headers(TestAuthUtils.withCurrentUser())
                                        .exchange()
                                        .expectStatus()
                                        .isOk()
                                        .expectBody(Void.class))
                        .sql());
    }

    private static void readsNoConversation(String request, List<String> sql) {
        assertThat(sql)
                .as("the statements of %s", request)
                .isNotEmpty()
                .noneMatch(statement -> CONVERSATION_TABLE
                        .matcher(statement.replace(LOCATION_LITERAL, "''"))
                        .find());
    }

    /** A conversation copy of {@code parent} posted in a Hephaestus message written on {@code surface}. */
    private void shownIn(Feedback parent, UUID conversation, int seq, String surface) {
        UUID entry = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,"
                        + "created_at) VALUES (?,?,?,(SELECT account_id FROM conversation WHERE id = ?),?,'ANSWER',?,"
                        + "'Add a focused regression test.',?)",
                entry,
                workspace.getId(),
                conversation,
                conversation,
                seq,
                surface,
                Timestamp.from(AT));
        UUID id = FeedbackIds.copy(parent.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_key,write_marker,"
                        + "write_started_at,posted_at,external_ref,body,conversation_entry_id,created_at)"
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
