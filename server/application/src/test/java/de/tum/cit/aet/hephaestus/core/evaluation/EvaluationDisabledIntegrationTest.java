package de.tum.cit.aet.hephaestus.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.EvaluationClockStep;
import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvaluationDisabledIntegrationTest extends BaseIntegrationTest {

    private static final MockWebServer COLLECTOR = new MockWebServer();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebTestClient http;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) throws IOException {
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
    void shouldLeaveTheClockAndRecordReadsAloneWhenEvaluationIsDisabled() {
        assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
        assertThat(context.getBeansWithAnnotation(ConditionalOnEvaluation.class))
                .isEmpty();
        assertThat(context.getBeansOfType(ControlledClock.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationProperties.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationObservations.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationWork.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationWork.Workspaces.class)).isEmpty();
        assertThat(context.getBeansOfType(EvaluationClockStep.class)).isEmpty();
        long workspace = workspaces
                .save(TestEntities.activeWorkspace("evaluation-off-" + UUID.randomUUID()))
                .getId();
        long account = EvaluationFixtures.developer(accounts, identities, providers);
        var conversation = conversations.findOrCreateMain(workspace, account);
        var entry = entries.append(workspace, conversation.getId(), ConversationLedgerFixtures.hephaestus("Stored"));
        assertThat(entries.find(workspace, entry.getId())).isPresent();

        request("/clock", Map.of("instant", "2030-01-01T00:00:00Z"));
        request("/settle", Map.of("timeoutMillis", 10));
        assertThat(COLLECTOR.getRequestCount()).isZero();
    }

    private void request(String path, Map<String, ?> body) {
        http.post()
                .uri(ApiWebTestClient.root(context, "/internal/evaluation" + path))
                .headers(h -> h.setBearerAuth("mock-jwt-admin-1"))
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody();
    }
}
