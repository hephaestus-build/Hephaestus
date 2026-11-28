package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationLlmConfig;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor.ExternalDelivery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * A person's erasure admits a conversation copy that a receipt settled unconfirmed (#5440). Its words stay in
 * Hephaestus, which the erasure redacts, unless a Slack turn that ended done posted a reply that may hold them. Then
 * the preview lists that reply, and the erasure waits for an administrator to confirm its removal. A claim that no
 * receipt settled still refuses.
 */
class UnconfirmedConversationCopyErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final ConversationLlmConfig ADMITTED = LlmCatalogTestFixtures.admittedConversationConfig();
    private static final String WORDS = "Pin the base image by digest in the release workflow.";
    private static final String TEAM = "T0CLAIMED";
    private static final String CHANNEL = "D0CLAIMED";
    private static final AtomicLong SLACK_TS = new AtomicLong();

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private ConversationTurnCompletion completion;

    @Autowired
    private ConversationSlackMessages slackMessages;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private AccountDeletionAdmission deletions;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private long developer;
    private long administrator;
    private Conversation main;

    @BeforeEach
    void setUpDeveloperConversation() {
        User owner = persistUser("unconfirmed-copy-owner");
        workspace = createWorkspace("unconfirmed-copy", "Hephaestus", "unconfirmed-copy-org", AccountType.ORG, owner);
        User user = persistUser("unconfirmed-copy-developer");
        ensureMembership(workspace, user, Membership.WorkspaceRole.MEMBER);
        developer = accountId(user);
        administrator =
                Objects.requireNonNull(persistInstanceAdmin("Administrator").getId());
        main = conversations.findOrCreateMain(workspace.getId(), developer);
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, "U0CLAIMED", CHANNEL);
    }

    /** The answer stopped after it showed the feedback; nothing left Hephaestus. */
    @Test
    void shouldEraseAPersonWhoseAppAnswerStoppedAfterItShowedTheirFeedback() {
        Asked asked = ask(main, ConversationTurn.Surface.APP);
        UUID feedback = claimed(asked.turn());
        turns.stop(workspace.getId(), asked.turn().getId());
        end(asked.turn(), ConversationTurn.Status.STOPPED, showing(feedback));
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");

        var preview = personData.preview(administrator, developer, List.of());
        assertThat(preview.externalDeliveries()).isEmpty();
        personData.requestErasure(preview.request().getId(), administrator, false);
        personData.run(preview.request().getId());

        assertComplete(preview.request().getId());
        assertThat(copies(feedback)).isZero();
        assertThat(unredactedAnswers(asked.turn()))
                .as("the step redacts the claimed turn (#5446)")
                .isZero();
    }

    /** The developer's own account deletion finishes; before, it answered 409 for good. */
    @Test
    void shouldFinishTheDevelopersAccountDeletionWhenTheirAppAnswerStopped() {
        Asked asked = ask(main, ConversationTurn.Surface.APP);
        UUID feedback = claimed(asked.turn());
        turns.stop(workspace.getId(), asked.turn().getId());
        end(asked.turn(), ConversationTurn.Status.STOPPED, showing(feedback));
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");

        assertThat(deletions.needsAdministrator(developer)).isFalse();
        personData.eraseForAccount(developer);

        assertThat(accountRepository.findById(developer).orElseThrow().getStatus())
                .isEqualTo(Account.Status.DELETED);
        assertThat(copies(feedback)).isZero();
    }

    /** A failed Slack turn posts a failure notice without the feedback's words. */
    @Test
    void shouldKeepTheCopyOfAFailedSlackTurnInsideHephaestus() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.FAILED, showing(feedback));
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");

        assertThat(personData.preview(administrator, developer, List.of()).externalDeliveries())
                .isEmpty();
    }

    /** Slack did not confirm the post: the reply, if Slack has it, is the first one after the question. */
    @Test
    void shouldListASlackReplyThatSlackDidNotConfirmAndEraseOnlyAfterItsRemoval() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.DONE, showing(feedback));
        completion.recordUncertain(workspace.getId(), asked.turn().getId());
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");

        var preview = personData.preview(administrator, developer, List.of());
        assertThat(preview.externalDeliveries())
                .containsExactly(new ExternalDelivery(
                        workspace.getId(),
                        "slack:" + TEAM + "/" + CHANNEL + "?after=" + asked.slackTs() + "&question="
                                + asked.question()));
        UUID request = preview.request().getId();
        assertThatThrownBy(() -> personData.requestErasure(request, administrator, false))
                .isInstanceOf(ResponseStatusException.class);
        personData.requestErasure(request, administrator, true);
        personData.run(request);

        assertComplete(request);
        assertThat(unredactedAnswers(asked.turn())).isZero();
    }

    /** The teammate deleted the question, which forgets the post's outbox row; the reply can still be in Slack. */
    @Test
    void shouldKeepListingAnUnconfirmedSlackReplyAfterTheQuestionIsDeleted() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.DONE, showing(feedback));
        completion.recordUncertain(workspace.getId(), asked.turn().getId());
        redaction.redact(List.of(asked.question()));

        assertThat(personData.preview(administrator, developer, List.of()).externalDeliveries())
                .extracting(ExternalDelivery::locator)
                .containsExactly("slack:" + TEAM + "/" + CHANNEL + "?after=" + asked.slackTs() + "&question="
                        + asked.question());
    }

    /** In a thread, the reply follows the question in that thread. */
    @Test
    void shouldNameTheThreadOfAnUnconfirmedSlackReplyToAThreadedQuestion() {
        Asked root = ask(main, ConversationTurn.Surface.SLACK);
        end(root.turn(), ConversationTurn.Status.DONE, List.of(answer("The image is pinned.")));
        UUID rootAnswer = answers(root.turn()).getFirst();
        String rootTs = nextSlackTs();
        slackMessages.link(workspace.getId(), rootAnswer, rootTs);
        Conversation thread = conversations.openThread(workspace.getId(), rootAnswer);
        Asked asked = ask(thread, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.DONE, showing(feedback));
        completion.recordUncertain(workspace.getId(), asked.turn().getId());

        assertThat(personData.preview(administrator, developer, List.of()).externalDeliveries())
                .extracting(ExternalDelivery::locator)
                .containsExactly("slack:" + TEAM + "/" + CHANNEL + "?after=" + asked.slackTs() + "&thread=" + rootTs
                        + "&question=" + asked.question());
    }

    /** Slack showed part of the feedback: the reply is in Slack, and the account deletion waits for its removal. */
    @Test
    void shouldStopTheAccountDeletionForAPartlyShownSlackReplyUntilAnAdministratorConfirmsItsRemoval() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.DONE, showing(feedback));
        String replyTs = nextSlackTs();
        slackMessages.link(workspace.getId(), answers(asked.turn()).getFirst(), replyTs);
        completion.recordDelivery(workspace.getId(), asked.turn().getId(), Set.of(), Set.of(feedback));
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");

        UUID request = admitAccountDeletionThatWaitsForAnAdministrator();
        var stopped = personData.get(request);
        assertThat(stopped.request().getFailureCode()).isEqualTo("EXTERNAL_COPIES_REQUIRE_REMOVAL");
        assertThat(stopped.externalDeliveries())
                .extracting(ExternalDelivery::locator)
                .containsExactly("slack:" + TEAM + "/" + CHANNEL + "/" + replyTs + "?question=" + asked.question());
        personData.requestErasure(request, administrator, true);
        personData.run(request);

        assertComplete(request);
    }

    /**
     * The developer linked another Slack account after the reply, which rebinds the conversation to a new DM. The locator
     * names the DM of now and the question that the reply carries as metadata, so the runbook can find the reply in the
     * earlier DM.
     */
    @Test
    void shouldNameTheQuestionOfAPostedReplyAfterTheDeveloperLinksAnotherSlackAccount() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        end(asked.turn(), ConversationTurn.Status.DONE, showing(feedback));
        String replyTs = nextSlackTs();
        slackMessages.link(workspace.getId(), answers(asked.turn()).getFirst(), replyTs);
        completion.recordDelivery(workspace.getId(), asked.turn().getId(), Set.of(), Set.of(feedback));
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, "U0RELINKED", "D0RELINKED");

        assertThat(personData.preview(administrator, developer, List.of()).externalDeliveries())
                .extracting(ExternalDelivery::locator)
                .containsExactly("slack:" + TEAM + "/D0RELINKED/" + replyTs + "?question=" + asked.question());
    }

    /** A lost attempt posted nothing; the retry's reply holds only the retry's own claims. */
    @Test
    void shouldKeepALostAttemptsCopyInsideHephaestusWhenTheRetryPostsAReply() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        UUID feedback = claimed(asked.turn());
        ConversationTurnService.LostAttempt lost = transactions.execute(
                tx -> turns.requeueRunning(workspace.getId(), main.getId()).orElseThrow());
        assertThat(Objects.requireNonNull(lost).attempt())
                .isEqualTo(asked.turn().getAttempt());
        assertThat(settled(feedback)).isEqualTo("UNCONFIRMED/WRITE_STARTED/-");
        ConversationTurn retry = claims.claimNext().orElseThrow();
        admit(retry);
        end(retry, ConversationTurn.Status.DONE, List.of(answer("The image is pinned.")));
        slackMessages.link(workspace.getId(), answers(retry).getFirst(), nextSlackTs());
        completion.recordDelivery(workspace.getId(), retry.getId(), Set.of(), Set.of());

        assertThat(personData.preview(administrator, developer, List.of()).externalDeliveries())
                .isEmpty();
    }

    /** A claim without a receipt may still be on its way to Slack. */
    @Test
    void shouldStillRefuseAClaimThatNoReceiptSettled() {
        Asked asked = ask(main, ConversationTurn.Surface.SLACK);
        claimed(asked.turn());

        assertThatThrownBy(() -> personData.preview(administrator, developer, List.of()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private record Asked(
            ConversationTurn turn, UUID question, @Nullable String slackTs) {}

    /** A claimed and admitted attempt of a new question in the conversation. */
    private Asked ask(Conversation conversation, ConversationTurn.Surface surface) {
        String ts = surface == ConversationTurn.Surface.SLACK ? nextSlackTs() : null;
        var submitted = turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        surface, "What did the review say?", UUID.randomUUID().toString(), ts, null));
        ConversationTurn turn = claims.claim(submitted.turn());
        admit(turn);
        return new Asked(turn, submitted.question().getId(), ts);
    }

    private void admit(ConversationTurn turn) {
        transactions.executeWithoutResult(tx -> turns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                ConversationTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))
                        .toJson()));
    }

    /** The developer's feedback that the attempt claimed through {@code show_feedback}. */
    private UUID claimed(ConversationTurn turn) {
        return ConversationFeedbackRows.claimedBy(jdbc, workspace.getId(), developer, turn.getId(), WORDS);
    }

    private static List<JsonNode> showing(UUID feedback) {
        return List.of(
                ConversationLedgerFixtures.showFeedback(
                        ConversationLedgerFixtures.piId(), "Here is what I saw.", Map.of("call-1", feedback), WORDS),
                ConversationLedgerFixtures.toolResult(
                        ConversationLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false));
    }

    private static JsonNode answer(String text) {
        return ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), text);
    }

    private void end(ConversationTurn turn, ConversationTurn.Status outcome, List<JsonNode> entries) {
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        outcome, entries, outcome == ConversationTurn.Status.FAILED ? "x" : null, null, null, null));
    }

    private String settled(UUID feedback) {
        ConversationReceipts.awaitSettled(jdbc);
        return ConversationFeedbackRows.settled(jdbc, feedback);
    }

    /** The developer deletes their account, and the request that waits for an administrator. */
    private UUID admitAccountDeletionThatWaitsForAnAdministrator() {
        assertThat(deletions.needsAdministrator(developer)).isTrue();
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM person_data_request WHERE (scope_json::jsonb)->>'accountId'=?"
                        + " ORDER BY created_at DESC LIMIT 1",
                UUID.class,
                Long.toString(developer)));
    }

    private void assertComplete(UUID request) {
        var receipt = personData.get(request).request();
        assertThat(receipt.getState())
                .as("erasure failure: %s", receipt.getFailureCode())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    private long copies(UUID feedback) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM feedback_copy WHERE feedback_id=?", Long.class, feedback));
    }

    private long unredactedAnswers(ConversationTurn turn) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM conversation_entry WHERE turn_id=? AND kind='ANSWER' AND redacted_at IS NULL",
                Long.class,
                turn.getId()));
    }

    private List<UUID> answers(ConversationTurn turn) {
        return jdbc
                .queryForList(
                        "SELECT id FROM conversation_entry WHERE turn_id=? AND kind='ANSWER' ORDER BY seq",
                        UUID.class,
                        turn.getId())
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private static String nextSlackTs() {
        return "1700000000." + String.format("%06d", SLACK_TS.incrementAndGet());
    }
}
