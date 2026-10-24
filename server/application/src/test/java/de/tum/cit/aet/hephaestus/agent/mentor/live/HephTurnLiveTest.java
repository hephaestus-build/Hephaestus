package de.tum.cit.aet.hephaestus.agent.mentor.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelService;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.UpdateLlmModelPriceRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.DockerClientFactory;

/**
 * One Heph turn end to end: the app's ask endpoint, the worker's claim, the Heph host container from the agent image,
 * the LLM proxy and a real model, and the answer written back. Everything else tests Heph with a fake host.
 *
 * <p>Needs Docker, the agent image ({@code HEPHAESTUS_AGENT_IMAGE_REFERENCE}, built from {@code docker/agents/pi}),
 * and an OpenAI-compatible model ({@code HEPHAESTUS_LIVE_LLM_*}). The host reaches the worker's sandbox gateway at
 * {@code host.docker.internal}; on a host whose firewall admits containers to one port only, name it in
 * {@code SANDBOX_API_PORT}.
 */
@LiveLlmTest
@LiveDockerTest
class HephTurnLiveTest extends AbstractWorkspaceIntegrationTest {

    /** Signs in as the user with login {@code mentor}. */
    private static final String DEVELOPER = "mock-jwt-token-for-mentor-user";

    /** The host starts on the first claim and may pull nothing; the model answers a one-line question. */
    private static final Duration ANSWER_DEADLINE = Duration.ofMinutes(4);

    @Autowired
    private WebTestClient client;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelService modelService;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void worker(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.runtime.worker.enabled", () -> "true");
        // Port 0 binds a free port; the worker hands the bound one to the host.
        registry.add("hephaestus.sandbox.gateway.port", () -> System.getenv().getOrDefault("SANDBOX_API_PORT", "0"));
        registry.add(
                "hephaestus.agent.image.reference",
                () -> System.getenv()
                        .getOrDefault("HEPHAESTUS_AGENT_IMAGE_REFERENCE", "ghcr.io/hephaestus-build/agent-pi:dev"));
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void shouldAnswerAQuestionThroughTheHephHostAndAModel() throws InterruptedException {
        LiveLlmCredentials llm = LiveLlmCredentials.fromEnv();
        var developer = persistUser("mentor");
        Workspace workspace =
                createWorkspace("heph-live", "Heph live", "heph-live", AccountType.ORG, persistUser("heph-live-owner"));
        ensureMembership(workspace, developer, Membership.WorkspaceRole.MEMBER);
        LlmConnection connection = LlmCatalogTestFixtures.connection("heph-live");
        connection.setBaseUrl(llm.baseUrl());
        connection.setApiKey(llm.apiKey());
        connection = connections.save(connection);
        LlmModel model = LlmCatalogTestFixtures.model(connection, "heph-live", llm.model());
        model.setContextWindow(32_768);
        model.setMaxOutputTokens(4_096);
        model = models.save(model);
        modelService.updatePrice(
                model.getId(),
                new UpdateLlmModelPriceRequestDTO(PricingMode.NO_CHARGE, null, null, null, null, "Live test model"));
        bindings.save(LlmCatalogTestFixtures.mentorBinding(workspace, model));

        UUID requestId = UUID.randomUUID();
        client.post()
                .uri("/workspaces/heph-live/mentor/turns")
                .headers(headers -> headers.setBearerAuth(DEVELOPER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "requestId", requestId, "text", "In one sentence: why do small pull requests help reviewers?"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        UUID turnId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM mentor_turn WHERE request_id = ?", UUID.class, requestId.toString()));

        String status = awaitEnd(turnId);

        String error =
                jdbc.queryForObject("SELECT coalesce(error, '') FROM mentor_turn WHERE id = ?", String.class, turnId);
        assertThat(status).as("turn %s ended: %s", turnId, error).isEqualTo("DONE");
        Integer calls = jdbc.queryForObject("SELECT llm_calls FROM mentor_turn WHERE id = ?", Integer.class, turnId);
        assertThat(calls).as("the proxy counted the model call").isNotNull().isPositive();
        List<@Nullable String> answers = jdbc.queryForList(
                "SELECT text FROM mentor_entry WHERE turn_id = ? AND kind = 'HEPH' AND redacted_at IS NULL",
                String.class,
                turnId);
        assertThat(answers).anySatisfy(text -> assertThat(text).isNotBlank());
    }

    private String awaitEnd(UUID turnId) throws InterruptedException {
        Instant deadline = Instant.now().plus(ANSWER_DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            String status = Objects.requireNonNullElse(
                    jdbc.queryForObject("SELECT status FROM mentor_turn WHERE id = ?", String.class, turnId), "");
            if (List.of("DONE", "FAILED", "STOPPED").contains(status)) {
                return status;
            }
            Thread.sleep(1_000);
        }
        return "UNFINISHED";
    }
}
