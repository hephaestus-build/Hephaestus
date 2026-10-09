package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Billing an attempt to the usage ledger when it ends and when its lease expires. */
class MentorTurnCompletionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String WORKER = "worker-billing";
    /** $10 per million input tokens; nothing else is priced. */
    private static final MentorLlmConfig ADMITTED = LlmCatalogTestFixtures.admittedMentorConfig();

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Workspace workspace;
    private MentorConversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-billing-developer");
        workspace = createWorkspace("heph-billing", "Heph", "heph-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
    }

    /** A claimed attempt admitted at {@link #ADMITTED}'s rates. */
    private MentorTurn admittedTurn(Duration lease) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, "Question", UUID.randomUUID().toString(), null, null));
        MentorTurn turn = turns.claimNext(WORKER, lease).orElseThrow();
        turns.admit(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                MentorTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))
                        .toJson());
        return turn;
    }

    private void proxyCall(MentorTurn turn, long input) {
        transactionTemplate.executeWithoutResult(tx -> turnRepository.accumulateUsage(
                turn.getId(), turn.getAttempt(), new MentorTurnUsage(input, 10, 0, 0, 0, 1)));
    }

    /** One row of the usage ledger, as billed. */
    private record Billed(int attempt, long inputTokens, int calls, String provenance, BigDecimal costUsd) {}

    private List<Billed> ledger(MentorTurn turn) {
        return jdbc.query(
                "SELECT source_attempt, input_tokens, total_calls, usage_provenance, cost_usd FROM llm_usage_event "
                        + "WHERE source_id = ? ORDER BY source_attempt",
                (rs, row) -> new Billed(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        Objects.requireNonNull(rs.getString(4)),
                        Objects.requireNonNull(rs.getBigDecimal(5))),
                turn.getId());
    }

    @Test
    void shouldBillTheProxysRecordAndRecordItOnTheTurnWhenTheAttemptEnds() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        proxyCall(turn, 50_000);
        proxyCall(turn, 50_000);

        completion.end(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                new MentorTurnCompletion.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        assertThat(ledger(turn)).singleElement().satisfies(row -> {
            assertThat(row.attempt()).isEqualTo(1);
            assertThat(row.provenance()).isEqualTo("PROXY");
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).isEqualByComparingTo("1.00");
        });
        MentorTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getInputTokens()).isEqualTo(100_000);
        assertThat(row.getCostUsd()).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldBillNothingWhenTheAttemptMadeNoCall() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                new MentorTurnCompletion.Ending(MentorTurn.Status.FAILED, List.of(), "broken", null));

        assertThat(ledger(turn)).isEmpty();
    }

    @Test
    void shouldBillEachLostAttemptFromTheProxyWhenTheReaperTakesItsLeaseBack() {
        // Built directly: the bean's scheduler lock would skip a second sweep within its minimum hold.
        MentorTurnLeaseReaper reaper = new MentorTurnLeaseReaper(turns, completion, metrics, transactions);
        MentorTurn first = admittedTurn(Duration.ofSeconds(-1));
        proxyCall(first, 30_000);

        reaper.reap();

        MentorTurn requeued = turnRepository.findById(first.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(requeued.getInputTokens()).isZero();
        MentorTurn second = turns.claimNext(WORKER, Duration.ofSeconds(-1)).orElseThrow();
        turns.admit(
                second.getId(),
                second.getAttempt(),
                WORKER,
                MentorTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))
                        .toJson());
        proxyCall(second, 70_000);

        reaper.reap();

        assertThat(turnRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ledger(first))
                .extracting(Billed::attempt, Billed::inputTokens)
                .containsExactly(tuple(1, 30_000L), tuple(2, 70_000L));
    }
}
