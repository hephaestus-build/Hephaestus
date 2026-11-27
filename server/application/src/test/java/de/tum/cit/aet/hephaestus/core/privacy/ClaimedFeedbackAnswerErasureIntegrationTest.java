package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationLlmConfig;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationContextPathBuilder;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.adapter.ConversationPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.adapter.PracticesPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A person's erasure redacts the Hephaestus answers that may show feedback derived from the person's data in a
 * teammate's conversation: every answer of a turn that claimed the feedback, whatever its receipt says, and each answer
 * that read one of them (#5150). The feedback is the teammate's own, from a review whose evidence copied the erased
 * developer's data. The teammate's questions stay.
 */
class ClaimedFeedbackAnswerErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final ConversationLlmConfig ADMITTED = LlmCatalogTestFixtures.admittedConversationConfig();
    private static final String WORDS = "Pin the base image by digest in the release workflow.";
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
    private ConversationContextPathBuilder paths;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private ConversationPersonDataCatalog conversationStores;

    @Autowired
    private PracticesPersonDataCatalog practicesStores;

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private long teammate;
    private PersonIdentity erased;
    private long administrator;
    private Conversation main;
    private UUID copyingJob;
    private Result teammateResult;

    @BeforeEach
    void setUpTeammateConversation() {
        User developer = persistUser("claimed-answer-teammate");
        workspace = createWorkspace("claimed-answer", "Hephaestus", "claimed-answer-org", AccountType.ORG, developer);
        teammate = accountId(developer);
        administrator =
                Objects.requireNonNull(persistInstanceAdmin("Administrator").getId());
        main = conversations.findOrCreateMain(workspace.getId(), teammate);
        var provider = gitProviderRepository.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://claimed-answer.example"));
        userRepository.saveAndFlush(TestUserFactory.createUser(4242L, "erased-developer", provider));
        erased = new PersonIdentity(Objects.requireNonNull(provider.getId()), "4242", null);
        var work = modelFixtures.work(workspace, "claimed-answer-practice", "scm.pull_request", 9_001L);
        teammateResult = modelFixtures.result(work, teammate, "Teammate result");
        copyingJob = work.job().getId();
        // The review's evidence copied the erased developer's data; its folder is already gone.
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)"
                        + " VALUES (?,?,?,'ERASED',CAST(? AS jsonb))",
                UUID.randomUUID(),
                workspace.getId(),
                copyingJob,
                mapper.writeValueAsString(Map.of(
                        "identities",
                        List.of(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), "4242", null)),
                        "repositories",
                        List.of())));
    }

    /** The message that showed the feedback goes with the person, and so does a later answer whose basis read it. */
    @Test
    void shouldRedactALaterAnswerThatReadTheMessageWhichShowedTheFeedback() {
        ConversationTurn showing = attempt(ConversationTurn.Surface.APP);
        UUID feedback = claimedAbout(showing);
        end(
                showing,
                List.of(
                        ConversationLedgerFixtures.showFeedback(
                                ConversationLedgerFixtures.piId(),
                                "Here is what I saw.",
                                Map.of("call-1", feedback),
                                WORDS),
                        shown("call-1")));
        assertThat(settled(feedback)).startsWith("DELIVERED/POSTED/");
        ConversationTurn followUp = attempt(ConversationTurn.Surface.APP);
        paths.forTurn(workspace.getId(), followUp.getId(), ZoneOffset.UTC);
        end(followUp, List.of(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "So: " + WORDS)));

        erase();

        assertThat(answers(showing)).hasSize(1).allSatisfy(this::assertRedacted);
        assertThat(answers(followUp))
                .as("its basis read the message")
                .hasSize(1)
                .allSatisfy(this::assertRedacted);
        assertNoEntryHoldsTheWordsAndTheQuestionsStay();
    }

    /** The turn's answers after the call read the words too, although their basis predates the turn. */
    @Test
    void shouldRedactEveryAnswerOfTheTurnThatShowedTheFeedback() {
        ConversationTurn turn = attempt(ConversationTurn.Surface.APP);
        UUID feedback = claimedAbout(turn);
        end(
                turn,
                List.of(
                        ConversationLedgerFixtures.showFeedback(
                                ConversationLedgerFixtures.piId(),
                                "Here is what I saw.",
                                Map.of("call-1", feedback),
                                WORDS),
                        shown("call-1"),
                        ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "In short: " + WORDS)));
        assertThat(settled(feedback)).startsWith("DELIVERED/POSTED/");

        erase();

        assertThat(answers(turn)).hasSize(2).allSatisfy(this::assertRedacted);
        assertNoEntryHoldsTheWordsAndTheQuestionsStay();
    }

    /** Silent Mode keeps the Slack reply inside the instance, so the claim settles not shown and names no answer. */
    @Test
    void shouldRedactTheAnswersOfASlackTurnWhoseReplySilentModeKept() {
        ConversationTurn turn = attempt(ConversationTurn.Surface.SLACK);
        UUID feedback = claimedAbout(turn);
        end(
                turn,
                List.of(
                        ConversationLedgerFixtures.showFeedback(
                                ConversationLedgerFixtures.piId(),
                                "Here is what I saw.",
                                Map.of("call-1", feedback),
                                WORDS),
                        shown("call-1"),
                        ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "In short: " + WORDS)));
        completion.recordSilenced(workspace.getId(), turn.getId());
        assertThat(settled(feedback)).isEqualTo("FAILED/FAILED/-");

        erase();

        assertThat(answers(turn)).hasSize(2).allSatisfy(this::assertRedacted);
        assertNoEntryHoldsTheWordsAndTheQuestionsStay();
    }

    /**
     * The runner trims the id it asks the server for and stores the call as the model wrote it. The settlement does not
     * recognize the padded id, so the claim settles not shown, although the model read the words.
     */
    @Test
    void shouldRedactTheAnswersOfATurnWhoseCallNamedTheFeedbackWithPadding() {
        ConversationTurn turn = attempt(ConversationTurn.Surface.APP);
        UUID feedback = claimedAbout(turn);
        end(
                turn,
                List.of(
                        ConversationLedgerFixtures.toolCall(
                                ConversationLedgerFixtures.piId(),
                                "call-1",
                                "show_feedback",
                                mapper.createObjectNode()
                                        .put("feedbackId", " " + feedback + " ")
                                        .put("text", WORDS)),
                        shown("call-1"),
                        ConversationLedgerFixtures.answer(
                                ConversationLedgerFixtures.piId(), "That was the feedback.")));
        assertThat(settled(feedback)).isEqualTo("FAILED/FAILED/-");

        erase();

        assertThat(answers(turn)).hasSize(2).allSatisfy(this::assertRedacted);
        assertNoEntryHoldsTheWordsAndTheQuestionsStay();
    }

    /**
     * A receipt that binds its answer after the steps selected what to erase. The first step's selection names no
     * answer, so the {@code feedback_copy} step finds it through the claimed turn. No supported flow reaches this today:
     * an unsettled claim refuses the erasure (#5440).
     */
    @Test
    void shouldRedactTheAnswersOfAReceiptThatBindsAfterTheStepsSelected() {
        ConversationTurn turn = attempt(ConversationTurn.Surface.APP);
        UUID feedback = claimedAbout(turn);
        var scope =
                new PersonScope(null, List.of(erased), List.of(), List.of(), List.of(), List.of(), List.of(copyingJob));
        PersonDataContributor messages = step(conversationStores, "conversation_entry_feedback_copy");
        PersonDataContributor copies = step(practicesStores, "feedback_copy");
        var selectedMessages = messages.select(scope);
        var selectedCopies = copies.select(scope);
        assertThat(selectedCopies.rows()).hasSize(1);
        end(
                turn,
                List.of(
                        ConversationLedgerFixtures.showFeedback(
                                ConversationLedgerFixtures.piId(),
                                "Here is what I saw.",
                                Map.of("call-1", feedback),
                                WORDS),
                        shown("call-1"),
                        ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "In short: " + WORDS)));
        assertThat(settled(feedback)).startsWith("DELIVERED/POSTED/");

        transactions.executeWithoutResult(tx -> messages.erase(selectedMessages, scope));
        transactions.executeWithoutResult(tx -> copies.erase(selectedCopies, scope));

        assertThat(answers(turn)).hasSize(2).allSatisfy(this::assertRedacted);
        assertNoEntryHoldsTheWordsAndTheQuestionsStay();
    }

    private static PersonDataContributor step(PersonDataCatalog catalog, String store) {
        return catalog.contributors().stream()
                .filter(contributor -> contributor.store().equals(store))
                .findFirst()
                .orElseThrow();
    }

    /** A claimed and admitted attempt of a new question in the teammate's conversation. */
    private ConversationTurn attempt(ConversationTurn.Surface surface) {
        var asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        surface,
                        "What did the review say?",
                        UUID.randomUUID().toString(),
                        surface == ConversationTurn.Surface.SLACK
                                ? "1700000000." + String.format("%06d", SLACK_TS.incrementAndGet())
                                : null,
                        null));
        ConversationTurn turn = claims.claim(asked.turn());
        transactions.executeWithoutResult(tx -> turns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                ConversationTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))
                        .toJson()));
        return turn;
    }

    /** Feedback for the teammate that the attempt claimed, with the teammate's result from the copying review as its basis. */
    private UUID claimedAbout(ConversationTurn turn) {
        UUID feedback = ConversationFeedbackRows.claimedBy(jdbc, workspace.getId(), teammate, turn.getId(), WORDS);
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,person_account_id,ordinal)"
                        + " VALUES(?,?,?,?,?,0)",
                FeedbackIds.basis(feedback, teammateResult.getId(), null),
                workspace.getId(),
                feedback,
                teammateResult.getId(),
                teammate);
        return feedback;
    }

    private static JsonNode shown(String callId) {
        return ConversationLedgerFixtures.toolResult(
                ConversationLedgerFixtures.piId(), callId, "show_feedback", "Shown", false);
    }

    private void end(ConversationTurn turn, List<JsonNode> entries) {
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(ConversationTurn.Status.DONE, entries, null, null, null, null));
    }

    private String settled(UUID feedback) {
        ConversationReceipts.awaitSettled(jdbc);
        return ConversationFeedbackRows.settled(jdbc, feedback);
    }

    private void erase() {
        var preview = personData.preview(administrator, null, List.of(erased));
        assertThat(preview.request().getScopeJson()).contains(copyingJob.toString());
        UUID request = preview.request().getId();
        personData.requestErasure(request, administrator, false);
        personData.run(request);
        var receipt = personData.get(request).request();
        assertThat(receipt.getState())
                .as("erasure failure: %s", receipt.getFailureCode())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
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

    private void assertRedacted(UUID entry) {
        assertThat(jdbc.queryForObject(
                        "SELECT redacted_at IS NOT NULL AND text='' FROM conversation_entry WHERE id=?",
                        Boolean.class,
                        entry))
                .as("answer %s is redacted", entry)
                .isTrue();
    }

    private void assertNoEntryHoldsTheWordsAndTheQuestionsStay() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_entry WHERE conversation_id=?"
                                + " AND strpos(text || COALESCE(CAST(payload AS text),''), ?) > 0",
                        Long.class,
                        main.getId(),
                        WORDS))
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT redacted_at IS NULL FROM conversation_entry WHERE conversation_id=? AND kind='USER'",
                        Boolean.class,
                        main.getId()))
                .isNotEmpty()
                .containsOnly(true);
    }
}
