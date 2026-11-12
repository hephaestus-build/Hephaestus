package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.ProductFeedbackModelGateway;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A report waits for its draft as one {@code draft_report} action. The test context runs without the worker role, so the
 * server alone drafts. The outbox, the filing and the instance model are real; an attempt's result stands in for the
 * proxy's answer ({@code DraftReportAdapterTest} maps each answer to its result).
 */
class ProductFeedbackDraftIntegrationTest extends BaseIntegrationTest {
    private static final String PRIVATE_REPOSITORY = "acme/feedback-private";

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private FilingService filing;

    @Autowired
    private ProductFeedbackDrafts drafts;

    @Autowired
    private ProductFeedbackDraftCalls draftCalls;

    @Autowired
    private ProductFeedbackDestinationRepository destinationRows;

    @Autowired
    private ProductFeedbackFilingRepository filings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private List<ActionAdapter> adapters;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private LlmModel model;
    private @Nullable String previousPublic;
    private @Nullable String previousPrivate;
    private @Nullable Long previousModel;
    private boolean previousPaused;
    private int previousDailyLimit;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        var connection = connections.save(LlmCatalogTestFixtures.connection("draft-" + System.nanoTime()));
        model = models.save(LlmCatalogTestFixtures.model(connection, "draft-" + System.nanoTime(), "draft-upstream"));
        ProductFeedbackDestination destination = destination();
        previousPublic = destination.getPublicRepository();
        previousPrivate = destination.getPrivateRepository();
        previousModel = destination.getLlmModelId();
        previousPaused = destination.isPaused();
        previousDailyLimit = destination.getDailyDraftLimit();
        destination.setPublicRepository(null);
        destination.setPrivateRepository(PRIVATE_REPOSITORY);
        destination.setLlmModelId(model.getId());
        destination.setPaused(false);
        destinationRows.save(destination);
    }

    @AfterEach
    void tearDown() {
        ProductFeedbackDestination destination = destination();
        destination.setPublicRepository(previousPublic);
        destination.setPrivateRepository(previousPrivate);
        destination.setLlmModelId(previousModel);
        destination.setPaused(previousPaused);
        destination.setDailyDraftLimit(previousDailyLimit);
        destinationRows.save(destination);
    }

    @Test
    void shouldQueueOneDraftActionInTheReportsLaneWhenAReportIsSent() {
        UUID id = report();

        ProductFeedbackFiling queued = filings.findById(id).orElseThrow();
        assertThat(queued.getState()).isEqualTo(FilingState.DRAFT_QUEUED);
        assertThat(queued.getAttempt()).isEqualTo(1);
        Map<String, @Nullable Object> action = action(id, 1);
        assertThat(action.get("kind")).isEqualTo("DRAFT_REPORT");
        assertThat(action.get("lane_key")).isEqualTo(FileIssueAdapter.lane(id));
        assertThat(action.get("workspace_id")).isNull();
        assertThat(action.get("max_attempts")).isEqualTo(DraftingService.DRAFT_ATTEMPTS);
        assertThat(action.get("state")).isEqualTo("ADMITTED");
        Instant expires = ((Timestamp) Objects.requireNonNull(action.get("expires_at"))).toInstant();
        assertThat(expires)
                .isBetween(
                        Instant.now().plus(DraftingService.EXPIRY).minus(Duration.ofMinutes(1)),
                        Instant.now().plus(DraftingService.EXPIRY));
    }

    @Test
    void shouldKeepTheAnswerAndRouteOnWhenTheDraftCallIsServed() {
        UUID id = report();
        String answer = "{\"disposition\":\"ISSUE\"}";
        var attempt = claim(id);

        assertThat(drafts.answered(attempt, id, 1, answer)).isTrue();
        ActionTestSupport.record(context, attempt, new ActionResult.Done(null));

        ProductFeedbackFiling answered = filings.findById(id).orElseThrow();
        assertThat(answered.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(answered.getAnswer()).isEqualTo(answer);
        assertThat(answered.getDraftedAt()).isNotNull();
        assertThat(action(id, 1).get("state")).isEqualTo("DONE");
    }

    @Test
    void shouldWaitForCapacityWithoutSpendingAttemptsAndResumeOnClaim() {
        UUID id = report();
        ActionAttempt first = claim(id);
        assertThat(drafts.claimed(first, id, 1)).isTrue();
        assertThat(drafts.deferred(first, id, 1)).isTrue();
        ActionTestSupport.record(context, first, new ActionResult.Defer(Duration.ofSeconds(30)));
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_WAITING);
        assertThat(action(id, 1).get("attempts")).isEqualTo(0);
        ActionAttempt next = claim(id);
        assertThat(drafts.claimed(next, id, 1)).isTrue();
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFTING);
        assertThat(drafts.deferred(first, id, 1)).isFalse();
        ActionTestSupport.record(context, next, new ActionResult.Done(null));
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
    }

    @Test
    void shouldReserveDailyQuotaAtCallAdmissionAndRejectAnAlreadyQueuedReport() {
        var destination = destination();
        destination.setDailyDraftLimit(1);
        destinationRows.saveAndFlush(destination);
        UUID first = report();
        UUID second = report();
        Boolean admitted = transactions.execute(tx -> draftCalls.admitCall(first, 1, model.getId()));
        Boolean refused = transactions.execute(tx -> draftCalls.admitCall(second, 1, model.getId()));
        assertThat(admitted).isTrue();
        assertThat(refused).isFalse();
        Boolean retry = transactions.execute(tx -> draftCalls.admitCall(first, 1, model.getId()));
        assertThat(retry).isTrue();
        destination = destination();
        destination.setDailyDraftLimit(0);
        destinationRows.saveAndFlush(destination);
        Boolean disabled = transactions.execute(tx -> draftCalls.admitCall(first, 1, model.getId()));
        assertThat(disabled).isFalse();
    }

    @Test
    void shouldRetryAFailingCallThenRouteWithoutADraft() {
        UUID id = report();

        for (int attempt = 1; attempt < DraftingService.DRAFT_ATTEMPTS; attempt++) {
            ActionTestSupport.record(context, claim(id), new ActionResult.Retry(Duration.ofSeconds(30)));
            assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.DRAFT_QUEUED);
            assertThat(action(id, 1).get("state")).isEqualTo("ADMITTED");
        }
        ActionTestSupport.record(context, claim(id), new ActionResult.Retry(Duration.ofSeconds(30)));

        ProductFeedbackFiling routed = filings.findById(id).orElseThrow();
        assertThat(routed.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(routed.getAnswer()).isNull();
        assertThat(routed.getDraftedAt()).isNull();
        Map<String, @Nullable Object> action = action(id, 1);
        assertThat(action.get("state")).isEqualTo("FAILED");
        assertThat(action.get("stop_reason")).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void shouldRouteWithoutADraftWhenTheProxyRefusesTheModel() {
        UUID id = report();

        ActionTestSupport.record(context, claim(id), new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED));

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(filings.findById(id).orElseThrow().getAnswer()).isNull();
        assertThat(action(id, 1).get("stop_reason")).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void shouldRouteWithoutADraftWhenTheAgentRuntimeIsOff() {
        UUID id = report();
        assertThat(context.getBeansOfType(ProductFeedbackModelGateway.class)).isEmpty();
        assertThat(runDraft(id)).containsInstanceOf(ActionResult.Failed.class);
        assertThat(action(id, 1).get("stop_reason")).isEqualTo("NOT_CONFIGURED");
        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(filings.findById(id).orElseThrow().getAnswer()).isNull();
    }

    @Test
    void shouldRouteWithoutADraftWhenTheModelIsGone() {
        UUID id = report();
        model.setEnabled(false);
        models.save(model);

        assertThat(runDraft(id)).containsInstanceOf(ActionResult.Failed.class);

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(action(id, 1).get("stop_reason")).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void shouldRouteWithoutADraftWhenTheDraftExpires() {
        UUID id = report();

        ActionTestSupport.endStale(context, expire(id));

        assertThat(filings.findById(id).orElseThrow().getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(action(id, 1).get("stop_reason")).isEqualTo("EXPIRED");
    }

    @Test
    void shouldKeepNoAnswerFromAnAttemptThatLostItsAction() {
        UUID id = report();
        var attempt = claim(id);
        ActionTestSupport.expireLease(jdbc, FileIssueAdapter.lane(id));
        assertThat(ActionTestSupport.claimLane(jdbc, mapper, FileIssueAdapter.lane(id)))
                .isPresent();

        assertThat(drafts.answered(attempt, id, 1, "{\"disposition\":\"ISSUE\"}"))
                .isFalse();

        assertThat(filings.findById(id).orElseThrow().getAnswer()).isNull();
    }

    @Test
    void shouldNeverHandAReportToAModelWhenItsAuthorWasNotTold() {
        UUID id = report();
        ActionTestSupport.endStale(context, expire(id));
        jdbc.update(
                "UPDATE product_feedback_filing SET state = 'STOPPED', draft = NULL, first_attempt_at = NULL,"
                        + " file_disclosed = false WHERE feedback_id = ?",
                id);

        assertThat(filing.retry(id)).isTrue();

        ProductFeedbackFiling retried = filings.findById(id).orElseThrow();
        assertThat(retried.getState()).isEqualTo(FilingState.ANSWERED);
        assertThat(retried.getAttempt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE lane_key = ? AND kind = 'DRAFT_REPORT'",
                        Long.class,
                        FileIssueAdapter.lane(id)))
                .isEqualTo(1);
    }

    @Test
    void shouldCountTheTokensOfADraftCallOnTheReport() {
        UUID id = report();

        draftCalls.count(id, 120, 30);

        ProductFeedbackFiling counted = filings.findById(id).orElseThrow();
        assertThat(counted.getInputTokens()).isEqualTo(120);
        assertThat(counted.getOutputTokens()).isEqualTo(30);
    }

    /** Takes the report's draft action as the executor would, without carrying it out. */
    private ActionAttempt claim(UUID id) {
        ActionTestSupport.due(jdbc, FileIssueAdapter.lane(id));
        return ActionTestSupport.claimLane(jdbc, mapper, FileIssueAdapter.lane(id))
                .orElseThrow();
    }

    /** Runs the report's draft action now, with the kind's own adapter, as the executor would. */
    private Optional<ActionResult> runDraft(UUID id) {
        ActionTestSupport.due(jdbc, FileIssueAdapter.lane(id));
        ActionAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.kind() == ActionKind.DRAFT_REPORT)
                .findFirst()
                .orElseThrow();
        return ActionTestSupport.runLane(executor, jdbc, FileIssueAdapter.lane(id), adapter);
    }

    /** Makes the report's first draft action expire before it ran; returns its id. */
    private UUID expire(UUID id) {
        jdbc.update(
                "UPDATE agent_action SET expires_at = now() - interval '1 second' WHERE idempotency_key = ?",
                "draft:" + id + ":1");
        return UUID.fromString(String.valueOf(action(id, 1).get("id")));
    }

    private Map<String, @Nullable Object> action(UUID id, int generation) {
        return jdbc.queryForMap(
                "SELECT id, kind, lane_key, workspace_id, max_attempts, attempts, state, stop_reason, expires_at"
                        + " FROM agent_action WHERE idempotency_key = ?",
                "draft:" + id + ":" + generation);
    }

    private ProductFeedbackDestination destination() {
        return destinationRows
                .findById(ProductFeedbackDestination.SINGLETON)
                .orElseGet(ProductFeedbackDestination::new);
    }

    private UUID report() {
        Account account = new Account("Draft reporter");
        account.setPrimaryEmail(UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        Account reporter = accounts.save(account);
        return feedbackService
                .add(
                        new FeedbackRequestDTO(ProductFeedback.Kind.BUG, "The list jumps", null, null, false),
                        Objects.requireNonNull(reporter.getId()),
                        null,
                        false)
                .id();
    }
}
