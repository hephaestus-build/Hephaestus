package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRetireReason;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Refused;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceWrite.Written;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackProducer;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.RetireAdvice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviseAdvice;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PRACTICE-ADVICE table A rows 4 to 12, on the migrated schema. Rows 1 to 3: {@link PracticeAdviceWriterAdmissionTest}. */
class PracticeAdviceWriterIntegrationTest extends BaseIntegrationTest {
    /** Not ASCII, so a stored copy that changed its encoding fails the byte comparison. */
    private static final String TEXT =
            "Name the migration’s rollback in the description — a reviewer can then check it.";

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
    private ProblemRepository problems;

    @Autowired
    private AdviceRows rows;

    @Autowired
    private PracticeAdviceRepository entries;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    private final FeedbackGate gate = mock(FeedbackGate.class);
    private Scenario scenario;
    private long ws;
    private long owner;
    private long practice;
    private PracticeAdviceWriter writer;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario(DefinitionTestFixtures.definition());
        ws = scenario.workspace().getId();
        owner = Objects.requireNonNull(scenario.account().getId());
        practice = Objects.requireNonNull(scenario.practice().getId());
        identify(scenario.account());
        gateAnswers(null);
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
    }

    @Test
    void shouldWriteTheFirstRevisionWithItsSourcesAndReceipt() {
        Result cited = observed(scenario, hoursAgo(2));
        Result seen = observed(scenario, hoursAgo(2));
        UUID problem = problemOf(cited);

        var write = revise(cited.getReviewId(), null, TEXT, List.of(ref(cited, problem)), List.of(ref(seen, null)));

        assertThat(write).isInstanceOf(Written.class);
        var adviceId = ((Written) write).adviceId();
        assertThat(((Written) write).version()).isOne();
        assertThat(jdbc.sql(
                                "SELECT state || ':' || version || ':' || latest_revision FROM practice_advice WHERE id=:id")
                        .param("id", adviceId)
                        .query(String.class)
                        .single())
                .isEqualTo("CURRENT:1:1");
        assertThat(jdbc.sql("SELECT text FROM practice_advice_revision WHERE advice_id=:id")
                        .param("id", adviceId)
                        .query((rs, row) -> rs.getBytes(1))
                        .single())
                .isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.sql("SELECT s.result_id || ':' || COALESCE(s.problem_id::text, '-') || ':' || s.cited || ':'"
                                + " || s.ordinal || ':' || r.cited_count || '/' || r.source_count"
                                + " FROM practice_advice_source s JOIN practice_advice_revision r ON r.id=s.revision_id"
                                + " WHERE r.advice_id=:id ORDER BY s.ordinal")
                        .param("id", adviceId)
                        .query(String.class)
                        .list())
                .containsExactly(cited.getId() + ":" + problem + ":true:0:1/2", seen.getId() + ":-:false:1:1/2");
        assertThat(receipts(adviceId)).containsExactly(Map.entry(cited.getReviewId(), "REVISE:1"));
    }

    @Test
    void shouldReturnTheStoredResultForARetryAndRefuseAnotherDecisionOfTheSameReview() {
        Result cited = observed(scenario, hoursAgo(2));
        var first = revise(cited.getReviewId(), null, TEXT, List.of(ref(cited, null)), List.of());

        assertThat(revise(cited.getReviewId(), null, TEXT, List.of(ref(cited, null)), List.of()))
                .isEqualTo(first);
        assertThat(revise(
                        cited.getReviewId(),
                        null,
                        TEXT + " Then link the runbook.",
                        List.of(ref(cited, null)),
                        List.of()))
                .isEqualTo(refused(AdviceRefusal.REVIEW_ALREADY_WROTE));
        assertThat(retire(cited.getReviewId(), 1)).isEqualTo(refused(AdviceRefusal.REVIEW_ALREADY_WROTE));
        assertThat(count("practice_advice_revision")).isOne();
    }

    @Test
    void shouldRefuseAWriterThatSawAnotherVersion() {
        Result first = observed(scenario, hoursAgo(3));
        var written = (Written) revise(first.getReviewId(), null, TEXT, List.of(ref(first, null)), List.of());
        Result later = observed(scenario, hoursAgo(1));

        assertThat(revise(later.getReviewId(), null, TEXT, List.of(ref(later, null)), List.of()))
                .isEqualTo(refused(AdviceRefusal.VERSION_MOVED));
        assertThat(revise(later.getReviewId(), 0L, TEXT, List.of(ref(later, null)), List.of()))
                .isEqualTo(refused(AdviceRefusal.VERSION_MOVED));
        assertThat(revise(later.getReviewId(), 1L, TEXT, List.of(ref(later, null)), List.of()))
                .isEqualTo(new Written(written.adviceId(), 2));
        assertThat(jdbc.sql("SELECT max(number) FROM practice_advice_revision")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void shouldRefuseEvidenceThatIsNotTheOwnersLiveObservationOfThisPractice() {
        Result cited = observed(scenario, hoursAgo(2));
        Result other = observed(scenario, hoursAgo(2));
        var someoneElse = new Scenario(scenario.workspace(), data.member(ws), scenario.practice(), scenario.item());
        Result theirs = observed(someoneElse, hoursAgo(2));
        UUID review = cited.getReviewId();

        assertThat(List.of(
                        revise(review, null, TEXT, List.of(), List.of(ref(cited, null))),
                        revise(review, null, TEXT, List.of(ref(theirs, null)), List.of()),
                        revise(review, null, TEXT, List.of(ref(cited, null)), List.of(ref(theirs, null))),
                        revise(review, null, TEXT, List.of(ref(cited, problemOf(other))), List.of()),
                        revise(review, null, TEXT, List.of(ref(cited, null)), List.of(ref(cited, null))),
                        revise(review, null, TEXT, List.of(new EvidenceRef(UUID.randomUUID(), null)), List.of()),
                        inTransaction(() -> writer.revise(new ReviseAdvice(
                                ws, owner, practice + 1, review, null, TEXT, List.of(ref(cited, null)), List.of())))))
                .containsOnly(refused(AdviceRefusal.EVIDENCE_INELIGIBLE));
        assertThat(count("practice_advice")).isZero();
    }

    @Test
    void shouldRefuseWhatTheGateRefusesAndAnIncorrectMarkOnAnySource() {
        Result cited = observed(scenario, hoursAgo(2));
        Result seen = observed(scenario, hoursAgo(2));

        gateAnswers(FeedbackStopReason.FEEDBACK_OFF);
        assertThat(revise(cited.getReviewId(), null, TEXT, List.of(ref(cited, null)), List.of(ref(seen, null))))
                .isEqualTo(new Refused(AdviceRefusal.GATE_REFUSED, FeedbackStopReason.FEEDBACK_OFF));

        gateAnswers(null);
        data.mark(seen, ResultMarkKind.INCORRECT, owner);
        assertThat(revise(cited.getReviewId(), null, TEXT, List.of(ref(cited, null)), List.of(ref(seen, null))))
                .isEqualTo(new Refused(AdviceRefusal.GATE_REFUSED, FeedbackStopReason.RESULT_MARKED_INCORRECT));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Result>> support = ArgumentCaptor.forClass(List.class);
        verify(gate, times(2))
                .evaluateComposition(any(), eq(owner), support.capture(), eq(FeedbackDestination.PRACTICE_PAGE));
        assertThat(support.getValue()).extracting(Result::getId).containsExactly(cited.getId());
        assertThat(count("practice_advice")).isZero();
    }

    @Test
    void shouldWriteADismissedEntryAgainOnlyForANewItemObservedAfterTheDismissal() {
        var dismissedWork = data.onOtherWork(scenario);
        Result first = data.result(dismissedWork, ResultOutcome.NOT_MET, hoursAgo(3));
        var written = (Written) revise(first.getReviewId(), null, TEXT, List.of(ref(first, null)), List.of());
        jdbc.sql("UPDATE practice_advice SET dismissed_at=:at, dismissed_items=ARRAY[:item]::bigint[] WHERE id=:id")
                .param("at", Timestamp.from(hoursAgo(2)))
                .param("item", dismissedWork.item().getId())
                .param("id", written.adviceId())
                .update();
        data.end(first, hoursAgo(1));

        Result sameItem = data.result(dismissedWork, ResultOutcome.NOT_MET, hoursAgo(1));
        assertThat(revise(sameItem.getReviewId(), 1L, TEXT, List.of(ref(sameItem, null)), List.of()))
                .isEqualTo(refused(AdviceRefusal.OWNER_DISMISSED));
        Result newItem = observed(scenario, hoursAgo(1));
        assertThat(revise(newItem.getReviewId(), 1L, TEXT, List.of(ref(newItem, null)), List.of()))
                .isEqualTo(new Written(written.adviceId(), 2));
        assertThat(jdbc.sql("SELECT dismissed_at IS NULL AND dismissed_items IS NULL FROM practice_advice")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void shouldRefuseTextThatClaimsAPattern() {
        Result cited = observed(scenario, hoursAgo(2));

        assertThat(revise(
                        cited.getReviewId(),
                        null,
                        "You skipped the rollback again.",
                        List.of(ref(cited, null)),
                        List.of()))
                .isEqualTo(refused(AdviceRefusal.TEXT_INVALID));
        assertThat(count("practice_advice")).isZero();
    }

    @Test
    void shouldRetireOnceForAReviewAndKeepTheDismissal() {
        Result first = observed(scenario, hoursAgo(3));
        assertThat(retire(first.getReviewId(), 0)).isEqualTo(refused(AdviceRefusal.VERSION_MOVED));
        var written = (Written) revise(first.getReviewId(), null, TEXT, List.of(ref(first, null)), List.of());
        jdbc.sql("UPDATE practice_advice SET dismissed_at=:at, dismissed_items=ARRAY[1]::bigint[] WHERE id=:id")
                .param("at", Timestamp.from(hoursAgo(2)))
                .param("id", written.adviceId())
                .update();
        Result later = observed(scenario, hoursAgo(1));

        var retired = retire(later.getReviewId(), 1);

        assertThat(retired).isEqualTo(new Written(written.adviceId(), 2));
        assertThat(retire(later.getReviewId(), 1)).isEqualTo(retired);
        assertThat(retire(observed(scenario, hoursAgo(1)).getReviewId(), 1))
                .isEqualTo(refused(AdviceRefusal.VERSION_MOVED));
        assertThat(jdbc.sql("SELECT state || ':' || retire_reason || ':' || (dismissed_at IS NOT NULL)"
                                + " FROM practice_advice")
                        .query(String.class)
                        .single())
                .isEqualTo("RETIRED:RESOLVED:true");
        assertThat(receipts(written.adviceId()))
                .containsOnly(Map.entry(first.getReviewId(), "REVISE:1"), Map.entry(later.getReviewId(), "RETIRE:2"));
    }

    @Test
    void shouldRefuseAStaleRetireInTheSameTransaction() {
        Result first = observed(scenario, hoursAgo(3));
        revise(first.getReviewId(), null, TEXT, List.of(ref(first, null)), List.of());
        UUID review = observed(scenario, hoursAgo(2)).getReviewId();
        UUID otherReview = observed(scenario, hoursAgo(1)).getReviewId();

        transactions.executeWithoutResult(status -> {
            assertThat(retire(review, 1)).isInstanceOf(Written.class);
            assertThat(retire(otherReview, 1)).isEqualTo(refused(AdviceRefusal.VERSION_MOVED));
        });
        assertThat(jdbc.sql("SELECT version FROM practice_advice")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void shouldSeeADismissalThatCommittedAfterThisTransactionReadTheEntry() {
        var dismissedWork = data.onOtherWork(scenario);
        Result first = data.result(dismissedWork, ResultOutcome.NOT_MET, hoursAgo(3));
        var written = (Written) revise(first.getReviewId(), null, TEXT, List.of(ref(first, null)), List.of());
        data.end(first, hoursAgo(1));
        Result again =
                data.result(dismissedWork, ResultOutcome.NOT_MET, Instant.now().minusSeconds(60));
        var separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        var write = transactions.execute(status -> {
            // As the mentor's prior read does: the entry is now in this transaction, without a dismissal.
            assertThat(entries.findByWorkspaceIdAndPersonAccountIdAndPracticeId(ws, owner, practice))
                    .hasValueSatisfying(
                            entry -> assertThat(entry.getDismissedAt()).isNull());
            separate.executeWithoutResult(inner -> jdbc.sql(
                            "UPDATE practice_advice SET dismissed_at=:at, dismissed_items=ARRAY[:item]::bigint[]"
                                    + " WHERE id=:id")
                    .param("at", Timestamp.from(hoursAgo(2)))
                    .param("item", dismissedWork.item().getId())
                    .param("id", written.adviceId())
                    .update());
            return writer.revise(new ReviseAdvice(
                    ws, owner, practice, again.getReviewId(), 1L, TEXT, List.of(ref(again, null)), List.of()));
        });

        assertThat(write).isEqualTo(refused(AdviceRefusal.OWNER_DISMISSED));
        assertThat(jdbc.sql("SELECT dismissed_at IS NOT NULL FROM practice_advice")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    private AdviceWrite revise(
            UUID review, @Nullable Long seen, String text, List<EvidenceRef> citations, List<EvidenceRef> others) {
        return inTransaction(
                () -> writer.revise(new ReviseAdvice(ws, owner, practice, review, seen, text, citations, others)));
    }

    private AdviceWrite retire(UUID review, long seen) {
        return inTransaction(
                () -> writer.retire(new RetireAdvice(ws, owner, practice, review, seen, AdviceRetireReason.RESOLVED)));
    }

    private AdviceWrite inTransaction(Supplier<AdviceWrite> write) {
        return Objects.requireNonNull(transactions.execute(status -> write.get()));
    }

    private void gateAnswers(@Nullable FeedbackStopReason refusal) {
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any()))
                .thenReturn(new FeedbackCheck(
                        FeedbackCheckStage.COMPOSITION,
                        refusal == null,
                        refusal,
                        Instant.now(),
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
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

    private UUID problemOf(Result result) {
        return problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(ws, List.of(result.getId()))
                .getFirst()
                .getId();
    }

    private static EvidenceRef ref(Result result, @Nullable UUID problem) {
        return new EvidenceRef(result.getId(), problem);
    }

    private static Refused refused(AdviceRefusal reason) {
        return new Refused(reason, null);
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private List<Map.Entry<UUID, String>> receipts(UUID adviceId) {
        return jdbc.sql("SELECT review_id, kind || ':' || version AS decision FROM practice_advice_receipt"
                        + " WHERE advice_id=:id ORDER BY version")
                .param("id", adviceId)
                .query((rs, row) -> Map.entry(rs.getObject("review_id", UUID.class), rs.getString("decision")))
                .list();
    }
}
