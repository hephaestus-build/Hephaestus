package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.Duration;
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
class MentorLedgerConcurrencyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    private static final int PATIENCE_SECONDS = 20;
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorRedaction redaction;

    @Autowired
    private MentorContextPathBuilder paths;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private MentorConversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-concurrency-developer");
        workspace = createWorkspace("heph-concurrency", "Hephaestus", "heph-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
    }

    @Test
    void shouldNumberEntriesConsecutivelyWhenTwoWritersAppendToOneConversationAtOnce() throws Exception {
        AtomicReference<MentorEntry> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(entries.append(workspace.getId(), main.getId(), heph("One"))));
        CompletableFuture<MentorEntry> second =
                CompletableFuture.supplyAsync(() -> entries.append(workspace.getId(), main.getId(), heph("Two")));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getSeq())
                .isEqualTo(first.get().getSeq() + 1);
    }

    @Test
    void shouldInsertOnceWhenTwoRunsAppendTheSameServerWriteAtOnce() throws Exception {
        AtomicReference<MentorEntryService.Appended> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() ->
                first.set(entries.appendOnce(workspace.getId(), main.getId(), MentorLedgerFixtures.digest("wd-race"))));
        CompletableFuture<MentorEntryService.Appended> second = CompletableFuture.supplyAsync(
                () -> entries.appendOnce(workspace.getId(), main.getId(), MentorLedgerFixtures.digest("wd-race")));

        holder.commitAfter(second);

        MentorEntryService.Appended again = second.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(first.get().inserted()).isTrue();
        assertThat(again.inserted()).isFalse();
        assertThat(again.entry().getId()).isEqualTo(first.get().entry().getId());
    }

    @Test
    void shouldQueueOneTurnWhenTheSameSubmissionArrivesTwiceAtOnce() throws Exception {
        MentorTurnService.Question question = new MentorTurnService.Question(
                MentorTurn.Surface.APP, "Is this ready?", UUID.randomUUID().toString(), null, null);
        AtomicReference<MentorTurnService.Submission> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(turns.submit(workspace.getId(), main.getId(), question)));
        CompletableFuture<MentorTurnService.Submission> retry =
                CompletableFuture.supplyAsync(() -> turns.submit(workspace.getId(), main.getId(), question));

        holder.commitAfter(retry);

        MentorTurnService.Submission again = retry.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(first.get().created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.turn().getId()).isEqualTo(first.get().turn().getId());
        assertThat(again.question().getId()).isEqualTo(first.get().question().getId());
    }

    @Test
    void shouldOpenOneThreadWhenTwoRepliesOpenItAtOnce() throws Exception {
        MentorEntry parent = entries.append(workspace.getId(), main.getId(), heph("Parent"));
        AtomicReference<MentorConversation> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(conversations.openThread(workspace.getId(), parent.getId())));
        CompletableFuture<MentorConversation> second =
                CompletableFuture.supplyAsync(() -> conversations.openThread(workspace.getId(), parent.getId()));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getId())
                .isEqualTo(first.get().getId());
    }

    @Test
    void shouldRefuseTheThreadWhenItsParentIsErasedWhileTheThreadOpens() throws Exception {
        MentorEntry parent = entries.append(workspace.getId(), main.getId(), heph("Parent"));
        HeldTransaction erasure = holdIn(() -> redaction.redact(List.of(parent.getId())));
        CompletableFuture<MentorConversation> thread =
                CompletableFuture.supplyAsync(() -> conversations.openThread(workspace.getId(), parent.getId()));

        erasure.commitAfter(thread);

        assertThatThrownBy(() -> thread.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(MentorRejectedException.Reason.NOT_A_THREAD_PARENT));
        assertThat(conversations.findThread(workspace.getId(), parent.getId())).isEmpty();
    }

    @Test
    void shouldCreateOneMainConversationWhenAnAccountsFirstTwoWritesArriveAtOnce() throws Exception {
        long newcomer = accountId(persistUser("heph-concurrency-newcomer"));
        AtomicReference<MentorConversation> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(conversations.findOrCreateMain(workspace.getId(), newcomer)));
        CompletableFuture<MentorConversation> second =
                CompletableFuture.supplyAsync(() -> conversations.findOrCreateMain(workspace.getId(), newcomer));

        holder.commitAfter(second);

        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS).getId())
                .isEqualTo(first.get().getId());
    }

    @Test
    void shouldStartOneTurnOfAConversationWhenTwoWorkersClaimAtOnce() throws Exception {
        MentorTurn head = submit("First question").turn();
        MentorTurn next = submit("Second question").turn();
        AtomicReference<Optional<MentorTurn>> first = new AtomicReference<>();
        HeldTransaction holder = holdIn(() -> first.set(turns.claimNext(Duration.ofMinutes(5))));
        CompletableFuture<Optional<MentorTurn>> second =
                CompletableFuture.supplyAsync(() -> turns.claimNext(Duration.ofMinutes(5)));

        holder.commitAfter(second);

        assertThat(first.get()).map(MentorTurn::getId).contains(head.getId());
        assertThat(second.get(PATIENCE_SECONDS, TimeUnit.SECONDS)).isEmpty();
        MentorTurn claimed = turnRepository.findById(head.getId()).orElseThrow();
        assertThat(claimed.getLeaseId()).isNotNull();
        assertThat(claimed.getAttempt()).isEqualTo(1);
        assertThat(turnRepository.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.QUEUED);
    }

    @Test
    void shouldKeepTheErasureWhenAVoteRacesIt() throws Exception {
        MentorEntry answer = entries.append(workspace.getId(), main.getId(), heph("A secret answer"));
        HeldTransaction holder = holdIn(() -> redaction.redact(List.of(answer.getId())));
        CompletableFuture<MentorEntry> vote = CompletableFuture.supplyAsync(
                () -> entries.vote(workspace.getId(), answer.getId(), MentorEntry.Vote.HELPFUL));

        holder.commitAfter(vote);

        assertThatThrownBy(() -> vote.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected ->
                                assertThat(rejected.reason()).isEqualTo(MentorRejectedException.Reason.NOT_VOTABLE));
        MentorEntry stored = entryRepository.findById(answer.getId()).orElseThrow();
        assertThat(stored.isRedacted()).isTrue();
        assertThat(stored.getText()).isEmpty();
        assertThat(stored.getVote()).isNull();
    }

    @Test
    void shouldKeepTheDeletionWhenASlackEditRacesIt() throws Exception {
        MentorTurnService.Submission asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.SLACK, "My token is abc", "slack:T:D:9.0", "9.0", null));
        HeldTransaction holder =
                holdIn(() -> redaction.redact(List.of(asked.question().getId())));
        CompletableFuture<MentorEntry> edit = CompletableFuture.supplyAsync(
                () -> entries.editSlackMessage(workspace.getId(), main.getId(), "9.0", "My token is abcd"));

        holder.commitAfter(edit);

        assertThatThrownBy(() -> edit.get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected ->
                                assertThat(rejected.reason()).isEqualTo(MentorRejectedException.Reason.NOT_EDITABLE));
        MentorEntry stored = entryRepository.findById(asked.question().getId()).orElseThrow();
        assertThat(stored.isRedacted()).isTrue();
        assertThat(stored.getText()).isEmpty();
        assertThat(stored.getEditedAt()).isNull();
    }

    @Test
    void shouldReadThePathWithoutAMessageWhenItsErasureCommitsWhileThePathIsRead() throws Exception {
        MentorEntry secret = entries.append(workspace.getId(), main.getId(), heph("Secret plan"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), heph("Kept"));
        MentorTurnService.Submission asked = submit("What now?");
        HeldTransaction erasure = holdIn(() -> redaction.redact(List.of(secret.getId())));
        MentorTurn turn = claimNext(asked);
        CompletableFuture<MentorContextPathBuilder.TurnContext> read =
                CompletableFuture.supplyAsync(() -> paths.forTurn(workspace.getId(), turn.getId(), ZoneOffset.UTC));

        erasure.commitAfter(read);

        assertThat(read.get(PATIENCE_SECONDS, TimeUnit.SECONDS).entries().toString())
                .doesNotContain("Secret plan")
                .contains("Kept");
        // The erasure ran before the path was read, so a summary of that path holds nothing erased.
        MentorEntry summary = ended(turn, compaction(kept));
        assertThat(summary.isRedacted()).isFalse();
    }

    @Test
    void shouldEraseTheSummaryWhenTheErasureWaitedForThePathRead() throws Exception {
        MentorEntry secret = entries.append(workspace.getId(), main.getId(), heph("Secret plan"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), heph("Kept"));
        MentorTurn turn = claimNext(submit("What now?"));
        AtomicReference<MentorContextPathBuilder.TurnContext> path = new AtomicReference<>();
        HeldTransaction read = holdIn(() -> path.set(paths.forTurn(workspace.getId(), turn.getId(), ZoneOffset.UTC)));
        CompletableFuture<Void> erasure = CompletableFuture.runAsync(() -> redaction.redact(List.of(secret.getId())));

        read.commitAfter(erasure);
        erasure.get(PATIENCE_SECONDS, TimeUnit.SECONDS);

        assertThat(path.get().entries().toString()).contains("Secret plan");
        MentorEntry summary = ended(turn, compaction(kept));
        assertThat(summary.isRedacted()).isTrue();
        assertThat(summary.getText()).isEmpty();
    }

    @Test
    void shouldEraseWhatAnEndingTurnStoresWhenTheDeveloperDeletesTheQuotedMessageMeanwhile() throws Exception {
        MentorTurnService.Submission secret = submit("My secret question");
        MentorTurn first = claimNext(secret);
        MentorEntry kept = ended(first, MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Kept answer"));
        MentorTurn turn = claimNext(submit("Next"));
        HeldTransaction end = holdIn(() -> turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE, List.of(compaction(kept), searchResult(secret.question())), null)));
        CompletableFuture<Void> deletion = CompletableFuture.runAsync(
                () -> entries.deleteMessage(workspace.getId(), secret.question().getId()));

        end.commitAfter(deletion);
        deletion.get(PATIENCE_SECONDS, TimeUnit.SECONDS);

        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()))
                .filteredOn(entry -> entry.getKind() != MentorEntry.Kind.USER)
                .hasSize(2)
                .allSatisfy(copy -> assertThat(copy.isRedacted()).isTrue());
        assertThat(entryRepository
                        .findById(secret.question().getId())
                        .orElseThrow()
                        .isRedacted())
                .isTrue();
    }

    private MentorTurn claimNext(MentorTurnService.Submission submission) {
        MentorTurn claimed = turns.claimNext(LEASE).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(submission.turn().getId());
        return claimed;
    }

    /** Ends the turn with one Pi entry its host appended, and returns that entry as stored. */
    private MentorEntry ended(MentorTurn turn, JsonNode piEntry) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(piEntry), null));
        return MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .getLast();
    }

    /** A compaction that summarizes the conversation before {@code firstKept}. */
    private static ObjectNode compaction(MentorEntry firstKept) {
        return MentorLedgerFixtures.compaction(
                MentorLedgerFixtures.piId(), "Talked about a secret", MentorLedgerFixtures.piIdOf(firstKept));
    }

    /** A {@code search_conversation} result that quotes {@code hit}. */
    private static ObjectNode searchResult(MentorEntry hit) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.putArray("hits")
                .addObject()
                .put("entryId", hit.getId().toString())
                .put("excerpt", "secret");
        return MentorLedgerFixtures.toolResult(
                MentorLedgerFixtures.piId(), "search-1", "search_conversation", response.toString(), false);
    }

    private MentorTurnService.Submission submit(String text) {
        return turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    private static MentorEntryDraft heph(String text) {
        return MentorLedgerFixtures.heph(text);
    }

    private HeldTransaction holdIn(Runnable work) throws InterruptedException {
        return HeldTransaction.holdIn(transactionTemplate, jdbc, work);
    }
}
