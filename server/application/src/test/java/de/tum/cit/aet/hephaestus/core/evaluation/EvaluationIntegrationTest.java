package de.tum.cit.aet.hephaestus.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.RevocationAwareJwtDecoder;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hephaestus.evaluation.controlled-clock.enabled=true",
            "hephaestus.auth.assurance-enforced=false",
            "hephaestus.integration.slack.signing-secret=hephaestus-evaluation"
        })
@ActiveProfiles("test")
@AutoConfigureWebTestClient(timeout = "30s")
@Import({
    EvaluationIntegrationTest.EventHandlerConfiguration.class,
    TestSecurityConfig.class,
    TestAsyncConfiguration.class
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvaluationIntegrationTest {

    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createApplicationDatabase("controlled_evaluation");
    private static final MockWebServer COLLECTOR = new MockWebServer();
    private static final AtomicReference<@Nullable CountDownLatch> COLLECTOR_ACK = new AtomicReference<>();
    private static final String SUBJECT = "evaluation-event";

    @Autowired
    private WebTestClient http;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private ControlledClock clock;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private RevocationAwareJwtDecoder decoder;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private ProviderEventStore events;

    @Autowired
    private ProviderEventConsumer consumer;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AgentActions actions;

    @Autowired
    private EvaluationRunListener runListener;

    private long adminId;

    @DynamicPropertySource
    static void configureScenario(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.enabled", () -> false);
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        COLLECTOR.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                var ack = COLLECTOR_ACK.get();
                int status = ack == null || ack.await(10, TimeUnit.SECONDS) ? 204 : 503;
                return new MockResponse.Builder().code(status).build();
            }
        });
        try {
            COLLECTOR.start();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        registry.add(
                "hephaestus.evaluation.controlled-clock.collector-url",
                () -> COLLECTOR.url("/events").toString());
    }

    @AfterAll
    static void closeCollector() throws IOException {
        COLLECTOR.close();
    }

    @BeforeEach
    void createAdministrator() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
                .isEqualTo("controlled_evaluation");
        var administrator = new Account("Evaluation administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        adminId = Objects.requireNonNull(accounts.save(administrator).getId());
    }

    @Test
    void shouldNameTheProviderEventAtTheDeadlineAndSettleAfterItsRunEnds() throws Exception {
        long workspace = newWorkspace();
        long item = newItem(workspace);
        String delivery = UUID.randomUUID().toString();
        assertThat(events.store(List.of(new ProviderEventStore.Arrival(
                        workspace,
                        "github",
                        "github.evaluation.repo.scenario",
                        delivery,
                        Map.of("workspace", Long.toString(workspace), "item", Long.toString(item)),
                        new byte[0]))))
                .isEqualTo(1);
        long id = jdbc.sql("SELECT id FROM provider_event WHERE delivery_key = :delivery")
                .param("delivery", delivery)
                .query(Long.class)
                .single();

        settle(50)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(false)
                .jsonPath("$.blocking[0].id")
                .isEqualTo(Long.toString(id))
                .jsonPath("$.blocking[0].queue")
                .isEqualTo("provider_event")
                .jsonPath("$.blocking[0].state")
                .isEqualTo("QUEUED");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var awaiting = CompletableFuture.supplyAsync(() -> settle(10_000), executor);
            assertThat(consumer.claimAndHandle()).isTrue();
            assertThat(jdbc.sql("SELECT state FROM provider_event WHERE id = :id")
                            .param("id", id)
                            .query(String.class)
                            .single())
                    .isEqualTo("DONE");
            try {
                assertThat(runListener.awaitStart()).isTrue();
                UUID publication = jdbc.sql("SELECT id FROM event_publication WHERE listener_id LIKE '%queueRun%'")
                        .query(UUID.class)
                        .single();
                settle(50)
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.settled")
                        .isEqualTo(false)
                        .jsonPath("$.blocking[?(@.queue == 'event_publication')].id")
                        .isEqualTo(List.of(publication.toString()));
            } finally {
                runListener.release();
            }

            AgentRunLease lease = await().atMost(5, TimeUnit.SECONDS)
                    .until(
                            () -> Objects.requireNonNull(transactions.execute(
                                    tx -> runs.claimSubject(workspace, SUBJECT, Set.of(AgentRunKind.REVIEW)))),
                            Optional::isPresent)
                    .orElseThrow();

            settle(50)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.settled")
                    .isEqualTo(false)
                    .jsonPath("$.blocking[0].id")
                    .isEqualTo(lease.fence().runId().toString())
                    .jsonPath("$.blocking[0].state")
                    .isEqualTo("LEASED");
            assertThat(awaiting).isNotDone();

            transactions.executeWithoutResult(tx -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));

            awaiting.get(12, TimeUnit.SECONDS)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.settled")
                    .isEqualTo(true);
        }
    }

    @Test
    void shouldReportAProviderEventWithoutAWorkspace() {
        String delivery = UUID.randomUUID().toString();
        assertThat(events.store(List.of(new ProviderEventStore.Arrival(
                        null, "github", "github.evaluation.installation.scenario", delivery, Map.of(), new byte[0]))))
                .isEqualTo(1);
        long id = jdbc.sql("SELECT id FROM provider_event WHERE delivery_key = :delivery")
                .param("delivery", delivery)
                .query(Long.class)
                .single();
        try {
            settle(50)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.blocking[?(@.queue == 'provider_event')].id")
                    .isEqualTo(List.of(Long.toString(id)));
        } finally {
            jdbc.sql("DELETE FROM provider_event WHERE id = :id")
                    .param("id", id)
                    .update();
        }
    }

    @Test
    void shouldReportAnInstanceActionWithoutAWorkspace() {
        String key = "evaluation:" + UUID.randomUUID();
        UUID id = actions.enqueue(new ActionRequest(
                ActionKind.EMAIL, null, key, key, JsonNodeFactory.instance.objectNode(), null, 3, null, null));
        try {
            settle(50)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.blocking[?(@.queue == 'agent_action')].id")
                    .isEqualTo(List.of(id.toString()));
        } finally {
            jdbc.sql("DELETE FROM agent_action WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void shouldOnlySendTheRecordIdWhenTheRecordContainsACanary() throws Exception {
        long workspace = newWorkspace();
        long account = EvaluationFixtures.developer(accounts, identities, providers);
        var conversation = conversations.findOrCreateMain(workspace, account);
        String canary = "private-" + UUID.randomUUID();
        var record = entries.append(workspace, conversation.getId(), ConversationLedgerFixtures.hephaestus(canary));

        var ack = new CountDownLatch(1);
        COLLECTOR_ACK.set(ack);
        try {
            assertThat(entries.find(workspace, record.getId()))
                    .hasValueSatisfying(read -> assertThat(read.getText()).isEqualTo(canary));
            settle(50)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.settled")
                    .isEqualTo(false)
                    .jsonPath("$.blocking[0].queue")
                    .isEqualTo("observer")
                    .jsonPath("$.blocking[0].state")
                    .isEqualTo("UNDELIVERED");
        } finally {
            ack.countDown();
            COLLECTOR_ACK.set(null);
        }
        settle(5_000).expectStatus().isOk().expectBody().jsonPath("$.watermark").isNumber();

        var bodies = new ArrayList<String>();
        int received = COLLECTOR.getRequestCount();
        for (int i = 0; i < received; i++) {
            var request = Objects.requireNonNull(COLLECTOR.takeRequest(1, TimeUnit.SECONDS));
            bodies.add(Objects.requireNonNull(request.getBody()).utf8());
        }
        assertThat(bodies)
                .noneMatch(body -> body.contains(canary))
                .anyMatch(body -> body.contains(record.getId().toString()) && body.contains("RecordRead"));
    }

    @Test
    void shouldKeepTheAdministratorTokenUsableWhenTheClockAdvances() {
        long memberId = EvaluationFixtures.developer(accounts, identities, providers);
        var token = issuer.issue(adminId, TokenConstraints.session(null, null), null);
        String instant = "2035-03-04T05:06:07Z";
        http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation/clock"))
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + memberId))
                .bodyValue(Map.of("instant", instant))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody();
        http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation/clock"))
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + adminId))
                .bodyValue(Map.of("instant", instant))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.instant")
                .isEqualTo(instant);
        assertThat(clock.instant()).isEqualTo(Instant.parse(instant));
        assertThat(decoder.decode(token.value()).getSubject()).isEqualTo(Long.toString(adminId));
        assertThat(jdbc.sql("SELECT clock_timestamp() < CAST(:instant AS timestamptz)")
                        .param("instant", instant)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void shouldProcessLapsedItemEventsWhenTheClockAdvancesWithoutAnAgentRuntime() {
        long workspace = newWorkspace();
        long item = newItem(workspace);
        Instant recordedAt = clock.instant();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO item_event (id, workspace_id, item_id, name, item_version, occurred_at,
                  found_by, state, reason, decided_at, retry_at)
                VALUES (:id, :workspace, :item, 'scm.pull_request.evaluation', 'fixture-version',
                  CAST(:recordedAt AS timestamptz), 'WEBHOOK', 'WAITING', 'MODEL_UNAVAILABLE',
                  CAST(:recordedAt AS timestamptz), CAST(:retryAt AS timestamptz))
                """)
                .param("id", id)
                .param("workspace", workspace)
                .param("item", item)
                .param("recordedAt", recordedAt.toString())
                .param("retryAt", recordedAt.plus(Duration.ofHours(1)).toString())
                .update();

        setClock(recordedAt.plus(Duration.ofHours(2)));
        settle(5_000)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(true)
                .jsonPath("$.future[?(@.queue == 'item_event')].id")
                .isEqualTo(List.of(id.toString()));

        setClock(recordedAt.plus(Duration.ofDays(8)));

        assertThat(jdbc.sql("SELECT state FROM item_event WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("EXPIRED");
        settle(5_000).expectStatus().isOk().expectBody().jsonPath("$.settled").isEqualTo(true);
    }

    private void setClock(Instant instant) {
        http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation/clock"))
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + adminId))
                .bodyValue(Map.of("instant", instant.toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private long newItem(long workspace) {
        return jdbc.sql("""
                    INSERT INTO item (workspace_id, kind, source_id)
                    VALUES (:workspace, 'scm.pull_request', :workspace) RETURNING id
                    """).param("workspace", workspace).query(Long.class).single();
    }

    private long newWorkspace() {
        return workspaces
                .save(TestEntities.activeWorkspace("evaluation-" + UUID.randomUUID()))
                .getId();
    }

    private WebTestClient.ResponseSpec settle(long timeoutMillis) {
        return http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation/settle"))
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + adminId))
                .bodyValue(Map.of("timeoutMillis", timeoutMillis))
                .exchange();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventHandlerConfiguration {

        @Bean
        IntegrationMessageHandler evaluationEventHandler(ItemEventService events, ControlledClock clock) {
            return new IntegrationMessageHandler() {
                @Override
                public EventTypeKey key() {
                    return new EventTypeKey(IntegrationKind.GITHUB, "repository.scenario");
                }

                @Override
                public void onMessage(ProviderEvent event) {
                    long workspace = Long.parseLong(Objects.requireNonNull(event.header("workspace")));
                    long item = Long.parseLong(Objects.requireNonNull(event.header("item")));
                    var _ = events.record(new ItemEventInput(
                            workspace,
                            item,
                            new ItemEventName("scm.pull_request.evaluation"),
                            "fixture-version",
                            clock.instant(),
                            FoundBy.WEBHOOK,
                            null,
                            null));
                }
            };
        }

        @Bean(destroyMethod = "close")
        ExecutorService evaluationRunExecutor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }

        @Bean
        EvaluationRunListener evaluationRunListener(AgentRuns runs) {
            return new EvaluationRunListener(runs);
        }
    }

    static class EvaluationRunListener {
        private final AgentRuns runs;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        EvaluationRunListener(AgentRuns runs) {
            this.runs = runs;
        }

        public boolean awaitStart() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        public void release() {
            release.countDown();
        }

        @Async("evaluationRunExecutor")
        @ApplicationModuleListener
        public void queueRun(ItemEventRecorded event) throws InterruptedException {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("The test did not release the listener");
            var _ = runs.enqueue(event.workspaceId(), AgentRunKind.REVIEW, AgentRunBand.BATCH, SUBJECT, null);
        }
    }
}
