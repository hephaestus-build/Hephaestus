package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The ledger's concurrency guards, each driven by two writers at once against PostgreSQL. The first writer holds its
 * transaction open; the second starts and either waits for it or finishes, and only then does the first commit.
 * Without the guard, the second writer reads before the first commits, and the test fails.
 */
class ConversationLedgerConcurrencyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

    private static final int PATIENCE_SECONDS = 20;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private ConversationContextPathBuilder paths;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Conversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("ask-concurrency-developer");
        workspace = createWorkspace("ask-concurrency", "Hephaestus", "ask-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
    }

    @Test
    void shouldNumberEntriesConsecutivelyWhenTwoWritersAppendToOneConversationAtOnce() throws Exception {
        AtomicReference<ConversationEntry> first = new AtomicReference<>();
        HeldTransaction holder =
                holdIn(() -> first.set(entries.append(workspace.getId(), main.getId(), hephaestus("One"))));
        CompletableFuture<ConversationEntry> second =
                CompletableFuture.supplyAsync(() -> entries.append(workspace.getId(), main.getId(), hephaestus("Two")));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getSeq())
                .isEqualTo(first.get().getSeq() + 1);
    }

    @Test
    void shouldInsertOnceWhenTwoRunsAppendTheSameServerWriteAtOnce() throws Exception {
        AtomicReference<ConversationEntryService.Appended> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(
                entries.appendOnce(workspace.getId(), main.getId(), ConversationLedgerFixtures.digest("wd-race"))));
        CompletableFuture<ConversationEntryService.Appended> second = CompletableFuture.supplyAsync(() ->
                entries.appendOnce(workspace.getId(), main.getId(), ConversationLedgerFixtures.digest("wd-race")));

        holder.commitAfter(second);

        ConversationEntryService.Appended again = second.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(first.get().inserted()).isTrue();
        assertThat(again.inserted()).isFalse();
        assertThat(again.entry().getId()).isEqualTo(first.get().entry().getId());
    }

    @Test
    void shouldQueueOneTurnWhenTheSameSubmissionArrivesTwiceAtOnce() throws Exception {
        ConversationTurnService.Question question = new ConversationTurnService.Question(
                ConversationTurn.Surface.APP,
                "Is this ready?",
                UUID.randomUUID().toString(),
                null,
                null);
        AtomicReference<ConversationTurnService.Submission> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(turns.submit(workspace.getId(), main.getId(), question)));
        CompletableFuture<ConversationTurnService.Submission> retry =
                CompletableFuture.supplyAsync(() -> turns.submit(workspace.getId(), main.getId(), question));

        holder.commitAfter(retry);

        ConversationTurnService.Submission again = retry.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(first.get().created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.turn().getId()).isEqualTo(first.get().turn().getId());
        assertThat(again.question().getId()).isEqualTo(first.get().question().getId());
    }

    @Test
    void shouldOpenOneThreadWhenTwoRepliesOpenItAtOnce() throws Exception {
        ConversationEntry parent = entries.append(workspace.getId(), main.getId(), hephaestus("Parent"));
        AtomicReference<Conversation> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(conversations.openThread(workspace.getId(), parent.getId())));
        CompletableFuture<Conversation> second =
                CompletableFuture.supplyAsync(() -> conversations.openThread(workspace.getId(), parent.getId()));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getId())
                .isEqualTo(first.get().getId());
    }

    @Test
    void shouldRefuseTheThreadWhenItsParentIsErasedWhileTheThreadOpens() throws Exception {
        ConversationEntry parent = entries.append(workspace.getId(), main.getId(), hephaestus("Parent"));
        HeldTransaction erasure = holdIn(() -> redaction.redact(List.of(parent.getId())));
        CompletableFuture<Conversation> thread =
                CompletableFuture.supplyAsync(() -> conversations.openThread(workspace.getId(), parent.getId()));

        erasure.commitAfter(thread);

        assertThatThrownBy(() -> thread.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.NOT_A_THREAD_PARENT));
        assertThat(conversations.findThread(workspace.getId(), parent.getId())).isEmpty();
    }

    @Test
    void shouldCreateOneMainConversationWhenAnAccountsFirstTwoWritesArriveAtOnce() throws Exception {
        long newcomer = accountId(persistUser("ask-concurrency-newcomer"));
        AtomicReference<Conversation> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(conversations.findOrCreateMain(workspace.getId(), newcomer)));
        CompletableFuture<Conversation> second =
                CompletableFuture.supplyAsync(() -> conversations.findOrCreateMain(workspace.getId(), newcomer));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getId())
                .isEqualTo(first.get().getId());
    }

    @Test
    void shouldStartOneTurnOfAConversationWhenTwoWorkersClaimAtOnce() throws Exception {
        ConversationTurn head = submit("First question").turn();
        ConversationTurn next = submit("Second question").turn();
        AtomicReference<Optional<ConversationTurn>> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(claims.claimNext()));
        CompletableFuture<Optional<ConversationTurn>> second = CompletableFuture.supplyAsync(() -> claims.claimNext());

        holder.commitAfter(second);

        assertThat(first.get()).map(ConversationTurn::getId).contains(head.getId());
        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS)).isEmpty();
        ConversationTurn claimed = turnRepository.findById(head.getId()).orElseThrow();
        assertThat(claimed.getRunId()).isNotNull();
        assertThat(claimed.getAttempt()).isEqualTo(1);
        assertThat(turnRepository.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.QUEUED);
    }

    @Test
    void shouldKeepTheErasureWhenAVoteRacesIt() throws Exception {
        ConversationEntry answer = entries.append(workspace.getId(), main.getId(), hephaestus("A secret answer"));
        HeldTransaction holder = holdIn(() -> redaction.redact(List.of(answer.getId())));
        CompletableFuture<ConversationEntry> vote = CompletableFuture.supplyAsync(
                () -> entries.vote(workspace.getId(), answer.getId(), ConversationEntry.Vote.HELPFUL));

        holder.commitAfter(vote);

        assertThatThrownBy(() -> vote.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.NOT_VOTABLE));
        ConversationEntry stored = entryRepository.findById(answer.getId()).orElseThrow();
        assertThat(stored.isRedacted()).isTrue();
        assertThat(stored.getText()).isEmpty();
        assertThat(stored.getVote()).isNull();
    }

    @Test
    void shouldKeepTheDeletionWhenASlackEditRacesIt() throws Exception {
        ConversationTurnService.Submission asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.SLACK, "My token is abc", "slack:T:D:9.0", "9.0", null));
        HeldTransaction holder =
                holdIn(() -> redaction.redact(List.of(asked.question().getId())));
        CompletableFuture<ConversationEntry> edit = CompletableFuture.supplyAsync(
                () -> entries.editSlackMessage(workspace.getId(), main.getId(), "9.0", "My token is abcd"));

        holder.commitAfter(edit);

        assertThatThrownBy(() -> edit.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.NOT_EDITABLE));
        ConversationEntry stored =
                entryRepository.findById(asked.question().getId()).orElseThrow();
        assertThat(stored.isRedacted()).isTrue();
        assertThat(stored.getText()).isEmpty();
        assertThat(stored.getEditedAt()).isNull();
    }

    @Test
    void shouldReadThePathWithoutAMessageWhenItsErasureCommitsWhileThePathIsRead() throws Exception {
        ConversationEntry secret = entries.append(workspace.getId(), main.getId(), hephaestus("Secret plan"));
        ConversationEntry kept = entries.append(workspace.getId(), main.getId(), hephaestus("Kept"));
        ConversationTurnService.Submission asked = submit("What now?");
        HeldTransaction erasure = holdIn(() -> redaction.redact(List.of(secret.getId())));
        ConversationTurn turn = claimNext(asked);
        CompletableFuture<ConversationContextPathBuilder.TurnContext> read =
                CompletableFuture.supplyAsync(() -> paths.forTurn(workspace.getId(), turn.getId(), ZoneOffset.UTC));

        erasure.commitAfter(read);

        assertThat(read.get(PATIENCE_SECONDS, TimeUnit.SECONDS).entries().toString())
                .doesNotContain("Secret plan")
                .contains("Kept");
        // The erasure ran before the path was read, so a summary of that path holds nothing erased.
        ConversationEntry summary = ended(turn, compaction(kept));
        assertThat(summary.isRedacted()).isFalse();
    }

    @Test
    void shouldEraseTheSummaryWhenTheErasureWaitedForThePathRead() throws Exception {
        ConversationEntry secret = entries.append(workspace.getId(), main.getId(), hephaestus("Secret plan"));
        ConversationEntry kept = entries.append(workspace.getId(), main.getId(), hephaestus("Kept"));
        ConversationTurn turn = claimNext(submit("What now?"));
        AtomicReference<ConversationContextPathBuilder.TurnContext> path = new AtomicReference<>();
        HeldTransaction read = holdIn(() -> path.set(paths.forTurn(workspace.getId(), turn.getId(), ZoneOffset.UTC)));
        CompletableFuture<Void> erasure = CompletableFuture.runAsync(() -> redaction.redact(List.of(secret.getId())));

        read.commitAfter(erasure);
        erasure.get(PATIENCE_SECONDS, TimeUnit.SECONDS);

        assertThat(path.get().entries().toString()).contains("Secret plan");
        ConversationEntry summary = ended(turn, compaction(kept));
        assertThat(summary.isRedacted()).isTrue();
        assertThat(summary.getText()).isEmpty();
    }

    @Test
    void shouldEraseWhatAnEndingTurnStoresWhenTheDeveloperDeletesTheQuotedMessageMeanwhile() throws Exception {
        ConversationTurnService.Submission secret = submit("My secret question");
        ConversationTurn first = claimNext(secret);
        ConversationEntry kept =
                ended(first, ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Kept answer"));
        ConversationTurn turn = claimNext(submit("Next"));
        HeldTransaction end = holdIn(() -> turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(compaction(kept), searchResult(secret.question())),
                        null,
                        null)));
        CompletableFuture<Void> deletion = CompletableFuture.runAsync(
                () -> entries.deleteMessage(workspace.getId(), secret.question().getId()));

        end.commitAfter(deletion);
        deletion.get(PATIENCE_SECONDS, TimeUnit.SECONDS);

        assertThat(ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .filteredOn(entry -> entry.getKind() != ConversationEntry.Kind.USER)
                .hasSize(2)
                .allSatisfy(copy -> assertThat(copy.isRedacted()).isTrue());
        assertThat(entryRepository
                        .findById(secret.question().getId())
                        .orElseThrow()
                        .isRedacted())
                .isTrue();
    }

    private ConversationTurn claimNext(ConversationTurnService.Submission submission) {
        ConversationTurn claimed = claims.claimNext().orElseThrow();
        assertThat(claimed.getId()).isEqualTo(submission.turn().getId());
        return claimed;
    }

    /** Ends the turn with one Pi entry its host appended, and returns that entry as stored. */
    private ConversationEntry ended(ConversationTurn turn, JsonNode piEntry) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(piEntry), null, null));
        return ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .getLast();
    }

    /** A compaction that summarizes the conversation before {@code firstKept}. */
    private static ObjectNode compaction(ConversationEntry firstKept) {
        return ConversationLedgerFixtures.compaction(
                ConversationLedgerFixtures.piId(),
                "Talked about a secret",
                ConversationLedgerFixtures.piIdOf(firstKept));
    }

    /** A {@code search_conversation} result that quotes {@code hit}. */
    private static ObjectNode searchResult(ConversationEntry hit) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.putArray("hits")
                .addObject()
                .put("entryId", hit.getId().toString())
                .put("excerpt", "secret");
        return ConversationLedgerFixtures.toolResult(
                ConversationLedgerFixtures.piId(), "search-1", "search_conversation", response.toString(), false);
    }

    private ConversationTurnService.Submission submit(String text) {
        return turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    private static ConversationEntryDraft hephaestus(String text) {
        return ConversationLedgerFixtures.hephaestus(text);
    }

    private HeldTransaction holdIn(Runnable work) throws InterruptedException {
        return HeldTransaction.holdIn(transactionTemplate, jdbc, work);
    }
}
