package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationReceiptAnswers;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationReceiptsDue;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRunRows;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder;
import de.tum.cit.aet.hephaestus.testconfig.SharedTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * One workspace with two recipients, under the row-level security planned for the conversation tables: the application
 * role reads a conversation row only while the transaction is bound to the row's account. The policies live in this
 * class's own database, so no other test sees them. The pool holds one connection, so a read that needs a second
 * connection fails, and each claim reuses the connection of the one before it.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=1",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.connection-timeout=5000",
        })
@ActiveProfiles("test")
@Import({
    TestSecurityConfig.class,
    TestAsyncConfiguration.class,
    SharedEventRecorder.class,
    StubInstallationRepositoryEnumerator.class,
    SharedTestDoubles.class,
    AgentHandlerTestDoubles.class,
})
@Tag("integration")
class FeedbackRecipientRowSecurityIntegrationTest {
    private static final long WORKSPACE = 979_500;
    private static final long PROVIDER = 979_501;
    private static final AtomicLong ACCOUNTS = new AtomicLong(979_600);
    private static final TestDatabase DATABASE = rowSecuredDatabase();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.enabled", () -> false);
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    ConversationClaims claims;

    @Autowired
    FeedbackHistoryQueryService history;

    @Autowired
    ConversationTurnCompletion completion;

    @Autowired
    EventSerializer serializer;

    @Autowired
    ConversationTurnService turns;

    @Autowired
    OwnerScope owners;

    @Autowired
    DigestAdmission digests;

    @Autowired
    LlmUsageEventRepository usage;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Clock clock;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The application role, on the pool's one connection. */
    @Autowired
    JdbcTemplate application;

    @MockitoBean
    FeedbackGate gate;

    @BeforeEach
    void allowEveryDelivery() {
        when(gate.evaluate(any(), any(), anyBoolean()))
                .thenAnswer(call -> new FeedbackCheck(
                        call.getArgument(1),
                        true,
                        null,
                        Instant.now(),
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
    }

    @Test
    void shouldClaimTheCopyOnlyForATurnOfItsRecipient() {
        var ada = recipient();
        var bo = recipient();

        assertThat(claims.claimConversationCopy(WORKSPACE, bo.account(), ada.feedback(), bo.turn(), 1))
                .as("another recipient's turn")
                .isEmpty();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), bo.turn(), 1))
                .as("another recipient's turn, named with the recipient's account")
                .isEmpty();
        assertThat(copyState(ada)).isEqualTo("PLANNED");
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .as("the recipient's own turn, which only the recipient's binding shows")
                .isPresent();
        assertThat(copyState(ada)).isEqualTo("WRITE_STARTED");
    }

    @Test
    void shouldSettleEachCopyThroughTheListenerWithItsRecipientsAnswerOnly() {
        var ada = recipient();
        var bo = recipient();
        var adaFence = claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1)
                .orElseThrow();
        assertThat(claims.claimConversationCopy(WORKSPACE, bo.account(), bo.feedback(), bo.turn(), 1))
                .isPresent();
        UUID foreign = answer(bo, ada.feedback());
        UUID adas = answer(ada, ada.feedback());
        UUID bos = answer(bo, bo.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        asAdministrator(ConversationTurnRunRows.endDone(bo.turn()));
        Instant at = adaFence.writeStartedAt().plusSeconds(1);

        assertThatThrownBy(
                        () -> claims.recordConversationReceipt(WORKSPACE, adaFence, delivered(adaFence, foreign, at)))
                .as("Bo's answer, although it names Ada's feedback")
                .isInstanceOf(ResponseStatusException.class);
        assertThat(copyState(ada)).isEqualTo("WRITE_STARTED");

        completion.settle(receiptsDue(ada));
        assertThat(receipt(ada)).isEqualTo("POSTED " + adas);
        assertThat(receipt(bo)).as("Ada's receipts leave Bo's copy alone").isEqualTo("WRITE_STARTED -");

        completion.settle(receiptsDue(bo));
        assertThat(receipt(bo)).isEqualTo("POSTED " + bos);
    }

    /**
     * A receipts publication that the previous release stored and this release delivers again. It names no account:
     * the listener reads the answer as the person each claim is for.
     */
    @Test
    void shouldSettleAReceiptsPublicationOfThePreviousReleaseAsThePersonEachClaimIsFor() {
        var ada = recipient();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .isPresent();
        UUID adas = answer(ada, ada.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        var due = receiptsDue(ada);
        String stored = """
                {"workspaceId":%d,"turnId":"%s","attemptStart":"%s","until":"%s","shown":null,"unconfirmed":[],
                 "otherwise":"FAILED","unconfirmedReason":"unconfirmed","failedReason":"failed"}
                """.formatted(WORKSPACE, ada.turn(), due.attemptStart(), due.until());

        completion.settle(serializer.deserialize(stored, ConversationReceiptsDue.class));

        assertThat(receipt(ada)).isEqualTo("POSTED " + adas);
    }

    /** A redaction of the answer after the listener read it and before the receipt is stored. */
    @Test
    void shouldRecordTheClaimUnconfirmedWhenItsAnswerIsRedactedBeforeTheReceiptIsStored() {
        var ada = recipient();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .isPresent();
        UUID adas = answer(ada, ada.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        var redactedAfterTheRead = new ConversationReceiptAnswers(turns, owners) {
            @Override
            public Optional<Map<UUID, UUID>> shownIn(long workspaceId, long personAccountId, UUID turnId) {
                var shown = new TransactionTemplate(transactionManager)
                        .execute(status -> super.shownIn(workspaceId, personAccountId, turnId));
                asAdministrator("UPDATE conversation_entry SET redacted_at = now() WHERE id = '%s'".formatted(adas));
                return Objects.requireNonNull(shown);
            }
        };

        new ConversationTurnCompletion(turns, digests, claims, redactedAfterTheRead, usage, meters, clock, events)
                .settle(receiptsDue(ada));

        assertThat(receipt(ada)).isEqualTo("WRITE_STARTED -");
        assertThat(administrator()
                        .queryForObject(
                                "SELECT f.state || ' ' || c.last_error FROM feedback_copy c"
                                        + " JOIN feedback f ON f.id = c.feedback_id WHERE c.id = ?",
                                String.class,
                                ada.copy()))
                .as("an unconfirmed receipt with its reason, which settles the claim")
                .isEqualTo("UNCONFIRMED Hephaestus could not check whether its answer showed the feedback.");
    }

    /**
     * A completed app answer counts whole, so the end recorded each piece it called as shown. A redaction before the
     * listener reads the answer leaves that unverifiable: the reason must not say the answer stopped or failed.
     */
    @Test
    void shouldRecordACalledPieceUnverifiableWhenACompletedAppAnswerIsRedactedBeforeSettlement() {
        var ada = recipient();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .isPresent();
        UUID adas = answer(ada, ada.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        asAdministrator("UPDATE conversation_entry SET redacted_at = now() WHERE id = '%s'".formatted(adas));
        var due = receiptsDue(ada);

        completion.settle(new ConversationReceiptsDue(
                WORKSPACE,
                ada.turn(),
                due.attemptStart(),
                due.until(),
                null,
                Set.of(ada.feedback()),
                ConversationCopyOutcome.FAILED,
                "Hephaestus's answer stopped or failed after it showed the feedback.",
                "Hephaestus's answer did not show the feedback."));

        assertThat(receipt(ada)).isEqualTo("WRITE_STARTED -");
        assertThat(administrator()
                        .queryForObject(
                                "SELECT f.state || ' ' || c.last_error FROM feedback_copy c"
                                        + " JOIN feedback f ON f.id = c.feedback_id WHERE c.id = ?",
                                String.class,
                                ada.copy()))
                .isEqualTo("UNCONFIRMED Hephaestus could not check whether its answer showed the feedback.");
    }

    /** A redaction that commits after the receipt read the answer's words and before it updates the copy. */
    @Test
    void shouldRecordTheClaimUnconfirmedWhenItsAnswerIsRedactedWhileTheReceiptUpdatesTheCopy() {
        var ada = recipient();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .isPresent();
        UUID adas = answer(ada, ada.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        // In the receipt's update of this copy, before the copy's guard checks the words again.
        asAdministrator("""
                CREATE FUNCTION test_redact_in_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    UPDATE public.conversation_entry SET redacted_at = clock_timestamp() WHERE id = '%s';
                    RETURN NEW;
                END
                $$;
                CREATE TRIGGER test_redact_in_receipt BEFORE UPDATE OF posted_at ON public.feedback_copy
                    FOR EACH ROW WHEN (NEW.id = '%s' AND NEW.posted_at IS NOT NULL)
                    EXECUTE FUNCTION test_redact_in_receipt();
                """.formatted(adas, ada.copy()));
        try {
            completion.settle(receiptsDue(ada));
        } finally {
            asAdministrator(
                    "DROP TRIGGER test_redact_in_receipt ON public.feedback_copy; DROP FUNCTION test_redact_in_receipt();");
        }

        assertThat(receipt(ada)).isEqualTo("WRITE_STARTED -");
        assertThat(administrator()
                        .queryForObject(
                                "SELECT f.state || ' ' || c.last_error FROM feedback_copy c"
                                        + " JOIN feedback f ON f.id = c.feedback_id WHERE c.id = ?",
                                String.class,
                                ada.copy()))
                .as("the guard's refusal of the words is a receipt that cannot be checked, not an outage")
                .isEqualTo("UNCONFIRMED Hephaestus could not check whether its answer showed the feedback.");
    }

    /**
     * A Slack reply that showed the feedback, whose answer a redaction changed before the receipt was settled, for
     * example on a redelivery after a failed first settlement: the person saw the feedback, so it is never "not shown".
     */
    @Test
    void shouldRecordAFeedbackShownInSlackUnconfirmedWhenItsAnswerIsRedactedBeforeSettlement() {
        var ada = recipient();
        assertThat(claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .isPresent();
        UUID adas = answer(ada, ada.feedback());
        asAdministrator(ConversationTurnRunRows.endDone(ada.turn()));
        asAdministrator("UPDATE conversation_entry SET redacted_at = now() WHERE id = '%s'".formatted(adas));

        completion.settle(receiptsDue(ada, Set.of(ada.feedback())));

        assertThat(administrator()
                        .queryForObject(
                                "SELECT f.state || ' ' || c.last_error FROM feedback_copy c"
                                        + " JOIN feedback f ON f.id = c.feedback_id WHERE c.id = ?",
                                String.class,
                                ada.copy()))
                .as("shown in Slack, now uncheckable: unconfirmed with its reason, never failed as not shown")
                .isEqualTo("UNCONFIRMED Hephaestus could not check whether its answer showed the feedback.");
    }

    /** The receipts that the end of the recipient's turn publishes for the listener that settles them. */
    private static ConversationReceiptsDue receiptsDue(Recipient recipient) {
        return receiptsDue(recipient, null);
    }

    /** As {@link #receiptsDue(Recipient)}, for an end that recorded which feedback its reply showed. */
    private static ConversationReceiptsDue receiptsDue(Recipient recipient, @Nullable Set<UUID> shown) {
        Instant startedAt = Objects.requireNonNull(administrator()
                        .queryForObject(
                                "SELECT started_at FROM conversation_turn WHERE id = ?",
                                Timestamp.class,
                                recipient.turn()))
                .toInstant();
        Instant now = Objects.requireNonNull(administrator().queryForObject("SELECT now()", Timestamp.class))
                .toInstant();
        return new ConversationReceiptsDue(
                WORKSPACE,
                recipient.turn(),
                startedAt,
                now,
                shown,
                Set.of(),
                ConversationCopyOutcome.FAILED,
                "unconfirmed",
                "failed");
    }

    /** The state and answer of the recipient's copy, with {@code -} for none. */
    private static String receipt(Recipient recipient) {
        return Objects.requireNonNull(administrator()
                .queryForObject(
                        "SELECT state || ' ' || COALESCE(conversation_entry_id::text, '-') FROM feedback_copy WHERE id = ?",
                        String.class,
                        recipient.copy()));
    }

    @Test
    void shouldReadTheThreadForItsRecipientOnOneConnection() {
        var ada = recipient();
        var bo = recipient();
        delivered(ada);

        var entry = history.find(WORKSPACE, ada.feedback(), ada.account()).orElseThrow();

        assertThat(entry.threadId()).isEqualTo(ada.conversation());
        assertThat(history.forDeveloper(WORKSPACE, ada.account(), Instant.EPOCH, 10))
                .filteredOn(read -> read.feedbackId().equals(ada.feedback()))
                .singleElement()
                .satisfies(read -> assertThat(read.threadId()).isEqualTo(ada.conversation()));
        assertThat(history.find(WORKSPACE, ada.feedback(), bo.account())).isEmpty();
    }

    @Test
    void shouldLeaveTheNextClaimUsableAfterAClaimFailsWithItsOwnerBound() {
        var ada = recipient();
        var bo = recipient();
        when(gate.evaluate(argThat(row -> row != null && row.getDeveloperId() == ada.account()), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("The delivery check failed."));

        assertThatThrownBy(() -> claims.claimConversationCopy(WORKSPACE, ada.account(), ada.feedback(), ada.turn(), 1))
                .hasMessage("The delivery check failed.");

        assertThat(copyState(ada)).as("the failed claim rolled back").isEqualTo("PLANNED");
        assertThat(application.queryForObject("SELECT public.current_owner_account()", Long.class))
                .as("the connection keeps no owner after the failed claim")
                .isNull();
        assertThat(claims.claimConversationCopy(WORKSPACE, bo.account(), bo.feedback(), bo.turn(), 1))
                .as("the next claim, on the same connection, binds its own recipient")
                .isPresent();
        assertThat(copyState(bo)).isEqualTo("WRITE_STARTED");
    }

    /** A recipient with a running Hephaestus turn and conversation feedback whose copy is planned. */
    private record Recipient(long account, UUID conversation, UUID turn, UUID feedback, UUID copy) {}

    private static Recipient recipient() {
        long account = ACCOUNTS.getAndIncrement();
        var recipient =
                new Recipient(account, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID run = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        asAdministrator("""
                INSERT INTO account (id, display_name, status) VALUES (%2$d, 'Recipient %2$d', 'ACTIVE');
                INSERT INTO identity (account_id, provider_id, provider_user_key, linked_via)
                VALUES (%2$d, %3$d, '%2$d', 'MANUAL');
                INSERT INTO membership (workspace_id, account_id, role, role_source)
                VALUES (%1$d, %2$d, 'MEMBER', 'PERSON');
                INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)
                VALUES ('%4$s', %1$d, %2$d, now(), now());
                %8$s;
                INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id, status, surface,
                    attempt, run_id, started_at, created_at)
                VALUES ('%5$s', %1$d, '%4$s', %2$d, 'request-%5$s', 'RUNNING', 'APP', 1, '%9$s', now(), now());
                INSERT INTO review (id, workspace_id, item_id, kind, item_version, origin, status, ordered_at, created_at)
                VALUES ('%10$s', %1$d, %1$d, 'ITEM', 'v1', 'REQUEST', 'RUNNING', now(), now());
                INSERT INTO feedback (id, workspace_id, review_id, item_id, person_account_id, destination, state, body)
                VALUES ('%6$s', %1$d, '%10$s', %1$d, %2$d, 'CONVERSATION', 'READY', 'Discuss focused tests.');
                INSERT INTO feedback_copy (id, workspace_id, feedback_id, kind, position, state, write_key)
                VALUES ('%7$s', %1$d, '%6$s', 'CHAT_TURN', 0, 'PLANNED', '%7$s');
                """.formatted(
                        WORKSPACE,
                        account,
                        PROVIDER,
                        recipient.conversation(),
                        recipient.turn(),
                        recipient.feedback(),
                        recipient.copy(),
                        ConversationTurnRunRows.insert(run, WORKSPACE, recipient.conversation(), "RUNNING", 1),
                        run,
                        review));
        return recipient;
    }

    /**
     * Hephaestus's answer in the recipient's turn, written in Slack, with the successful {@code show_feedback} call that
     * showed {@code feedback}.
     */
    private static UUID answer(Recipient recipient, UUID feedback) {
        UUID entry = UUID.randomUUID();
        asAdministrator("""
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                    payload, turn_id, created_at)
                VALUES ('%3$s', %1$d, '%4$s', %2$d, (SELECT count(*) + 1 FROM conversation_entry
                                                    WHERE conversation_id = '%4$s'),
                        'ANSWER', 'SLACK', 'Some context.',
                        '{"type":"message","id":"a1","message":{"role":"assistant","content":[
                          {"type":"text","text":"Some context."},
                          {"type":"toolCall","id":"call-%3$s","name":"show_feedback",
                           "arguments":{"feedbackId":"%6$s","text":"Try a focused test."}}]}}', '%5$s', now());
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                    payload, turn_id, created_at)
                VALUES (gen_random_uuid(), %1$d, '%4$s', %2$d, (SELECT count(*) + 1 FROM conversation_entry
                                                              WHERE conversation_id = '%4$s'),
                        'TOOL_RESULT', 'SLACK', '',
                        '{"type":"message","id":"r-%3$s","message":{"role":"toolResult","toolCallId":"call-%3$s",
                          "toolName":"show_feedback","isError":false,"content":[{"type":"text","text":"done"}]}}',
                        '%5$s', now());
                """.formatted(
                        WORKSPACE, recipient.account(), entry, recipient.conversation(), recipient.turn(), feedback));
        return entry;
    }

    /** The recipient's feedback, delivered through a Slack answer of its conversation. */
    private static void delivered(Recipient recipient) {
        UUID entry = UUID.randomUUID();
        asAdministrator("""
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                    created_at)
                VALUES ('%3$s', %1$d, '%4$s', %2$d, 1, 'ANSWER', 'SLACK', 'Try a focused test.', now());
                UPDATE feedback SET state = 'DELIVERED', delivered_at = now() WHERE id = '%5$s';
                DELETE FROM feedback_copy WHERE id = '%6$s';
                INSERT INTO feedback_copy (id, workspace_id, feedback_id, kind, position, state, write_key,
                    write_started_at, posted_at, body, conversation_entry_id)
                VALUES ('%6$s', %1$d, '%5$s', 'CHAT_TURN', 0, 'POSTED', '%6$s', now(), now(), 'Try a focused test.',
                        '%3$s');
                """.formatted(
                        WORKSPACE,
                        recipient.account(),
                        entry,
                        recipient.conversation(),
                        recipient.feedback(),
                        recipient.copy()));
    }

    private static ConversationCopyReceipt delivered(CopyWriteFence fence, UUID entry, Instant at) {
        return new ConversationCopyReceipt(
                fence.conversationTurnId(), entry, ConversationCopyOutcome.DELIVERED, at, null);
    }

    private static String copyState(Recipient recipient) {
        return Objects.requireNonNull(administrator()
                .queryForObject("SELECT state FROM feedback_copy WHERE id = ?", String.class, recipient.copy()));
    }

    /**
     * The migrated database with the conversation tables under row-level security for the application role, and one
     * workspace with an item. The superuser that writes the fixtures bypasses the policies.
     */
    private static TestDatabase rowSecuredDatabase() {
        var database = PostgreSQLTestContainer.createApplicationDatabase("feedback_recipient_rows");
        var policies = new StringBuilder();
        for (String table : new String[] {"conversation", "conversation_entry", "conversation_turn"}) {
            policies.append("""
                    ALTER TABLE public.%1$s ENABLE ROW LEVEL SECURITY;
                    CREATE POLICY %1$s_owner ON public.%1$s TO hephaestus_app
                        USING (account_id = public.current_owner_account())
                        WITH CHECK (account_id = public.current_owner_account());
                    """.formatted(table));
        }
        asAdministrator(database, policies + """
                INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
                VALUES (%1$d, 'recipient-rows', 'Recipient rows', 'recipient-rows', 'ORG', 'ACTIVE');
                INSERT INTO identity_provider (id, type, server_url)
                VALUES (%2$d, 'GITHUB', 'https://recipient-rows.example.test');
                INSERT INTO item (id, workspace_id, kind, source_id) VALUES (%1$d, %1$d, 'scm.pull_request', 42);
                """.formatted(WORKSPACE, PROVIDER));
        return database;
    }

    private static JdbcTemplate administrator() {
        var administrator = PostgreSQLTestContainer.administrator();
        return new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.jdbcUrl(), administrator.username(), administrator.password()));
    }

    private static void asAdministrator(String sql) {
        asAdministrator(DATABASE, sql);
    }

    /** Runs {@code sql} as the container's superuser in one transaction. */
    private static void asAdministrator(TestDatabase database, String sql) {
        var administrator = PostgreSQLTestContainer.administrator();
        try (Connection connection =
                DriverManager.getConnection(database.jdbcUrl(), administrator.username(), administrator.password())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("The fixture could not be written", e);
        }
    }
}
