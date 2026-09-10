package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures.admittedMentorConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.TurnAlreadyInFlightException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the {@link MentorTurnPersistence} REQUIRES_NEW contract end-to-end against a real
 * Postgres container: DB unique partial index, JSONB metadata round-trip, status transitions,
 * reaper sweep.
 */
class MentorTurnPersistenceIntegrationTest extends BaseIntegrationTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Autowired
    private MentorTurnPersistence persistence;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ChatThreadRepository chatThreadRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private LlmUsageEventRepository usageEventRepository;

    @Autowired
    private MentorInFlightAccounting accounting;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Workspace workspace;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        databaseTestUtils.cleanDatabase();
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_message_in_flight_v2 "
                    + "ON chat_message (thread_id) WHERE status = 'in_flight'");
            // Drop-then-add so a previous run's constraint doesn't survive across tests.
            stmt.execute("ALTER TABLE chat_message DROP CONSTRAINT IF EXISTS chk_chat_message_status");
            stmt.execute("ALTER TABLE chat_message ADD CONSTRAINT chk_chat_message_status "
                    + "CHECK (status IN ('in_flight', 'completed', 'interrupted'))");
        }
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mentor-persist-ws");
        workspace.setDisplayName("Mentor Persistence Workspace");
        workspace.setAccountLogin("mentor-persist-org");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaceRepository.save(workspace);

        IdentityProvider gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));

        user = new User();
        user.setNativeId(7_001L);
        user.setLogin("mentor-tester");
        user.setName("Mentor Tester");
        user.setAvatarUrl("https://example.com/m.png");
        user.setHtmlUrl("https://gitlab.com/mentor-tester");
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(gitProvider);
        user = userRepository.save(user);
    }

    @AfterEach
    void restoreGeneratedSchema() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS ux_chat_message_in_flight_v2");
            statement.execute("ALTER TABLE chat_message DROP CONSTRAINT IF EXISTS chk_chat_message_status");
        }
    }

    @Test
    void ensureThread_createsWhenAbsent() {
        UUID threadId = UUID.randomUUID();
        ChatThread thread = persistence.ensureThread(workspace.getId(), threadId, user, "Hello mentor");
        assertThat(thread.getId()).isEqualTo(threadId);
        assertThat(thread.getUser().getId()).isEqualTo(user.getId());
        assertThat(thread.getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(thread.getTitle()).isEqualTo("Hello mentor");
        assertThat(chatThreadRepository.findById(threadId)).isPresent();
    }

    @Test
    void ensureThread_returnsExisting() {
        UUID threadId = UUID.randomUUID();
        ChatThread first = persistence.ensureThread(workspace.getId(), threadId, user, "first prompt");
        ChatThread second = persistence.ensureThread(workspace.getId(), threadId, user, "second prompt");
        assertThat(second.getId()).isEqualTo(first.getId());
        // Title is fixed on first write — a second call with a different prompt must NOT
        // overwrite, otherwise the thread sidebar flickers between titles.
        assertThat(second.getTitle()).isEqualTo("first prompt");
    }

    @Test
    void ensureThread_foreignOwnerThrows() {
        UUID threadId = UUID.randomUUID();
        persistence.ensureThread(workspace.getId(), threadId, user, "hello");

        User other = new User();
        other.setNativeId(7_002L);
        other.setLogin("other");
        other.setName("Other");
        other.setAvatarUrl("https://example.com/o.png");
        other.setHtmlUrl("https://gitlab.com/other");
        other.setType(User.Type.USER);
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        other.setProvider(user.getProvider());
        other = userRepository.save(other);

        final User otherUser = other;
        assertThatThrownBy(() -> persistence.ensureThread(workspace.getId(), threadId, otherUser, "intruder"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void persistInFlight_happyPath(CapturedOutput output) {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello mentor", assistantId, null, admittedMentorConfig());
        assertThat(cookie.assistantMessageId()).isEqualTo(assistantId);

        ChatMessage assistant = chatMessageRepository.findById(assistantId).orElseThrow();
        assertThat(assistant.getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(assistant.getStatus()).isEqualTo(ChatMessage.Status.in_flight);
        assertThat(assistant.getParentMessageId()).isEqualTo(cookie.userMessageId());
        assertThat(output).doesNotContain("HHH90032022");

        ChatMessage userMessage =
                chatMessageRepository.findById(cookie.userMessageId()).orElseThrow();
        assertThat(userMessage.getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(userMessage.getParts().get(0).path("text").asString()).isEqualTo("hello mentor");
    }

    @Test
    void persistInFlight_honorsClientUserMessageId() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID clientUserId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello mentor", assistantId, clientUserId, admittedMentorConfig());
        assertThat(cookie.userMessageId()).isEqualTo(clientUserId);
        assertThat(chatMessageRepository.findById(clientUserId)).isPresent();
    }

    @Test
    void persistInFlight_secondCallThrows() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        persistence.persistInFlight(thread, "first", UUID.randomUUID(), null, admittedMentorConfig());
        assertThatThrownBy(() ->
                        persistence.persistInFlight(thread, "second", UUID.randomUUID(), null, admittedMentorConfig()))
                .isInstanceOf(TurnAlreadyInFlightException.class);
    }

    @Test
    void shouldRollBackTheNewParentWhenAnotherTurnAlreadyExists() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "first");
        persistence.persistInFlight(thread, "first", UUID.randomUUID(), null, admittedMentorConfig());
        UUID rejectedUserId = UUID.randomUUID();
        UUID rejectedAssistantId = UUID.randomUUID();

        assertThatThrownBy(() -> persistence.persistInFlight(
                        thread, "second", rejectedAssistantId, rejectedUserId, admittedMentorConfig()))
                .isInstanceOf(TurnAlreadyInFlightException.class);

        assertThat(chatMessageRepository.findById(rejectedUserId)).isEmpty();
        assertThat(chatMessageRepository.findById(rejectedAssistantId)).isEmpty();
    }

    @Test
    void persistInFlight_concurrentRace_theDbUniqueIndexAloneLetsExactlyOneWriterWin() throws Exception {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            Callable<Object> attempt = () -> {
                ready.countDown();
                fire.await(5, TimeUnit.SECONDS);
                try {
                    return persistence.persistInFlight(thread, "race", UUID.randomUUID(), null, admittedMentorConfig());
                } catch (RuntimeException ex) {
                    return ex; // surface to caller for classification
                }
            };
            var fa = pool.submit(attempt);
            var fb = pool.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            Object resultA = fa.get(10, TimeUnit.SECONDS);
            Object resultB = fb.get(10, TimeUnit.SECONDS);

            int winners = 0;
            int conflicts = 0;
            for (Object r : List.of(resultA, resultB)) {
                if (r instanceof MentorTurnPersistence.TurnPersistenceCookie) {
                    winners++;
                } else if (r instanceof TurnAlreadyInFlightException) {
                    // Narrowed exception — production must translate DataIntegrityViolation into
                    // TurnAlreadyInFlight before the orchestrator sees it. A regression that
                    // drops the isInFlightUniqueViolation filter would leak the unwrapped type.
                    conflicts++;
                } else if (r instanceof Throwable t) {
                    throw new AssertionError("Unexpected exception type: " + t, t);
                }
            }
            assertThat(winners).as("exactly one writer succeeds").isEqualTo(1);
            assertThat(conflicts).as("exactly one writer 409s").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void finalise_writesCompletedRow() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());

        TranslatorState state = new TranslatorState(assistantId);
        state.observeModel("openai/gpt-oss-120b");
        ObjectNode usage = NODES.objectNode();
        usage.put("input", 123).put("output", 45);
        state.observeUsage(usage);
        // Open + close a text block so partsSnapshot() has something to write.
        state.openTextBlock("text-0");
        state.appendText("Hello there!");
        state.closeTextBlock();

        UIMessageChunk.MessageMetadata finishMeta = new UIMessageChunk.MessageMetadata(
                "openai/gpt-oss-120b",
                new UIMessageChunk.MessageMetadata.Usage(123, 45, null, null, 168),
                /* costUsd */ null);
        UIMessageChunk.Finish finish = new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, finishMeta);

        persistence.finalise(cookie, state, finish, MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        ChatMessage assistant = chatMessageRepository.findById(assistantId).orElseThrow();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessage.Status.completed);
        JsonNode meta = assistant.getMetadata();
        assertThat(meta.path("finishReason").asString()).isEqualTo("stop");
        assertThat(meta.path("model").asString()).isEqualTo("openai/gpt-oss-120b");
        // Nested wire shape — must match UIMessageChunk.MessageMetadata + webapp MessageMetadata
        // so a rehydrated thread renders identically to the live stream.
        assertThat(meta.path("usage").path("input").asLong()).isEqualTo(123);
        assertThat(meta.path("usage").path("output").asLong()).isEqualTo(45);
        assertThat(meta.path("usage").path("totalTokens").asLong()).isEqualTo(168);
        assertThat(meta.has("inputTokens")).as("flat keys retired").isFalse();
        assertThat(assistant.getParts().isArray()).isTrue();
        assertThat(assistant.getParts().get(0).path("text").asString()).isEqualTo("Hello there!");
    }

    @Test
    void finalise_persistsProviderTotalTokensWhenItDivergesFromInputPlusOutput() {
        // A provider-reported totalTokens that includes cache tokens legitimately exceeds input+output. The
        // persisted block must round-trip the WIRE total unchanged (single source of truth), NOT re-derive
        // it as input+output — otherwise a rehydrated thread renders a different token count than the stream.
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hi");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hi", assistantId, null, admittedMentorConfig());

        TranslatorState state = new TranslatorState(assistantId);
        state.observeModel("openai/gpt-oss-120b");
        ObjectNode usage = NODES.objectNode();
        usage.put("input", 100).put("output", 50);
        state.observeUsage(usage);
        state.openTextBlock("text-0");
        state.appendText("ok");
        state.closeTextBlock();

        // Wire total = 200 ≠ input+output (150): provider counted 50 cache tokens on top.
        UIMessageChunk.MessageMetadata finishMeta = new UIMessageChunk.MessageMetadata(
                "openai/gpt-oss-120b",
                new UIMessageChunk.MessageMetadata.Usage(100, 50, 50, null, 200),
                /* costUsd */ null);
        persistence.finalise(
                cookie,
                state,
                new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, finishMeta),
                MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        JsonNode meta =
                chatMessageRepository.findById(assistantId).orElseThrow().getMetadata();
        assertThat(meta.path("usage").path("input").asLong()).isEqualTo(100);
        assertThat(meta.path("usage").path("output").asLong()).isEqualTo(50);
        assertThat(meta.path("usage").path("totalTokens").asLong()).isEqualTo(200);
    }

    @Test
    void finalise_storesSessionJsonlByteIdentically() {
        UUID threadId = UUID.randomUUID();
        ChatThread thread = persistence.ensureThread(workspace.getId(), threadId, user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());

        // 3-byte and 4-byte UTF-8 characters exercise any layer that round-trips through String.
        byte[] expectedBytes = ("{\"type\":\"user_message\",\"text\":\"hello €\"}\n"
                        + "{\"type\":\"assistant_message\",\"text\":\"hi 😀\",\"stopReason\":\"stop\"}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        TranslatorState state = new TranslatorState(assistantId);
        state.observeSessionJsonl(expectedBytes);
        persistence.finalise(
                cookie,
                state,
                new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null),
                MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        assertThat(chatThreadRepository.findSessionJsonl(threadId))
                .as("byte-identical: any re-encoding kills prompt-cache prefix matching")
                .contains(expectedBytes);
    }

    @Test
    void finalise_storesSessionJsonlAboveToastThreshold() {
        // Postgres TOAST threshold is ~2KB, so a 1MB payload exercises out-of-line storage and
        // detoast on read — the path that would surface an encoding/transport regression in JDBC
        // stream handling.
        UUID threadId = UUID.randomUUID();
        ChatThread thread = persistence.ensureThread(workspace.getId(), threadId, user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());

        byte[] bigBytes = new byte[1024 * 1024]; // 1 MiB
        // Pattern with a stable header + repeating non-zero filler so a partial-read regression
        // is detectable by a single-byte check anywhere in the array.
        byte[] header = "{\"type\":\"user_message\",\"text\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(header, 0, bigBytes, 0, header.length);
        for (int i = header.length; i < bigBytes.length - 3; i++) bigBytes[i] = (byte) ('a' + (i % 26));
        bigBytes[bigBytes.length - 3] = '"';
        bigBytes[bigBytes.length - 2] = '}';
        bigBytes[bigBytes.length - 1] = '\n';

        TranslatorState state = new TranslatorState(assistantId);
        state.observeSessionJsonl(bigBytes);
        persistence.finalise(
                cookie,
                state,
                new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null),
                MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        byte[] readBack = chatThreadRepository.findSessionJsonl(threadId).orElseThrow();
        assertThat(readBack).as("1MB TOAST round-trip preserves every byte").isEqualTo(bigBytes);
    }

    @Test
    void finalise_withoutSessionJsonl_preservesPriorTurn() {
        UUID threadId = UUID.randomUUID();
        ChatThread thread = persistence.ensureThread(workspace.getId(), threadId, user, "hello");

        byte[] priorBytes = "{\"prior\":\"turn\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        chatThreadRepository.updateSessionJsonl(threadId, priorBytes);

        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "follow-up", assistantId, null, admittedMentorConfig());
        persistence.finalise(
                cookie,
                new TranslatorState(assistantId),
                new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null),
                MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        assertThat(chatThreadRepository.findSessionJsonl(threadId)).contains(priorBytes);
    }

    @Test
    void interrupt_writesInterruptedRow() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());

        persistence.interrupt(cookie, new TranslatorState(assistantId), new IllegalStateException("upstream timeout"));

        ChatMessage assistant = chatMessageRepository.findById(assistantId).orElseThrow();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessage.Status.interrupted);
        assertThat(assistant.getMetadata().path("error").asString()).isEqualTo("upstream timeout");
    }

    @Test
    void interrupt_afterLlmCallStarted_writesUnverifiableLedgerEventWhenUsageIsMissing() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());
        TranslatorState state = new TranslatorState(assistantId);
        state.markLlmCallStarted();

        persistence.interrupt(cookie, state, new IllegalStateException("upstream disconnected"));

        var event = usageEventRepository.findAll().stream()
                .filter(row -> row.getSourceId().equals(assistantId))
                .findFirst();
        assertThat(event).isPresent();
        assertThat(event.orElseThrow().getPricingState()).isEqualTo(PricingState.UNPRICED);
        assertThat(event.orElseThrow().getCostUsd()).isNull();
    }

    @Test
    void finalise_cacheOnlyUsage_writesPricedLedgerEvent() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        LlmPriceSnapshot price = new LlmPriceSnapshot(
                FundingSource.INSTANCE,
                PricingState.PRICED,
                12L,
                null,
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("2"),
                new BigDecimal("3"));
        MentorLlmConfig config = new MentorLlmConfig(
                "openai-responses",
                "https://api.openai.com/v1",
                "test-model",
                null,
                null,
                false,
                FundingSource.INSTANCE,
                1L,
                1L,
                null,
                price,
                false,
                600);
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, config);
        TranslatorState state = new TranslatorState(assistantId);
        state.markLlmCallStarted();
        ObjectNode usage = NODES.objectNode();
        usage.put("input", 0).put("output", 0).put("cacheRead", 500_000).put("cacheWrite", 0);
        state.observeUsage(usage);

        persistence.finalise(
                cookie,
                state,
                new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null),
                MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        var event = usageEventRepository.findAll().stream()
                .filter(row -> row.getSourceId().equals(assistantId))
                .findFirst()
                .orElseThrow();
        assertThat(event.getPricingState()).isEqualTo(PricingState.PRICED);
        assertThat(event.getCacheReadTokens()).isEqualTo(500_000);
        assertThat(event.getCostUsd()).isEqualByComparingTo("1.000000");
    }

    @Test
    void interrupt_beforeLlmCallStarted_doesNotInventAUsageEvent() {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "hello");
        UUID assistantId = UUID.randomUUID();
        MentorTurnPersistence.TurnPersistenceCookie cookie =
                persistence.persistInFlight(thread, "hello", assistantId, null, admittedMentorConfig());

        persistence.interrupt(
                cookie, new TranslatorState(assistantId), new IllegalStateException("sandbox attach failed"));

        assertThat(usageEventRepository.findAll())
                .noneMatch(row -> row.getSourceId().equals(assistantId));
    }

    @Test
    void optimisticLocking_aLateFinaliseCannotOverwriteWhatTheReaperWrote() throws Exception {
        UUID assistantId = persistInFlightTurn("hello");

        // Simulate the in-flight runner: load a managed snapshot at the current version.
        ChatMessage stale = chatMessageRepository.findById(assistantId).orElseThrow();
        Long versionBefore = stale.getVersion();
        assertThat(versionBefore).isNotNull();

        setCreatedAt(assistantId, Instant.now().minus(Duration.ofMinutes(200)));
        reaperWithAnUnsafeWindow().reap();
        ChatMessage afterReaper = chatMessageRepository.findById(assistantId).orElseThrow();
        assertThat(afterReaper.getVersion()).isEqualTo(versionBefore + 1L);
        assertThat(afterReaper.getStatus()).isEqualTo(ChatMessage.Status.interrupted);

        stale.setStatus(ChatMessage.Status.completed);
        assertThatThrownBy(() -> {
                    chatMessageRepository.saveAndFlush(stale);
                })
                .isInstanceOf(OptimisticLockingFailureException.class);

        ChatMessage finalState = chatMessageRepository.findById(assistantId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(ChatMessage.Status.interrupted);
        assertThat(finalState.getMetadata().path("error").asString()).isEqualTo("server restart");
    }

    @Test
    void accountingReaper_neverSelectsLegitimateTurnsAndAccountsTrulyStaleTurnOnce() throws Exception {
        UUID tenMinuteTurn = persistInFlightTurn("ten-minute-turn");
        UUID maxDurationTurn = persistInFlightTurn("max-duration-turn");
        UUID staleTurn = persistInFlightTurn("stale-turn");
        Instant now = Instant.now();
        setCreatedAt(tenMinuteTurn, now.minus(Duration.ofMinutes(10)));
        setCreatedAt(maxDurationTurn, now.minus(Duration.ofMinutes(180)));
        setCreatedAt(staleTurn, now.minus(Duration.ofMinutes(200)));

        MentorInFlightReaper sweeper = reaperWithAnUnsafeWindow();
        assertThat(sweeper.window()).isEqualTo(Duration.ofMinutes(190));
        sweeper.reap();
        sweeper.reap();

        assertThat(chatMessageRepository.findById(tenMinuteTurn).orElseThrow().getStatus())
                .isEqualTo(ChatMessage.Status.in_flight);
        assertThat(chatMessageRepository.findById(maxDurationTurn).orElseThrow().getStatus())
                .isEqualTo(ChatMessage.Status.in_flight);
        assertThat(chatMessageRepository.findById(staleTurn).orElseThrow().getStatus())
                .isEqualTo(ChatMessage.Status.interrupted);
        assertThat(usageEventRepository.findAll())
                .filteredOn(row -> row.getSourceId().equals(staleTurn))
                .hasSize(1);
    }

    @Test
    @DisplayName("a turn abandoned by a crashed worker is billed for the calls the proxy recorded")
    void accountingReaper_billsACrashedTurnFromItsProxyRecordedUsage() throws Exception {
        UUID crashedTurn = persistInFlightTurn("crashed-turn");
        accumulateProxyCall(crashedTurn, 40_000, 1_000, 250, 5_000, 1_000);
        accumulateProxyCall(crashedTurn, 60_000, 2_000, 250, 5_000, 2_000);
        setCreatedAt(crashedTurn, Instant.now().minus(Duration.ofMinutes(200)));

        reaperWithAnUnsafeWindow().reap();

        assertThat(chatMessageRepository.findById(crashedTurn).orElseThrow().getStatus())
                .isEqualTo(ChatMessage.Status.interrupted);
        var event = usageEventRepository.findAll().stream()
                .filter(row -> row.getSourceId().equals(crashedTurn))
                .findFirst()
                .orElseThrow();
        assertThat(event.getInputTokens()).isEqualTo(100_000);
        assertThat(event.getOutputTokens()).isEqualTo(3_000);
        assertThat(event.getCacheReadTokens()).isEqualTo(10_000);
        assertThat(event.getCacheWriteTokens()).isEqualTo(3_000);
        assertThat(event.getReasoningTokens()).isEqualTo(500);
        assertThat(event.getTotalCalls()).isEqualTo(2);
        assertThat(event.getPricingState()).isEqualTo(PricingState.PRICED);
        assertThat(event.getCostUsd()).isEqualByComparingTo("1.000000");
    }

    @Test
    @DisplayName("a turn with no recorded call stays unverifiable rather than being priced as free")
    void accountingReaper_keepsATurnWithNoRecordedCallUnverifiable() throws Exception {
        UUID silentTurn = persistInFlightTurn("silent-turn");
        setCreatedAt(silentTurn, Instant.now().minus(Duration.ofMinutes(200)));

        reaperWithAnUnsafeWindow().reap();

        var event = usageEventRepository.findAll().stream()
                .filter(row -> row.getSourceId().equals(silentTurn))
                .findFirst()
                .orElseThrow();
        assertThat(event.getPricingState()).isEqualTo(PricingState.UNPRICED);
        assertThat(event.getInputTokens()).isZero();
        assertThat(event.getCostUsd()).isNull();
    }

    private void accumulateProxyCall(
            UUID turnId, long input, long output, long reasoning, long cacheRead, long cacheWrite) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(ignored -> assertThat(chatMessageRepository.accumulateLlmUsage(
                                turnId, input, output, reasoning, cacheRead, cacheWrite))
                        .isEqualTo(1));
    }

    private UUID persistInFlightTurn(String prompt) {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, prompt);
        UUID assistantId = UUID.randomUUID();
        persistence.persistInFlight(thread, prompt, assistantId, null, admittedMentorConfig());
        return assistantId;
    }

    private MentorInFlightReaper reaperWithAnUnsafeWindow() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MentorInFlightReaper.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var reaper =
                    new MentorInFlightReaper(chatMessageRepository, accounting, meterRegistry, Duration.ofMinutes(10));
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("PT10M is unsafe", "using PT3H10M");
            });
            return reaper;
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private void setCreatedAt(UUID messageId, Instant createdAt) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("UPDATE chat_message SET created_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(createdAt));
            statement.setObject(2, messageId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("chk_chat_message_status rejects values outside (in_flight,completed,interrupted)")
    void statusColumnCheckConstraintFires() throws Exception {
        ChatThread thread = persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, "constraint test");
        Assertions.assertThatThrownBy(() -> {
                    try (var conn = dataSource.getConnection();
                            var stmt = conn.prepareStatement(
                                    "INSERT INTO chat_message (id, thread_id, role, parts, status, created_at, version) "
                                            + "VALUES (?, ?, 'ASSISTANT', '[]'::jsonb, ?, now(), 0)")) {
                        stmt.setObject(1, UUID.randomUUID());
                        stmt.setObject(2, thread.getId());
                        // Must fit VARCHAR(16) so we exercise the CHECK constraint, not the
                        // length truncation that fires before the CHECK runs.
                        stmt.setString(3, "in_flite");
                        stmt.executeUpdate();
                    }
                })
                .isInstanceOf(java.sql.SQLException.class)
                // Production ships the explicit `chk_chat_message_status` via Liquibase; ddl-auto=create
                // also generates a Hibernate-implicit `chat_message_status_check`. Either can fire first.
                .satisfies(t -> Assertions.assertThat(t.getMessage())
                        .containsAnyOf("chk_chat_message_status", "chat_message_status_check"));
    }
}
