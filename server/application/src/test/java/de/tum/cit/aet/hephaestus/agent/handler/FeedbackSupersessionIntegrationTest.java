package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the supersession swap against a real database rather than a stub. Whether two runs replacing one
 * queued card leave the developer with one live card or two, and whether a card they opened can be
 * rewritten under them, are properties of the SQL and of when each transaction commits — no verified
 * method call can answer either.
 *
 * <p>The rules under test, in two sentences: <b>a queued message may be claimed by exactly one run, and
 * replacing it must never cost the developer the message.</b> On the in-app lane <b>an open card is
 * replaced whether or not it was read, and only the caller, who read it the way the page does, decides
 * that it is open.</b>
 */
class FeedbackSupersessionIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicInteger SLUG_SEQUENCE = new AtomicInteger();
    private static final int RACERS = 2;
    private static final int PATIENCE_SECONDS = 30;

    private static final long RECIPIENT = 4242L;
    private static final String PRACTICE = "ships-tests-with-the-change";

    @Autowired
    private FeedbackSupersession supersession;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Workspace workspace;
    private String threadKey;

    @BeforeEach
    void setUp() {
        // A workspace of its own per test: every read below is workspace-scoped, so rows another test
        // left behind are invisible and no instance-wide clean is needed.
        workspace = workspaceRepository.save(
                WorkspaceTestFixtures.activeWorkspace("supersede-" + SLUG_SEQUENCE.incrementAndGet()));
        threadKey = FeedbackThreadKey.forPractice(PRACTICE, RECIPIENT, FeedbackChannel.IN_APP);
    }

    /**
     * Two reviews of two different pull requests finish at the same instant and both compose a card about
     * the same habit. The developer must end up with one current card about it, and the card that was
     * retired must have a successor — a retirement that outlived its replacement would take a message out
     * of somebody's queue and put nothing back.
     */
    @Test
    @DisplayName("two runs replace one queued card, and the developer is left with exactly one")
    void twoRacingRunsLeaveOneLiveCardAndNoOrphanedRetirement() throws Exception {
        UUID queued = queuedCard("The card that was waiting").getId();

        CountDownLatch atTheLine = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService runs = Executors.newFixedThreadPool(RACERS);
        List<FeedbackSupersession.Disposition> dispositions = new ArrayList<>();
        try {
            List<Future<FeedbackSupersession.Disposition>> replacements = new ArrayList<>();
            for (int racer = 0; racer < RACERS; racer++) {
                Callable<FeedbackSupersession.Disposition> replace = replacement(racer, atTheLine, go);
                replacements.add(runs.submit(replace));
            }
            assertThat(atTheLine.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<FeedbackSupersession.Disposition> replacement : replacements) {
                dispositions.add(replacement.get(PATIENCE_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            runs.shutdownNow();
        }

        List<Feedback> thread = onThread();
        assertThat(thread)
                .as("the card that was waiting, plus one replacement per run")
                .hasSize(RACERS + 1);
        assertThat(state(queued)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(thread)
                .filteredOn(card -> card.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .as("one habit, one live card — the pile is what supersession exists to prevent")
                .hasSize(1);
        assertThat(dispositions)
                .filteredOn(disposition -> disposition == FeedbackSupersession.Disposition.SUPERSEDED)
                .as("each run retires the card it found, and no card is retired twice")
                .hasSize(RACERS);
        assertThat(thread)
                .filteredOn(card -> card.getDeliveryState() == FeedbackDeliveryState.SUPERSEDED)
                .allSatisfy(retired -> assertThat(thread)
                        .filteredOn(card -> retired.getId().equals(card.getReplacesId()))
                        .as("every retirement has exactly one successor, and the chain never forks")
                        .hasSize(1));
    }

    /**
     * The precondition that cannot live in a prior read: the recipient's own page flips the card at the
     * moment they open it, in a transaction the composing run knows nothing about. A read and a
     * replacement racing must resolve one way or the other and never both, and whichever wins, the words
     * the run composed still reach the developer.
     */
    @Test
    @DisplayName("a read and a replacement race, and the card is either read or retired — never both")
    void aReadAndAReplacementNeverBothWin() throws Exception {
        UUID queued = queuedCard("The card that was waiting").getId();

        CountDownLatch atTheLine = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        boolean readWon;
        FeedbackSupersession.Disposition disposition;
        try {
            Future<Integer> read = contenders.submit(() -> {
                atTheLine.countDown();
                go.await(PATIENCE_SECONDS, TimeUnit.SECONDS);
                return transactionTemplate.execute(status ->
                        feedbackRepository.markInAppDelivered(workspace.getId(), List.of(queued), Instant.now()));
            });
            Future<FeedbackSupersession.Disposition> replace = contenders.submit(replacement(0, atTheLine, go));
            assertThat(atTheLine.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            readWon = Integer.valueOf(1).equals(read.get(PATIENCE_SECONDS, TimeUnit.SECONDS));
            disposition = replace.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        } finally {
            contenders.shutdownNow();
        }

        boolean supersessionWon = disposition == FeedbackSupersession.Disposition.SUPERSEDED;
        assertThat(readWon ^ supersessionWon)
                .as("exactly one of the two claimed the card: %s", readWon ? "the reader" : "the replacement")
                .isTrue();
        assertThat(state(queued))
                .isEqualTo(readWon ? FeedbackDeliveryState.DELIVERED : FeedbackDeliveryState.SUPERSEDED);
        if (readWon) {
            assertThat(disposition)
                    .as("a card that was read is followed, not rewritten")
                    .isEqualTo(FeedbackSupersession.Disposition.CONTINUED);
        }
        assertThat(onThread())
                .as("whoever won, the words the run composed still reached the developer")
                .hasSize(2);
    }

    /**
     * A mentor unit the developer has already read is the case the un-saying rule is named for. It keeps
     * its state, and the new unit is written beside it pointing back at it, so the thread reads as one
     * habit raised twice over time rather than as an edit to something they have in their head.
     */
    @Test
    @DisplayName("a mentor unit that has been read is followed rather than replaced")
    void aMentorUnitThatWasReadIsFollowedRatherThanReplaced() {
        String mentorThread = FeedbackThreadKey.forPractice(PRACTICE, RECIPIENT, FeedbackChannel.IN_CHAT);
        UUID read = feedbackRepository
                .save(cardBuilder("The unit they read", FeedbackDeliveryState.DELIVERED, null)
                        .channel(FeedbackChannel.IN_CHAT)
                        .threadKey(mentorThread)
                        .deliveredAt(Instant.now())
                        .build())
                .getId();

        FeedbackSupersession.Outcome outcome = transactionTemplate.execute(
                status -> supersession.supersede(workspace.getId(), RECIPIENT, FeedbackChannel.IN_CHAT, mentorThread));

        assertThat(outcome.disposition()).isEqualTo(FeedbackSupersession.Disposition.CONTINUED);
        assertThat(outcome.replacesId()).isEqualTo(read);
        assertThat(outcome.retiredSomething()).isFalse();
        assertThat(state(read)).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    /**
     * The in-app lane: the page is a list of habits to work on, so the card still open about a habit is
     * retired by the newer card about it, whether it was waiting to be read or already read. The ledger
     * keeps the retired row, and the new card points back at it.
     */
    @Test
    @DisplayName("an open in-app card is replaced whether or not it was read")
    void anOpenInAppCardIsReplacedReadOrNot() {
        UUID queued = queuedCard("The card that was waiting").getId();
        UUID read = queuedCard("The card they opened").getId();
        assertThat(feedbackRepository.markInAppDelivered(workspace.getId(), List.of(read), Instant.now()))
                .isEqualTo(1);

        FeedbackSupersession.Outcome queuedOutcome =
                transactionTemplate.execute(status -> supersession.replaceOpen(workspace.getId(), queued));
        FeedbackSupersession.Outcome readOutcome =
                transactionTemplate.execute(status -> supersession.replaceOpen(workspace.getId(), read));

        assertThat(queuedOutcome.disposition()).isEqualTo(FeedbackSupersession.Disposition.SUPERSEDED);
        assertThat(queuedOutcome.replacesId()).isEqualTo(queued);
        assertThat(readOutcome.disposition()).isEqualTo(FeedbackSupersession.Disposition.SUPERSEDED);
        assertThat(readOutcome.replacesId()).isEqualTo(read);
        assertThat(state(queued)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(state(read)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
    }

    /** A card another run already retired, or that was withheld, is not claimed twice and not followed. */
    @Test
    @DisplayName("an in-app card that is no longer live is neither claimed again nor followed")
    void anInAppCardNoLongerLiveIsNotClaimedAgain() {
        UUID retired = queuedCard("Already replaced").getId();
        assertThat(transactionTemplate
                        .execute(status -> supersession.replaceOpen(workspace.getId(), retired))
                        .retiredSomething())
                .isTrue();
        UUID withheld = card("Never sent", FeedbackDeliveryState.SUPPRESSED, FeedbackSuppressionReason.VOLUME_CAPPED)
                .getId();

        FeedbackSupersession.Outcome again =
                transactionTemplate.execute(status -> supersession.replaceOpen(workspace.getId(), retired));
        FeedbackSupersession.Outcome never =
                transactionTemplate.execute(status -> supersession.replaceOpen(workspace.getId(), withheld));

        assertThat(again.disposition()).isEqualTo(FeedbackSupersession.Disposition.NEW);
        assertThat(again.replacesId()).isNull();
        assertThat(never.disposition()).isEqualTo(FeedbackSupersession.Disposition.NEW);
        assertThat(state(withheld)).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
    }

    /**
     * The head of the thread is settled in a state nothing can claim. Pointing back at it would put two
     * rows on one link, so the new card follows nothing and the shared key is what still ties it to the
     * thread.
     */
    @Test
    @DisplayName("a thread whose head was withheld leaves the new card following nothing")
    void aWithheldHeadIsNotFollowed() {
        card("The card that was never sent", FeedbackDeliveryState.SUPPRESSED, FeedbackSuppressionReason.VOLUME_CAPPED);

        FeedbackSupersession.Outcome outcome = transactionTemplate.execute(
                status -> supersession.supersede(workspace.getId(), RECIPIENT, FeedbackChannel.IN_APP, threadKey));

        assertThat(outcome.disposition()).isEqualTo(FeedbackSupersession.Disposition.NEW);
        assertThat(outcome.replacesId()).isNull();
    }

    @Test
    @DisplayName("a first card on a thread claims nothing and does not fail")
    void aFirstCardOnAThreadClaimsNothing() {
        FeedbackSupersession.Outcome outcome = transactionTemplate.execute(
                status -> supersession.supersede(workspace.getId(), RECIPIENT, FeedbackChannel.IN_APP, threadKey));

        assertThat(outcome.disposition()).isEqualTo(FeedbackSupersession.Disposition.NEW);
        assertThat(outcome.replacesId()).isNull();
    }

    /**
     * One run's whole turn: claim the queued card, then queue its own before committing — the shape the
     * in-app lane has, and the reason a lost claim never leaves a retirement standing alone.
     */
    private Callable<FeedbackSupersession.Disposition> replacement(
            int racer, CountDownLatch atTheLine, CountDownLatch go) {
        return () -> {
            atTheLine.countDown();
            go.await(PATIENCE_SECONDS, TimeUnit.SECONDS);
            return transactionTemplate.execute(status -> {
                FeedbackSupersession.Outcome outcome =
                        supersession.supersede(workspace.getId(), RECIPIENT, FeedbackChannel.IN_APP, threadKey);
                feedbackRepository.save(cardBuilder("Replacement " + racer, FeedbackDeliveryState.PREPARED, null)
                        .replacesId(outcome.replacesId())
                        .build());
                return outcome.disposition();
            });
        };
    }

    private Feedback queuedCard(String body) {
        return card(body, FeedbackDeliveryState.PREPARED, null);
    }

    private Feedback card(String body, FeedbackDeliveryState state, @Nullable FeedbackSuppressionReason reason) {
        return feedbackRepository.save(cardBuilder(body, state, reason).build());
    }

    /** Each card takes a job of its own, since {@code (agent_job_id, position)} is one unit's identity. */
    private Feedback.FeedbackBuilder cardBuilder(
            String body, FeedbackDeliveryState state, @Nullable FeedbackSuppressionReason reason) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job = agentJobRepository.save(job);
        return Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .recipientUserId(RECIPIENT)
                .aboutUserId(RECIPIENT)
                .channel(FeedbackChannel.IN_APP)
                .position(FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE)
                .deliveryState(state)
                .suppressionReason(reason)
                .body(body)
                .source(FeedbackSource.AGENT)
                .threadKey(threadKey)
                .createdAt(Instant.now());
    }

    private List<Feedback> onThread() {
        return feedbackRepository.findAll().stream()
                .filter(card -> workspace.getId().equals(card.getWorkspaceId()))
                .toList();
    }

    private FeedbackDeliveryState state(UUID id) {
        return feedbackRepository
                .findByIdAndWorkspaceId(id, workspace.getId())
                .orElseThrow()
                .getDeliveryState();
    }
}
