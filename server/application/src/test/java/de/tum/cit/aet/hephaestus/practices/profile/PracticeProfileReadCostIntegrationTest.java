package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmSourceFingerprints;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.ResultFeedQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryService;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingResultDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reads behind the Practice profile page cost a fixed number of statements, however many results and how
 * much Practice page feedback the developer's work has. Every row passes custody and source checks, so the whole
 * authorization path runs with explicit provider fingerprints for the synthetic work.
 */
class PracticeProfileReadCostIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    SqlReadMeasurement reads;

    @Autowired
    PracticeProfileOverviewService overview;

    @Autowired
    PracticeStandingService standings;

    @Autowired
    PracticeGroupStandingService groupStandings;

    @Autowired
    PracticeGroupRepository groups;

    @Autowired
    PracticePageFeedbackService practicePageFeedback;

    @Autowired
    SqlStatementCounter statements;

    @Autowired
    PracticeProfileReviewService reviewList;

    @Autowired
    ResultQueryService resultList;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    IdentityRepository identities;

    @Autowired
    ItemRepository items;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ScmItemSourceAccess source;

    Scenario scenario;

    List<Scenario> works;

    Map<Long, Result> newestOnWork = new HashMap<>();

    @Nullable
    Result newestProblem;

    int seeded;

    /** Whether seeded results keep no custody of their own and the receipt of their producing job vouches for them. */
    boolean vouchedByJob;

    @MockitoBean
    ScmSourceFingerprints sourceFingerprints;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        when(sourceFingerprints.supports(any())).thenReturn(true);
        when(sourceFingerprints.fingerprints(any(), any(), any(), any()))
                .thenReturn(Map.of(new SourceKind("scm.pull-request.diff"), "a".repeat(64)));
        scenario = data.scenario();
        works = List.of(scenario, onNewWork(), onNewWork());
        signInIdentity();
        inShownGroup();
        when(source.kinds()).thenReturn(Set.of(new ItemKind("scm.pull_request")));
        when(source.permits(any(), any())).thenReturn(true);
        when(source.permits(any(), any(), any())).thenReturn(true);
        var developer = Jwt.withTokenValue("profile-read-cost")
                .header("alg", "none")
                .subject(String.valueOf(scenario.account().getId()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(developer));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepEachProfileReadAtAFixedStatementCountWhenMoreResultsArriveOnTheSameWork() {
        seed(2 * works.size());
        var few = costs(1);
        seed(4 * works.size());
        var many = costs(3);

        // The practice page records the egress check of every unread piece it reads, one statement each. What the
        // pieces share (here one practice, work item and recipient) is read once for all of them.
        long fewPage = Objects.requireNonNull(few.remove("practice page feedback"));
        assertThat(many.remove("practice page feedback")).isEqualTo(fewPage + 2);
        assertThat(many).isEqualTo(few);
    }

    /**
     * Reading whether a result's sources are current reads the work behind it from the mirror. A standing quotes at
     * most five problems and three strengths of a practice, so it reads the work behind those, not behind every result
     * of its look-back. Each piece of work here holds one result, so a read of every result would ask about all 24.
     */
    @Test
    void shouldReadTheWorkBehindOnlyTheResultsAStandingCouldQuote() {
        works = IntStream.range(0, 24).mapToObj(index -> onNewWork()).toList();
        seed(works.size());

        var asked = workReads();
        // A standing quotes at most eight results of the one practice.
        assertThat(asked.get("standings")).isPositive().isLessThanOrEqualTo(8);
        assertThat(asked.get("group standings")).isPositive().isLessThanOrEqualTo(8);
        // The overview reads two such snapshots. It also asks whether later work resolved each piece of feedback,
        // which needs current proof of every later result of its practice, once for each edge of the window.
        assertThat(asked.get("overview")).isPositive().isLessThanOrEqualTo(2L * works.size() + 2 * 8);
    }

    /**
     * The review list narrows and pages the developer's whole history, but shows whether a result's sources are current
     * only for the reviews on the page. Each piece of work here holds one result of its own review, so a read of every
     * result would ask about all 24.
     */
    @Test
    void shouldReadTheWorkBehindOnlyTheReviewsOnTheListPage() {
        works = IntStream.range(0, 24).mapToObj(index -> onNewWork()).toList();
        seed(works.size());

        assertThat(workReads().get("reviews")).isPositive().isLessThanOrEqualTo(5);
    }

    /**
     * A result usually keeps no custody of its own: the receipt of the job that produced it vouches for it, and every
     * review has its own job. Standings and the overview check the receipt of every producing job in their look-back,
     * in a fixed number of statements however many jobs that is.
     */
    @Test
    void shouldCheckEveryProducingJobsReceiptAtAFixedStatementCount() {
        vouchedByJob = true;
        seed(2 * works.size());
        var few = receiptCosts();
        seed(4 * works.size());
        var many = receiptCosts();

        assertThat(many).isEqualTo(few);
    }

    /**
     * The batch answers each producing job on its own. A job whose receipt refuses custody keeps its result out of the
     * standings and the overview's history, while the other job of the same read still vouches for its own.
     */
    @Test
    void shouldLeaveOutTheResultOfAJobWhoseReceiptRefusesCustody() {
        vouchedByJob = true;
        works = List.of(onNewWork(), onNewWork());
        seed(2);
        var vouched =
                Objects.requireNonNull(newestOnWork.get(works.get(0).item().getId()));
        var refused =
                Objects.requireNonNull(newestOnWork.get(works.get(1).item().getId()));
        // A receipt still capturing refuses custody, as an interrupted capture leaves it.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(
                    "UPDATE person_evidence_copy SET state='CAPTURING' WHERE workspace_id=? AND job_id="
                            + "(SELECT job_id FROM review_item WHERE workspace_id=? AND result_id=?)",
                    refused.getWorkspaceId(),
                    refused.getWorkspaceId(),
                    refused.getId());
        });

        var history = standings.history(
                scenario.workspace().getId(),
                Objects.requireNonNull(scenario.account().getId()),
                Instant.now().minus(1, ChronoUnit.DAYS));
        assertThat(history.permittedViews())
                .extracting(view -> view.result().id())
                .contains(vouched.getId())
                .doesNotContain(refused.getId());
        assertThat(standings.getStandings(scenario.workspace().getId()).stream()
                        .flatMap(standing -> Stream.concat(standing.toWorkOn().stream(), standing.strengths().stream()))
                        .map(PracticeStandingResultDTO::resultId))
                .doesNotContain(refused.getId());
    }

    /**
     * The Practice page asks whether later work closed each card. Only work that the change itself occasioned can
     * close one, never a review the developer asked for, so the page reads the work behind none of those. Every result
     * here comes from a requested review on its own piece of work, so a read of each later result would ask about
     * nearly all 24.
     */
    @Test
    void shouldReadTheWorkBehindNoRequestedReviewWhenThePracticePageLooksForWorkThatClosesACard() {
        works = IntStream.range(0, 24).mapToObj(index -> onNewWork()).toList();
        seed(works.size());

        clearInvocations(sourceFingerprints);
        var cards = practicePageFeedback.getOwnPracticePageFeedback(
                scenario.workspace().getId());

        assertThat(cards).isNotEmpty();
        assertThat(fingerprintReads()).isLessThanOrEqualTo(cards.size());
    }

    @Test
    void shouldReadTheDevelopersResultHistoryOnceForTheOverview() {
        seed(2 * works.size());
        var context = WorkspaceContext.fromWorkspace(scenario.workspace(), Set.of(), null);
        var measured = reads.measure(() -> overview.getOverview(context));

        assertThat(measured.value().latestReview()).isNotNull();
        // The page, its changes and both standing snapshots filter one history; the newest result is its own read.
        // The history is the one result read bounded by the look-back; the first-opportunity read spans all results.
        assertThat(measured.sql())
                .filteredOn(sql -> sql.contains(" FROM result r ") && sql.contains("r.recorded_at>=?"))
                .hasSize(1);
    }

    @Test
    void shouldShowPracticePageFeedbackAsItStandsWhenTheReadingTransactionLoadedItBefore() {
        seed(works.size());
        var piece = practicePageFeedback(Objects.requireNonNull(newestProblem), true);
        var cards = new TransactionTemplate(transactions).execute(status -> {
            assertThat(feedback.findById(piece)).isPresent();
            jdbc.update("UPDATE feedback SET body='Name each change after its intent.' WHERE id=?", piece);
            return practicePageFeedback.getOwnPracticePageFeedback(
                    scenario.workspace().getId());
        });
        assertThat(cards)
                .filteredOn(card -> card.id().equals(piece))
                .singleElement()
                .satisfies(card -> assertThat(card.body()).isEqualTo("Name each change after its intent."));
    }

    /** An older card must not fall off the page because a developer has many others. */
    @Test
    void shouldListEveryPracticePageCardOfADeveloperWithManyOfThem() {
        seed(works.size());
        var problem = Objects.requireNonNull(newestProblem);
        var pieces = IntStream.range(0, 25)
                .mapToObj(index -> practicePageFeedback(problem, true))
                .toList();

        var cards = new TransactionTemplate(transactions)
                .execute(status -> practicePageFeedback.getOwnPracticePageFeedback(
                        scenario.workspace().getId()));

        assertThat(cards).extracting(card -> card.id()).containsAll(pieces);
    }

    /** Each read's statements, after checking that the read saw the seeded work. */
    private Map<String, Long> costs(int unreadPieces) {
        var context = WorkspaceContext.fromWorkspace(scenario.workspace(), Set.of(), null);
        var shown = groups.findByWorkspaceIdAndShownToPeopleTrueOrderByPositionAscNameAsc(
                scenario.workspace().getId());
        Map<String, Long> costs = new LinkedHashMap<>();
        costs.put(
                "overview",
                cost(
                        () -> overview.getOverview(context),
                        read -> assertThat(read.latestReview()).isNotNull()));
        costs.put(
                "standings",
                cost(
                        () -> standings.getStandings(scenario.workspace().getId()),
                        read -> assertThat(read)
                                .singleElement()
                                .satisfies(standing -> assertThat(
                                                standing.toWorkOn().size()
                                                        + standing.strengths().size())
                                        .isPositive())));
        costs.put(
                "group standings",
                cost(
                        () -> groupStandings.getGroupStandings(
                                scenario.workspace().getId(), shown),
                        read -> assertThat(read)
                                .singleElement()
                                .satisfies(group -> assertThat(group.standing())
                                        .isNotIn(PracticeStanding.NOT_OBSERVED, PracticeStanding.NO_OPPORTUNITY))));
        costs.put(
                "reviews",
                cost(
                        () -> reviewList.list(
                                context,
                                new OwnReviewFilter(null, null, null, null, null, false),
                                PageRequest.of(0, 10)),
                        read -> assertThat(read.getContent()).isNotEmpty()));
        costs.put(
                "results",
                cost(
                        () -> resultList.listOwnResults(
                                scenario.workspace().getId(),
                                new ResultFeedQuery(null, null, null, null, null, null, false, ResultSort.NEWEST),
                                PageRequest.of(0, 10)),
                        read -> assertThat(read.getContent()).isNotEmpty()));
        // Pieces no one has read yet go through the egress gate on every read.
        List<UUID> unread = new ArrayList<>();
        for (int piece = 0; piece < unreadPieces; piece++)
            unread.add(practicePageFeedback(Objects.requireNonNull(newestProblem), false));
        costs.put(
                "unread practice page feedback",
                cost(
                        () -> practicePageFeedback.unreadCreatedAt(
                                scenario.workspace().getId(),
                                Objects.requireNonNull(scenario.account().getId())),
                        // The gate decides the unread pieces and, as the page read below records, refuses them here.
                        read -> assertThat(read).isEmpty()));
        costs.put(
                "practice page feedback",
                writingCost(
                        () -> practicePageFeedback.getOwnPracticePageFeedback(
                                scenario.workspace().getId()),
                        read -> assertThat(read).isNotEmpty()));
        for (var piece : unread) {
            assertThat(jdbc.queryForObject(
                            "SELECT checks->'EGRESS' IS NOT NULL FROM feedback WHERE id=?", Boolean.class, piece))
                    .isTrue();
            // Read now, it counts among the delivered history of the next measurement.
            jdbc.update("UPDATE feedback SET state='DELIVERED',delivered_at=created_at WHERE id=?", piece);
        }
        return costs;
    }

    /** How many times each standing read asks the mirror for the current fingerprints of a piece of work. */
    private Map<String, Long> workReads() {
        var context = WorkspaceContext.fromWorkspace(scenario.workspace(), Set.of(), null);
        var shown = groups.findByWorkspaceIdAndShownToPeopleTrueOrderByPositionAscNameAsc(
                scenario.workspace().getId());
        Map<String, Runnable> reads = new LinkedHashMap<>();
        reads.put("overview", () -> overview.getOverview(context));
        reads.put("standings", () -> standings.getStandings(scenario.workspace().getId()));
        reads.put(
                "group standings",
                () -> groupStandings.getGroupStandings(scenario.workspace().getId(), shown));
        reads.put(
                "reviews",
                () -> reviewList.list(
                        context, new OwnReviewFilter(null, null, null, null, null, false), PageRequest.of(0, 5)));
        Map<String, Long> counts = new LinkedHashMap<>();
        reads.forEach((name, read) -> {
            clearInvocations(sourceFingerprints);
            read.run();
            counts.put(name, fingerprintReads());
        });
        return counts;
    }

    /** The statements of each read that checks the custody of its whole look-back. */
    private Map<String, Long> receiptCosts() {
        var context = WorkspaceContext.fromWorkspace(scenario.workspace(), Set.of(), null);
        var shown = groups.findByWorkspaceIdAndShownToPeopleTrueOrderByPositionAscNameAsc(
                scenario.workspace().getId());
        Map<String, Long> costs = new LinkedHashMap<>();
        costs.put(
                "overview",
                cost(
                        () -> overview.getOverview(context),
                        read -> assertThat(read.latestReview()).isNotNull()));
        costs.put(
                "standings",
                cost(
                        () -> standings.getStandings(scenario.workspace().getId()),
                        read -> assertThat(read).hasSize(1)));
        costs.put(
                "group standings",
                cost(
                        () -> groupStandings.getGroupStandings(
                                scenario.workspace().getId(), shown),
                        read -> assertThat(read).hasSize(1)));
        return costs;
    }

    private long fingerprintReads() {
        return mockingDetails(sourceFingerprints).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("fingerprints"))
                .count();
    }

    private <T> long cost(Supplier<T> read, Consumer<T> check) {
        var measured = reads.measure(read);
        check.accept(measured.value());
        return measured.cost().statements();
    }

    /** The Practice page read records first reads and locks the feedback it shows, so it runs in a writing transaction. */
    private <T> long writingCost(Supplier<T> read, Consumer<T> check) {
        var measured = statements.measure(() -> new TransactionTemplate(transactions).execute(status -> read.get()));
        var value = Objects.requireNonNull(measured.value());
        check.accept(value);
        return measured.statements();
    }

    /**
     * {@code count} more results, recorded a minute apart across the works with complete custody. Each replaces the
     * claim the work's previous result held, as a new review of the work does.
     */
    private void seed(int count) {
        var start = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int index = 0; index < count; index++) {
            var at = start.plusSeconds(60L * seeded);
            var work = works.get(seeded % works.size());
            var previous = newestOnWork.get(work.item().getId());
            if (previous != null) data.end(previous, at);
            var result = data.result(work, seeded % 3 == 0 ? ResultOutcome.NOT_MET : ResultOutcome.MET, at);
            newestOnWork.put(work.item().getId(), result);
            if (result.getOutcome() == ResultOutcome.NOT_MET) {
                practicePageFeedback(result, true);
                newestProblem = result;
            }
            seeded++;
            // Only capture writes custody receipts; the guard that refuses other writers stays on.
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL session_replication_role=replica");
                if (vouchedByJob) producedByJob(result);
                else
                    jdbc.update(
                            "UPDATE review_item SET detail=detail||CAST(? AS jsonb) WHERE workspace_id=? AND result_id=?",
                            completeCustody(),
                            result.getWorkspaceId(),
                            result.getId());
            });
        }
    }

    private UUID practicePageFeedback(Result bound, boolean delivered) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES(?,?,?,?,?,'PRACTICE_PAGE',?,'Keep each change focused.',?,?)",
                id,
                bound.getWorkspaceId(),
                bound.getReviewId(),
                bound.getItemId(),
                bound.getDeveloperId(),
                delivered ? "DELIVERED" : "READY",
                Timestamp.from(bound.getRecordedAt()),
                delivered ? Timestamp.from(bound.getRecordedAt()) : null);
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,person_account_id,ordinal)"
                        + " VALUES(?,?,?,?,?,0)",
                FeedbackIds.basis(id, bound.getId(), null),
                bound.getWorkspaceId(),
                id,
                bound.getId(),
                bound.getDeveloperId());
        return id;
    }

    private void inShownGroup() {
        var group = jdbc.queryForObject(
                "INSERT INTO practice_group(workspace_id,slug,name) VALUES (?,'review-quality','Review quality') RETURNING id",
                Long.class,
                scenario.workspace().getId());
        jdbc.update(
                "UPDATE practice SET group_id=? WHERE id=?",
                group,
                scenario.practice().getId());
    }

    /** The Practice page shows feedback only to an account a provider identity signs in as. */
    private void signInIdentity() {
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("profile-read-cost-" + scenario.account().getId());
        identities.saveAndFlush(identity);
    }

    private Scenario onNewWork() {
        var item = new Item();
        item.setWorkspaceId(scenario.workspace().getId());
        item.setKind(scenario.item().getKind());
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        return new Scenario(scenario.workspace(), scenario.account(), scenario.practice(), items.saveAndFlush(item));
    }

    /** Binds the result to a completed job whose READY receipt indexes every copy of the job's capture. */
    private void producedByJob(Result result) {
        var job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review_job(id,review_id,workspace_id,status,run_id,processing_status,trace_id,input,"
                        + "config_snapshot,submitted_at,completed_at)"
                        + " VALUES(?,?,?,'COMPLETED',?,'NONE',?,'{}'::jsonb,'{}'::jsonb,now(),now())",
                job,
                result.getReviewId(),
                result.getWorkspaceId(),
                RuntimeJobTestData.insertRun(jdbc, result.getWorkspaceId(), result.getReviewId(), "COMPLETED", 1),
                job.toString().replace("-", ""));
        jdbc.update(
                "UPDATE review_item SET job_id=? WHERE workspace_id=? AND result_id=?",
                job,
                result.getWorkspaceId(),
                result.getId());
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload) VALUES(?,?,?,'READY',"
                        + "CAST(? AS jsonb))",
                UUID.randomUUID(),
                result.getWorkspaceId(),
                job,
                "{\"dependencies\":\"COMPLETE\",\"identities\":[],\"repositories\":[],\"copiedJobs\":[]}");
    }

    private String completeCustody() {
        var detail = json.createObjectNode();
        detail.set("custody", json.valueToTree(new HistoryCustody(true, false, List.of(), List.of(), List.of())));
        return detail.toString();
    }
}
