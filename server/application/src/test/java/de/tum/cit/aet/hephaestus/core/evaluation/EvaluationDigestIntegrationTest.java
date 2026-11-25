package de.tum.cit.aet.hephaestus.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestAvailability;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
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
import java.util.Optional;
import java.util.UUID;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hephaestus.evaluation.controlled-clock.enabled=true",
            "hephaestus.agent.enabled=true",
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
    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createApplicationDatabase("evaluation_digest");
    private static final MockWebServer COLLECTOR = new MockWebServer();

    @MockitoBean(name = "sandboxKubernetesClient", enforceOverride = true)
    private KubernetesClient sandboxKubernetesClient;

    @MockitoBean
    private WeeklyDigestAvailability availability;

    @MockitoBean
    private WeeklyDigestFacts facts;

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
    private ConversationTurnService turns;

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

    @Test
    void shouldQueueDueDigestBeforeTheBarrierCanSettle() {
        var administrator = new Account("Evaluation administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        String token = "mock-jwt-admin-" + accounts.save(administrator).getId();
        long developer = EvaluationFixtures.developer(accounts, identities, providers);
        var zone = ZoneId.of("UTC");
        accountDirectory.setTimeZone(developer, zone.getId());
        var workspace = workspaces.save(TestEntities.activeWorkspace("evaluation-digest"));
        long workspaceId = workspace.getId();
        when(availability.memberships(developer))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(
                        workspaceId,
                        workspace.getWorkspaceSlug(),
                        workspace.getDisplayName(),
                        AccountMembershipQuery.Role.MEMBER)));
        when(availability.available(workspaceId, developer)).thenReturn(true);

        Instant due = Instant.parse("2030-01-07T09:00:00Z");
        var week = WeeklyDigestWindow.previousWeek(due, zone);
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

        post(token, "/clock", Map.of("instant", due.minusSeconds(1).toString()))
                .expectStatus()
                .isOk();
        post(token, "/settle", Map.of("timeoutMillis", 1000))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(true);

        post(token, "/clock", Map.of("instant", due.toString())).expectStatus().isOk();
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

        UUID turnId = jdbc.sql("SELECT id FROM conversation_turn WHERE workspace_id = :workspace AND run_id = :run")
                .param("workspace", workspaceId)
                .param("run", runId)
                .query(UUID.class)
                .single();
        assertThat(turns.stop(workspaceId, turnId).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        post(token, "/settle", Map.of("timeoutMillis", 5000))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.settled")
                .isEqualTo(true);
    }

    private WebTestClient.ResponseSpec post(String token, String path, Map<String, ?> body) {
        return http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation" + path))
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(body)
                .exchange();
    }
}
