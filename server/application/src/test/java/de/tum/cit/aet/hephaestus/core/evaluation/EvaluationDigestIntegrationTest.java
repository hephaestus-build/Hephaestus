package de.tum.cit.aet.hephaestus.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.host.DigestSandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestAvailability;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hephaestus.evaluation.controlled-clock.enabled=true",
            "hephaestus.agent.enabled=true",
            "hephaestus.agent.capacity.lease-ttl=PT2H",
            "hephaestus.agent.capacity.lease-renewal=PT1H",
            "hephaestus.sandbox.kubernetes.namespace=evaluation-digest",
            "hephaestus.auth.assurance-enforced=false",
            "hephaestus.integration.slack.signing-secret=hephaestus-evaluation"
        })
@ActiveProfiles("test")
@AutoConfigureWebTestClient(timeout = "30s")
@Import({TestSecurityConfig.class, TestAsyncConfiguration.class})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvaluationDigestIntegrationTest {
    private static final Instant DUE = Instant.parse("2024-07-08T07:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2024-07-08T19:00:00Z");
    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createApplicationDatabase("evaluation_digest");
    private static final MockWebServer COLLECTOR = new MockWebServer();

    @MockitoBean(name = "sandboxKubernetesClient", enforceOverride = true)
    private KubernetesClient sandboxKubernetesClient;

    @MockitoBean
    private WeeklyDigestAvailability availability;

    @MockitoBean
    private WeeklyDigestFacts facts;

    @MockitoBean
    private ConversationAdmission conversationAdmission;

    @MockitoBean
    private DigestAdmission digestAdmission;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebTestClient http;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountDirectory accountDirectory;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DigestSandboxRunKind digestKind;

    @DynamicPropertySource
    static void scenarioProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.liquibase.enabled", () -> false);
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        COLLECTOR.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder().code(204).build();
            }
        });
        COLLECTOR.start();
        registry.add(
                "hephaestus.evaluation.controlled-clock.collector-url",
                () -> COLLECTOR.url("/events").toString());
    }

    @AfterAll
    static void closeCollector() throws IOException {
        COLLECTOR.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldOfferAHistoricalSummerDigestUntilItsCutoffEvenIfAnOldReplicaWroteIt(boolean legacyStimulus) {
        var digest = queueReplayDigest();
        var ref = digest.run();
        long workspaceId = ref.workspaceId();
        if (legacyStimulus) {
            // An old replica queues the digest after this test database has been migrated.
            assertThat(jdbc.sql("""
                    UPDATE conversation_entry e SET payload = payload - 'startBy'
                      FROM conversation_turn t
                     WHERE t.workspace_id = :workspace AND t.run_id = :run
                       AND e.workspace_id = t.workspace_id AND e.turn_id = t.id AND e.kind = 'DIGEST'
                    """)
                            .param("workspace", workspaceId)
                            .param("run", ref.id())
                            .update())
                    .isOne();
        }

        reaper.reap();
        assertThat(runs.find(ref).orElseThrow().getState())
                .as("still inside the contact window")
                .isEqualTo(AgentRunState.QUEUED);
        var kinds = Set.of(AgentRunKind.DIGEST);
        assertThat(runs.dueOf(kinds, List.of(workspaceId), List.of(digest.subject())))
                .extracting(AgentRuns.DueRun::run)
                .containsExactly(ref);
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThat(runs.claimSubject(workspaceId, digest.subject(), kinds))
                    .hasValueSatisfying(lease -> assertThat(lease.runId()).isEqualTo(ref.id()));
        });

        setClock(digest.token(), CUTOFF);
        assertThat(runs.dueOf(kinds, List.of(workspaceId), List.of(digest.subject())))
                .isEmpty();
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThat(runs.claimSubject(workspaceId, digest.subject(), kinds)).isEmpty();
        });
        reaper.reap();
        assertThat(runs.find(ref).orElseThrow().getStopReason()).isEqualTo(AgentRunStopReason.NOT_STARTED);
        post(digest.token(), "/settle", Map.of("timeoutMillis", 5000))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(true);
    }

    @Test
    void shouldKeepAStartedHistoricalDigestLeasedUntilItsScenarioDeadline() {
        var digest = queueReplayDigest();
        long workspaceId = digest.run().workspaceId();
        var config = LlmCatalogTestFixtures.admittedConversationConfig();
        when(conversationAdmission.admit(eq(workspaceId), eq(digest.developer())))
                .thenReturn(config);
        when(digestAdmission.refuses(eq(workspaceId), any())).thenReturn(false);
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), workspaceId, digest.subject(), AgentSandboxKind.CONVERSATION);
        var lease = Objects.requireNonNull(transactions.execute(status -> {
            var claim = runs.claimSubject(workspaceId, digest.subject(), Set.of(AgentRunKind.DIGEST))
                    .orElseThrow();
            assertThat(digestKind.claimed(new SandboxRun(sandbox, claim.fence(), claim.kind(), claim.attempt())))
                    .isTrue();
            return claim;
        }));
        var fence = lease.fence();
        var deadline = DUE.plusSeconds(config.timeoutSeconds()).plus(TurnRunsAdapter.GRACE);
        assertThat(runs.find(digest.run()).orElseThrow().getDeadline()).isEqualTo(deadline);
        setClock(digest.token(), deadline.minusSeconds(1));
        reaper.reap();
        assertThat(runs.find(digest.run()).orElseThrow().getState())
                .as("before the scenario deadline")
                .isEqualTo(AgentRunState.LEASED);
        transactions.executeWithoutResult(
                status -> assertThat(runs.renew(fence)).isPresent());

        setClock(digest.token(), deadline);
        transactions.executeWithoutResult(
                status -> assertThat(runs.renew(fence)).isEmpty());
        reaper.reap();
        assertThat(runs.find(digest.run()).orElseThrow().getStopReason()).isEqualTo(AgentRunStopReason.DEADLINE);
    }

    private record ReplayDigest(AgentRunRef run, String subject, String token, long developer) {}

    private ReplayDigest queueReplayDigest() {
        var administrator = new Account("Evaluation administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        String token = "mock-jwt-admin-" + accounts.save(administrator).getId();
        long developer = EvaluationFixtures.developer(accounts, identities, providers);
        var zone = ZoneId.of("CET");
        accountDirectory.setTimeZone(developer, zone.getId());
        var workspace = workspaces.save(TestEntities.activeWorkspace("evaluation-digest-" + developer));
        long workspaceId = workspace.getId();
        when(availability.memberships(developer))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(
                        workspaceId,
                        workspace.getWorkspaceSlug(),
                        workspace.getDisplayName(),
                        AccountMembershipQuery.Role.MEMBER)));
        when(availability.available(workspaceId, developer)).thenReturn(true);

        var week = WeeklyDigestWindow.previousWeek(DUE, zone);
        when(facts.anythingIn(workspaceId, developer, week)).thenReturn(true);
        when(facts.compose(workspaceId, developer, week))
                .thenReturn(Optional.of(new WeeklyDigestPayload(week, List.of(), List.of())));
        jdbc.sql("""
                INSERT INTO notification_subscription (id, account_id, kind, enabled, enabled_since, unsubscribe_token, version)
                VALUES (gen_random_uuid(), :account, 'WEEKLY_DIGEST', true, :since, gen_random_uuid(), 0)
                """)
                .param("account", developer)
                .param("since", Timestamp.from(week.start()))
                .update();

        setClock(token, DUE.minusSeconds(1));
        post(token, "/settle", Map.of("timeoutMillis", 1000))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(true);

        setClock(token, DUE);
        UUID runId =
                jdbc.sql("""
                SELECT id FROM agent_run WHERE workspace_id = :workspace AND kind = 'DIGEST' AND state = 'QUEUED'
                """).param("workspace", workspaceId).query(UUID.class).single();
        post(token, "/settle", Map.of("timeoutMillis", 50))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(false)
                .jsonPath("$.blocking[?(@.queue == 'agent_run')].id")
                .isEqualTo(List.of(runId.toString()));

        String subject = jdbc.sql("SELECT concurrency_key FROM agent_run WHERE workspace_id = :workspace AND id = :run")
                .param("workspace", workspaceId)
                .param("run", runId)
                .query(String.class)
                .single();
        return new ReplayDigest(new AgentRunRef(runId, workspaceId), subject, token, developer);
    }

    private void setClock(String token, Instant instant) {
        post(token, "/clock", Map.of("instant", instant.toString()))
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec post(String token, String path, Map<String, ?> body) {
        return http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation" + path))
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(body)
                .exchange();
    }
}
