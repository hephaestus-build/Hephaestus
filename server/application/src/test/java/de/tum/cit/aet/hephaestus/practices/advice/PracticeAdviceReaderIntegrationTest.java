package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceAudience;
import de.tum.cit.aet.hephaestus.practices.spi.AdvicePrior;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRetireReason;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRevisionState;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Refused;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Written;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.PriorState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.RetireAdvice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviseAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** PRACTICE-ADVICE table B for the mentor and the conversation basis, and the write races of decision 6. */
class PracticeAdviceReaderIntegrationTest extends BaseIntegrationTest {
    private static final String TEXT = "Name the rollback in the description, so a reviewer can check it.";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AccountDirectory accounts;

    @Autowired
    private AccountMembershipQuery memberships;

    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private ReviewSnapshotQuery reviews;

    @Autowired
    private FeedbackBasisRepository basis;

    @Autowired
    private AdviceRedaction redaction;

    @Autowired
    private PracticeAdviceRepository entries;

    @Autowired
    private PracticeAdviceRevisionRepository revisions;

    @Autowired
    private PracticeAdviceSourceRepository sources;

    @Autowired
    private AdviceRows rows;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    private final FeedbackModeQuery modes = mock(FeedbackModeQuery.class);
    private Scenario scenario;
    private long ws;
    private long owner;
    private long practice;
    private PracticeAdviceWriter writer;
    private PracticeAdviceReader reader;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario(DefinitionTestFixtures.definition());
        ws = scenario.workspace().getId();
        owner = Objects.requireNonNull(scenario.account().getId());
        practice = Objects.requireNonNull(scenario.practice().getId());
        identify(scenario.account());
        feedback(FeedbackMode.AUTOMATIC);
        var gate = mock(FeedbackGate.class);
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any()))
                .thenReturn(new FeedbackCheck(
                        FeedbackCheckStage.COMPOSITION,
                        true,
                        null,
                        Instant.now(),
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
        writer = new PracticeAdviceWriter(
                new PracticeAdviceProperties(Set.of(ws), false),
                List.of((workspace, review) -> Optional.of(FeedbackProducer.MENTOR)),
                List.of((workspace, revisions, cause) -> {}),
                accounts,
                memberships,
                fence,
                reviews,
                gate,
                basis,
                rows);
        reader = new PracticeAdviceReader(memberships, modes, basis, entries, revisions, sources);
    }

    @Test
    void shouldGiveTheMentorTheVisibleEntryWithItsCitationsButNoText() {
        Result cited = observed(scenario, hoursAgo(2));
        Result seen = observed(scenario, hoursAgo(2));
        var written = revise(cited, null, List.of(seen));

        var prior = prior().orElseThrow();

        assertThat(prior)
                .isEqualTo(new AdvicePrior(
                        written.adviceId(),
                        1,
                        1,
                        latestRevision(),
                        PriorState.VISIBLE,
                        null,
                        List.of(),
                        List.of(new EvidenceRef(cited.getId(), null))));
        assertThat(state(latestRevision())).isEqualTo(AdviceRevisionState.VISIBLE);
    }

    @Test
    void shouldFindNothingForTheTeamAnotherPersonOrAFormerMember() {
        revise(observed(scenario, hoursAgo(2)), null, List.of());
        UUID revision = latestRevision();
        long someoneElse = Objects.requireNonNull(data.member(ws).getId());

        assertThat(reader.prior(ws, owner, practice, AdviceAudience.TEAM)).isEmpty();
        assertThat(reader.state(ws, owner, revision, AdviceAudience.TEAM)).isEqualTo(AdviceRevisionState.MISSING);
        assertThat(reader.prior(ws, someoneElse, practice, AdviceAudience.OWNER))
                .isEmpty();
        assertThat(reader.state(ws, someoneElse, revision, AdviceAudience.OWNER))
                .isEqualTo(AdviceRevisionState.MISSING);
        assertThat(state(UUID.randomUUID())).isEqualTo(AdviceRevisionState.MISSING);

        jdbc.sql("UPDATE membership SET left_at=now() WHERE workspace_id=:ws AND account_id=:account")
                .param("ws", ws)
                .param("account", owner)
                .update();
        assertThat(prior()).isEmpty();
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.MISSING);
    }

    @Test
    void shouldHideARevisionThatLostTextOrASource() {
        Result cited = observed(scenario, hoursAgo(2));
        Result seen = observed(scenario, hoursAgo(2));
        revise(cited, null, List.of(seen));
        UUID revision = latestRevision();

        // As if a delete path that does not redact removed an observation: its source row goes, the text stays.
        jdbc.sql("DELETE FROM practice_advice_source WHERE result_id=:result")
                .param("result", seen.getId())
                .update();
        assertThat(prior().orElseThrow().state()).isEqualTo(PriorState.HIDDEN);
        assertThat(prior().orElseThrow().citations()).isEmpty();
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.STALE);

        transactions.executeWithoutResult(
                status -> redaction.beforeResultsDeleted(ws, List.of(cited.getId()), AdviceStaleCause.ERASURE));
        assertThat(prior().orElseThrow().state()).isEqualTo(PriorState.HIDDEN);
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.STALE);
    }

    @Test
    void shouldHideWhileFeedbackIsOffOrASourceIsMarkedIncorrect() {
        Result cited = observed(scenario, hoursAgo(2));
        Result seen = observed(scenario, hoursAgo(2));
        revise(cited, null, List.of(seen));
        UUID revision = latestRevision();

        feedback(FeedbackMode.OFF);
        assertThat(prior().orElseThrow().state()).isEqualTo(PriorState.HIDDEN);
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.HIDDEN);

        feedback(FeedbackMode.APPROVE_FIRST);
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.VISIBLE);
        data.mark(seen, ResultMarkKind.INCORRECT, owner);
        assertThat(prior().orElseThrow().state()).isEqualTo(PriorState.HIDDEN);
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.HIDDEN);
    }

    @Test
    void shouldReportARetiredOrDismissedEntryWithoutCitations() {
        Result first = observed(scenario, hoursAgo(3));
        var written = revise(first, null, List.of());
        UUID revision = latestRevision();
        jdbc.sql("UPDATE practice_advice SET dismissed_at=:at, dismissed_items=ARRAY[:item]::bigint[] WHERE id=:id")
                .param("at", Timestamp.from(hoursAgo(2)))
                .param("item", first.getItemId())
                .param("id", written.adviceId())
                .update();

        assertThat(prior().orElseThrow())
                .extracting(AdvicePrior::state, AdvicePrior::dismissedItems, AdvicePrior::citations)
                .containsExactly(PriorState.DISMISSED, List.of(first.getItemId()), List.of());
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.HIDDEN);

        Result later = observed(scenario, hoursAgo(1));
        assertThat(inTransaction(() -> writer.retire(
                        new RetireAdvice(ws, owner, practice, later.getReviewId(), 1, AdviceRetireReason.RESOLVED))))
                .isEqualTo(new Written(written.adviceId(), 2));
        assertThat(prior().orElseThrow())
                .extracting(AdvicePrior::state, AdvicePrior::reason, AdvicePrior::version, AdvicePrior::citations)
                .containsExactly(PriorState.RETIRED, AdviceRetireReason.RESOLVED, 2L, List.of());
        assertThat(state(revision)).isEqualTo(AdviceRevisionState.RETIRED);
    }

    @Test
    void shouldReportAnOlderRevisionAsSupersededUnlessItIsStale() {
        Result first = observed(scenario, hoursAgo(3));
        revise(first, null, List.of());
        UUID older = latestRevision();
        Result later = observed(scenario, hoursAgo(1));
        revise(later, 1L, List.of());
        UUID newer = latestRevision();

        assertThat(List.of(state(older), state(newer)))
                .containsExactly(AdviceRevisionState.SUPERSEDED, AdviceRevisionState.VISIBLE);
        transactions.executeWithoutResult(
                status -> redaction.beforeResultsDeleted(ws, List.of(first.getId()), AdviceStaleCause.ERASURE));
        assertThat(List.of(state(older), state(newer)))
                .containsExactly(AdviceRevisionState.STALE, AdviceRevisionState.VISIBLE);
    }

    @Test
    void shouldLetOnlyOneOfTwoConcurrentFirstWritesCreateTheEntry() throws Exception {
        Result one = observed(scenario, hoursAgo(2));
        Result two = observed(scenario, hoursAgo(2));
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.execute(status -> {
                var write = revise(one, null);
                written.countDown();
                hold(release);
                return write;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transactions.execute(status -> {
                secondPid.set(pid());
                return revise(two, null);
            }));
            await().atMost(Duration.ofSeconds(10)).until(() -> waitingForARow(secondPid.get()));
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(Written.class);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new Refused(AdviceRefusal.VERSION_MOVED, null));
        }
        assertThat(count("practice_advice")).isOne();
        assertThat(count("practice_advice_revision")).isOne();
    }

    @Test
    void shouldFindTheObservationGoneWhenAWriteWaitsForItsDeletion() throws Exception {
        Result doomed = observed(scenario, hoursAgo(2));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                redaction.beforeResultsDeleted(ws, List.of(doomed.getId()), AdviceStaleCause.ERASURE);
                locked.countDown();
                hold(release);
                jdbc.sql("DELETE FROM review_item WHERE result_id=:id")
                        .param("id", doomed.getId())
                        .update();
                jdbc.sql("DELETE FROM result WHERE id=:id")
                        .param("id", doomed.getId())
                        .update();
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var write = executor.submit(() -> transactions.execute(status -> {
                writerPid.set(pid());
                return revise(doomed, null);
            }));
            await().atMost(Duration.ofSeconds(10)).until(() -> waitingForARow(writerPid.get()));
            release.countDown();

            deletion.get(10, TimeUnit.SECONDS);
            assertThat(write.get(10, TimeUnit.SECONDS)).isEqualTo(new Refused(AdviceRefusal.EVIDENCE_INELIGIBLE, null));
        }
        assertThat(count("practice_advice")).isZero();
    }

    private Written revise(Result cited, @Nullable Long seen, List<Result> others) {
        return (Written) inTransaction(() -> writer.revise(new ReviseAdvice(
                ws,
                owner,
                practice,
                cited.getReviewId(),
                seen,
                TEXT,
                List.of(new EvidenceRef(cited.getId(), null)),
                others.stream()
                        .map(result -> new EvidenceRef(result.getId(), null))
                        .toList())));
    }

    /** In the caller's transaction. */
    private AdviceWrite revise(Result cited, @Nullable Long seen) {
        return writer.revise(new ReviseAdvice(
                ws,
                owner,
                practice,
                cited.getReviewId(),
                seen,
                TEXT,
                List.of(new EvidenceRef(cited.getId(), null)),
                List.of()));
    }

    private Optional<AdvicePrior> prior() {
        return reader.prior(ws, owner, practice, AdviceAudience.OWNER);
    }

    private AdviceRevisionState state(UUID revision) {
        return reader.state(ws, owner, revision, AdviceAudience.OWNER);
    }

    private UUID latestRevision() {
        return jdbc.sql("SELECT id FROM practice_advice_revision ORDER BY number DESC LIMIT 1")
                .query(UUID.class)
                .single();
    }

    private void feedback(FeedbackMode mode) {
        when(modes.effectiveModes(anyLong(), anyCollection())).thenReturn(Map.of(practice, mode));
    }

    private AdviceWrite inTransaction(Supplier<AdviceWrite> write) {
        return Objects.requireNonNull(transactions.execute(status -> write.get()));
    }

    /** The write fence admits only an account with a provider identity. */
    private void identify(Account account) {
        var provider = providers.saveAndFlush(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://advice-" + UUID.randomUUID() + ".example.test"));
        var link = new Identity();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setProviderUserKey("advice-owner");
        identities.saveAndFlush(link);
    }

    /** A current observation of the developer on new work: one item has one current observation per practice. */
    private Result observed(Scenario on, Instant at) {
        return data.result(data.onOtherWork(on), ResultOutcome.NOT_MET, at);
    }

    private int pid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private boolean waitingForARow(int pid) {
        return pid != 0
                && jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=:pid AND wait_event_type='Lock')")
                        .param("pid", pid)
                        .query(Boolean.class)
                        .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private static void hold(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
