package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountConversationExport;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountFeedbackExport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * An export holds the messages of its owner's conversations. Erasing one of them, which another person's erasure does
 * to an answer that used their data, expires a ready export and never lets an export in flight publish the erased
 * words, whichever transaction takes the export row first.
 */
class AccountExportMessageErasureIntegrationTest extends BaseIntegrationTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ERASED = "Alex reviewed the API.";
    private static final String KEPT = "Who reviewed the API?";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ExportGenerationWorker worker;

    @Autowired
    private ExportBundleAssembler assembler;

    @Autowired
    private AccountConversationExport conversations;

    @Autowired
    private AccountFeedbackExport feedback;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private ConversationEntryService entryService;

    @Autowired
    private AccountExportRepository repository;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private OwnerScope ownerScope;

    @Autowired
    private RowSweepAdmission admission;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private FeedbackCopyRepository copies;

    @Autowired
    private FeedbackRepository feedbackRows;

    @Autowired
    private FeedbackOutcomes outcomes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountExportWriter writer;

    private long seq;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldExpireOnlyTheReadyExportOfTheOwnerWhoseMessageIsErased() {
        var scenario = data.scenario();
        long owner = Objects.requireNonNull(scenario.account().getId());
        long workspace = scenario.workspace().getId();
        long unrelated = Objects.requireNonNull(data.member(workspace).getId());
        UUID conversation = conversation(workspace, owner);
        entry(conversation, "USER", KEPT);
        UUID answer = entry(conversation, "ANSWER", ERASED);
        entry(conversation(workspace, unrelated), "ANSWER", "The unrelated member's answer.");
        long ownerExport = readyExport(owner);
        long unrelatedExport = readyExport(unrelated);
        assertThat(payload(ownerExport, owner)).contains(ERASED);

        redaction.redact(List.of(answer));

        AccountExport expired = read(ownerExport, owner);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(read(unrelatedExport, unrelated).getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(payload(unrelatedExport, unrelated)).contains("The unrelated member's answer.");
    }

    @Test
    void shouldExpireWhatAWorkerPublishesWhileTheErasureWaitsForTheWorker() throws Exception {
        var scenario = data.scenario();
        long owner = Objects.requireNonNull(scenario.account().getId());
        UUID conversation = conversation(scenario.workspace().getId(), owner);
        entry(conversation, "USER", KEPT);
        UUID answer = entry(conversation, "ANSWER", ERASED);
        long exportId = seedExport(owner);
        var read = new CompletableFuture<Integer>();
        var publish = new CompletableFuture<Void>();
        AccountConversationExport pausing = accountId -> {
            var messages = conversations.messages(accountId);
            read.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            publish.join();
            return messages;
        };

        var generation = CompletableFuture.runAsync(() -> worker(pausing).generate(exportId, owner));
        CompletableFuture<Void> erasure;
        try {
            int workerPid = read.get(30, TimeUnit.SECONDS);
            erasure = CompletableFuture.runAsync(() -> redaction.redact(List.of(answer)));
            awaitBlockedBy(workerPid, erasure);
            assertThat(erasure).isNotDone();
        } finally {
            publish.complete(null);
        }
        generation.get(30, TimeUnit.SECONDS);
        erasure.get(30, TimeUnit.SECONDS);

        AccountExport export = read(exportId, owner);
        assertThat(export.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(export.getPayload()).isNull();
    }

    /** The export did not exist when the erasure changed the message: only the owner's fence orders the two. */
    @Test
    void shouldReadTheMessagesAfterAnOpenErasureForAnExportRequestedDuringIt() throws Exception {
        var scenario = data.scenario();
        long owner = Objects.requireNonNull(scenario.account().getId());
        UUID conversation = conversation(scenario.workspace().getId(), owner);
        entry(conversation, "USER", KEPT);
        UUID answer = entry(conversation, "ANSWER", ERASED);

        var erasure = HeldTransaction.holdIn(
                new TransactionTemplate(transactionManager), jdbc, () -> redaction.redact(List.of(answer)));
        long exportId = seedExport(owner);
        var generation = CompletableFuture.runAsync(() -> worker(conversations).generate(exportId, owner));
        erasure.commitAfter(generation, () -> assertThat(generation).isNotDone());
        generation.get(30, TimeUnit.SECONDS);

        assertThat(read(exportId, owner).getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(payload(exportId, owner)).contains(KEPT).doesNotContain(ERASED);
    }

    @Test
    void shouldExpireTheReadyExportsOfTheMembersWhoseMessagesAWorkspacePurgeDeletes() {
        var purged = data.scenario();
        long member = Objects.requireNonNull(purged.account().getId());
        entry(conversation(purged.workspace().getId(), member), "ANSWER", ERASED);
        var kept = data.scenario();
        long other = Objects.requireNonNull(kept.account().getId());
        entry(conversation(kept.workspace().getId(), other), "ANSWER", "The other workspace's answer.");
        long memberExport = readyExport(member);
        long otherExport = readyExport(other);

        long purgedId = purged.workspace().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            admission.admitWorkspacePurge(purgedId);
            jdbc.queryForObject(
                    "SELECT public.person_row_sweep('WORKSPACE_PURGE', ?, NULL, NULL)", Integer.class, purgedId);
        });

        AccountExport expired = read(memberExport, member);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(read(otherExport, other).getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(payload(otherExport, other)).contains("The other workspace's answer.");
    }

    @Test
    void shouldExpireTheReadyExportOfTheDeveloperWhoseFeedbackAnErasureDeletes() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        UUID derived = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES (?,?,?,?,?,'ON_WORK','DELIVERED',?,?,?)",
                derived,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getItemId(),
                developer,
                "Feedback derived from the erased person's review.",
                Timestamp.from(AT),
                Timestamp.from(AT));
        long unrelated =
                Objects.requireNonNull(data.member(scenario.workspace().getId()).getId());
        long developerExport = readyExport(developer);
        long unrelatedExport = readyExport(unrelated);
        assertThat(payload(developerExport, developer)).contains("Feedback derived from the erased person's review.");

        // As the erasure's feedback step does for feedback whose basis used the erased person's data.
        jdbc.update("DELETE FROM feedback WHERE id = ?", derived);

        AccountExport expired = read(developerExport, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(read(unrelatedExport, unrelated).getStatus()).isEqualTo(AccountExport.Status.READY);
    }

    @Test
    void shouldExpireTheReadyExportWhenAnErasureDeletesAPostedPieceBeforeItsFeedback() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        UUID feedback = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES (?,?,?,?,?,'ON_WORK','DELIVERED','Kept feedback.',?,?)",
                feedback,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getItemId(),
                developer,
                Timestamp.from(AT),
                Timestamp.from(AT));
        UUID piece = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,path,body,state,created_at,posted_at)"
                        + " VALUES (?,?,?,'FILE_NOTE',0,'src/Example.java',?,'POSTED',?,?)",
                piece,
                result.getWorkspaceId(),
                feedback,
                "A note that quoted the erased person.",
                Timestamp.from(AT),
                Timestamp.from(AT));
        long exportId = readyExport(developer);
        assertThat(payload(exportId, developer)).contains("A note that quoted the erased person.");

        // The erasure deletes the posted copies in an earlier step than the feedback itself.
        jdbc.update("DELETE FROM feedback_copy WHERE id = ?", piece);

        AccountExport expired = read(exportId, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
    }

    /** A withdrawn feedback shows its dates and not its text, also in an export built before the withdrawal. */
    @Test
    void shouldExpireTheReadyExportOfTheDeveloperWhoseFeedbackIsWithdrawn() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        UUID withdrawn = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES (?,?,?,?,?,'ON_WORK','DELIVERED',?,?,?)",
                withdrawn,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getItemId(),
                developer,
                "Feedback that turned out to be wrong.",
                Timestamp.from(AT),
                Timestamp.from(AT));
        long unrelated =
                Objects.requireNonNull(data.member(scenario.workspace().getId()).getId());
        long developerExport = readyExport(developer);
        long unrelatedExport = readyExport(unrelated);
        assertThat(payload(developerExport, developer)).contains("Feedback that turned out to be wrong.");

        decide(result.getWorkspaceId(), withdrawn, "RESTORE");
        assertThat(read(developerExport, developer).getStatus())
                .as("a decision that hides nothing leaves the export")
                .isEqualTo(AccountExport.Status.READY);
        decide(result.getWorkspaceId(), withdrawn, "WITHDRAW");

        AccountExport expired = read(developerExport, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(read(unrelatedExport, unrelated).getStatus()).isEqualTo(AccountExport.Status.READY);
        long fresh = readyExport(developer);
        assertThat(payload(fresh, developer)).doesNotContain("Feedback that turned out to be wrong.");
    }

    /** A withdrawal also hides the delivered edits that replaced the withdrawn feedback (FeedbackVisibilitySql). */
    @Test
    void shouldExpireTheReadyExportThatShowsAnEditOfTheWithdrawnFeedback() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        UUID original = deliveredFeedback(result, developer, "REPLACED", null, "The first version of the feedback.");
        // Replaced before its delivery, so only the edit chain leads from the withdrawn feedback to a delivered one.
        jdbc.update("UPDATE feedback SET delivered_at = NULL WHERE id = ?", original);
        deliveredFeedback(result, developer, "DELIVERED", original, "The edited feedback that is withdrawn.");
        long exportId = readyExport(developer);
        assertThat(payload(exportId, developer))
                .as("the export shows only the edit")
                .contains("The edited feedback that is withdrawn.")
                .doesNotContain("The first version of the feedback.");

        decide(result.getWorkspaceId(), original, "WITHDRAW");

        AccountExport expired = read(exportId, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(payload(readyExport(developer), developer)).doesNotContain("The edited feedback that is withdrawn.");
    }

    /** A correction that retracts one posted finding takes it out of the export, also out of a ready one. */
    @Test
    void shouldExpireTheReadyExportWhenACorrectionRetractsAPostedFinding() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        long workspace = result.getWorkspaceId();
        UUID feedbackId = deliveredFeedback(result, developer, "DELIVERED", null, "Feedback with one posted finding.");
        UUID piece = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,path,body,state,created_at,posted_at)"
                        + " VALUES (?,?,?,'FILE_NOTE',0,'src/Example.java',?,'POSTED',?,?)",
                piece,
                workspace,
                feedbackId,
                "A finding that turned out to be wrong.",
                Timestamp.from(AT),
                Timestamp.from(AT));
        long exportId = readyExport(developer);
        assertThat(payload(exportId, developer)).contains("A finding that turned out to be wrong.");

        correct(workspace, feedbackId, piece, "POSTED");
        assertThat(read(exportId, developer).getStatus())
                .as("a correction that keeps the finding leaves the export")
                .isEqualTo(AccountExport.Status.READY);
        correct(workspace, feedbackId, piece, "RETRACTED");

        AccountExport expired = read(exportId, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(payload(readyExport(developer), developer)).doesNotContain("A finding that turned out to be wrong.");
    }

    /** A quiet edit replaces the delivered original once the edit is posted, so its words leave the export. */
    @Test
    void shouldExpireTheReadyExportWhenAQuietEditReplacesTheDeliveredOriginal() {
        var scenario = data.scenario();
        long developer = Objects.requireNonNull(scenario.account().getId());
        var result = data.result(scenario, ResultOutcome.NOT_MET, AT);
        long workspace = result.getWorkspaceId();
        UUID original = deliveredFeedback(result, developer, "DELIVERED", null, "The original words of the feedback.");
        long exportId = readyExport(developer);
        assertThat(payload(exportId, developer)).contains("The original words of the feedback.");
        deliveredFeedback(result, developer, "DELIVERED", original, "The quietly edited feedback.");

        moveOriginal(workspace, original, FeedbackState.DELIVERED);
        assertThat(read(exportId, developer).getStatus())
                .as("a delivery update that changes nothing the export shows leaves the export")
                .isEqualTo(AccountExport.Status.READY);
        // As FeedbackSendTransactions does when the edit's copies are posted.
        moveOriginal(workspace, original, FeedbackState.REPLACED);

        AccountExport expired = read(exportId, developer);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        assertThat(payload(readyExport(developer), developer))
                .contains("The quietly edited feedback.")
                .doesNotContain("The original words of the feedback.");
    }

    /** The feedback trigger names the states that an export shows, so a new state cannot slip past it. */
    @Test
    void shouldWatchExactlyTheFeedbackStatesThatAnExportShows() {
        String definition = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname = 'trg_feedback_changed_exports'",
                String.class));
        for (FeedbackState state : FeedbackState.values()) {
            assertThat(definition.contains("'" + state.name() + "'"))
                    .as(state.name())
                    .isEqualTo(FeedbackState.VISIBLE_TO_DEVELOPER.contains(state));
        }
    }

    private void moveOriginal(long workspaceId, UUID feedbackId, FeedbackState state) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var row =
                    feedbackRows.findByIdAndWorkspaceId(feedbackId, workspaceId).orElseThrow();
            outcomes.update(row, state, null, row.getDeliveredAt());
        });
    }

    private UUID deliveredFeedback(Result result, long developer, String state, @Nullable UUID replaces, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at,replaces_id,composition) VALUES (?,?,?,?,?,'ON_WORK',?,?,?,?,?,CAST(? AS jsonb))",
                id,
                result.getWorkspaceId(),
                result.getReviewId(),
                result.getItemId(),
                developer,
                state,
                body,
                Timestamp.from(AT),
                Timestamp.from(AT),
                replaces,
                replaces == null ? "{}" : "{\"editOf\": \"" + replaces + "\"}");
        return id;
    }

    private void correct(long workspaceId, UUID feedbackId, UUID copyId, String state) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> copies.confirmCorrection(workspaceId, feedbackId, copyId, state));
    }

    private void decide(long workspaceId, UUID feedbackId, String action) {
        jdbc.update(
                "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,note,decided_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),
                workspaceId,
                feedbackId,
                action,
                "Decided in the test",
                Timestamp.from(Instant.now()));
    }

    /** An author's edit replaces a Slack message so that no earlier version stays, also in their export. */
    @Test
    void shouldExpireTheReadyExportOfAnAuthorWhoEditsASlackMessage() {
        var scenario = data.scenario();
        long owner = Objects.requireNonNull(scenario.account().getId());
        linkSlack(owner);
        long workspace = scenario.workspace().getId();
        UUID conversation = conversation(workspace, owner);
        slackQuestion(conversation, "1700000000.000100", "My token is hunter2, can you check it?");
        long exportId = readyExport(owner);
        assertThat(payload(exportId, owner)).contains("hunter2");

        entryService.editSlackMessage(workspace, conversation, "1700000000.000100", "Can you check my token?");

        AccountExport expired = read(exportId, owner);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        long fresh = readyExport(owner);
        assertThat(payload(fresh, owner)).contains("Can you check my token?").doesNotContain("hunter2");
    }

    /** A worker of a release without the fence reads its sources unordered against an erasure: it may not publish. */
    @Test
    void shouldRefuseAPublicationThatDoesNotHoldTheOwnersExportFence() {
        var scenario = data.scenario();
        long owner = Objects.requireNonNull(scenario.account().getId());
        long exportId = seedExport(owner);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    ownerScope.ofJob(owner);
                    jdbc.update(
                            "UPDATE account_export SET status = 'READY', payload = ?, completed_at = now(),"
                                    + " expires_at = now() + interval '2 days' WHERE id = ?",
                            "Words read before an erasure.".getBytes(StandardCharsets.UTF_8),
                            exportId);
                }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("published only under its owner's export fence");
        assertThat(read(exportId, owner).getStatus()).isEqualTo(AccountExport.Status.PENDING);
    }

    private void awaitBlockedBy(int pid, CompletableFuture<?> waiting) {
        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            return waiting.isDone()
                    || Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                            Boolean.class,
                            pid));
        });
    }

    private ExportGenerationWorker worker(AccountConversationExport messages) {
        return new ExportGenerationWorker(
                repository,
                accounts,
                assembler,
                messages,
                feedback,
                objectMapper,
                writer,
                new PrivacyJobMetrics(new SimpleMeterRegistry()),
                transactionManager,
                ownerScope);
    }

    private long readyExport(long accountId) {
        long exportId = seedExport(accountId);
        worker.generate(exportId, accountId);
        assertThat(read(exportId, accountId).getStatus()).isEqualTo(AccountExport.Status.READY);
        return exportId;
    }

    private long seedExport(long accountId) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            ownerScope.ofJob(accountId);
            return repository.save(new AccountExport(accountId)).getId();
        }));
    }

    private AccountExport read(long exportId, long accountId) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            ownerScope.ofJob(accountId);
            return repository.findById(exportId).orElseThrow();
        }));
    }

    private String payload(long exportId, long accountId) {
        return new String(Objects.requireNonNull(read(exportId, accountId).getPayload()), StandardCharsets.UTF_8);
    }

    private UUID conversation(long workspace, long account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation(id, workspace_id, account_id, created_at, last_activity_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id,
                workspace,
                account,
                Timestamp.from(AT),
                Timestamp.from(AT));
        return id;
    }

    private void slackQuestion(UUID conversation, String slackTs, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                                slack_ts, payload, created_at)
                SELECT ?, c.workspace_id, c.id, c.account_id, ?, 'USER', 'SLACK', ?, ?,
                       jsonb_build_object('type', 'message', 'id', 'pi-' || ?, 'message',
                           jsonb_build_object('role', 'user',
                               'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', ?)))),
                       ?
                FROM conversation c WHERE c.id = ?
                """, id, ++seq, text, slackTs, id.toString(), text, Timestamp.from(AT.plusSeconds(seq)), conversation);
    }

    private UUID entry(UUID conversation, String kind, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                                payload, created_at)
                SELECT ?, c.workspace_id, c.id, c.account_id, ?, ?, 'APP', ?,
                       jsonb_build_object('type', 'message', 'id', 'pi-' || ?, 'message',
                           jsonb_build_object('role', CASE ? WHEN 'USER' THEN 'user' ELSE 'assistant' END,
                               'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', ?)))),
                       ?
                FROM conversation c WHERE c.id = ?
                """,
                id,
                ++seq,
                kind,
                text,
                id.toString(),
                kind,
                text,
                Timestamp.from(AT.plusSeconds(seq)),
                conversation);
        return id;
    }

    /** Links the account to Slack, as a Slack author is: the person-data write fence holds the author's identities. */
    private void linkSlack(long accountId) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> providers.registerSourceInstance("SLACK", "https://slack.com"));
        long provider = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow()
                .getId());
        jdbc.update("""
                INSERT INTO identity(account_id,provider_id,provider_user_key,team_key,linked_at,linked_via)
                VALUES (?,?,?,'T1',now(),'MANUAL')
                """, accountId, provider, "U" + accountId);
    }
}
