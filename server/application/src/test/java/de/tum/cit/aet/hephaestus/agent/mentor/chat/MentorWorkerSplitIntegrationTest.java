package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.DockerClient;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.usage.*;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackStreamingMentorChannel;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.*;
import de.tum.cit.aet.hephaestus.workspace.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/** Server has no worker role; a real authenticated socket carries both web and Slack turns to a worker. */
@TestPropertySource(properties = "hephaestus.runtime.worker.enabled=false")
class MentorWorkerSplitIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    WorkerJwtIssuer issuer;

    @Autowired
    WorkerSessionRegistry workers;

    @Autowired
    MentorChatService service;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    MentorTurnLock turnLock;

    @Autowired
    ChatThreadRepository threads;

    @Autowired
    WebTestClient web;

    @Autowired
    SlackMessageService slack;

    @MockitoBean
    StubMentorChatStarter starter;

    @MockitoBean
    MemberAiRoutingAdapter routing;

    @MockitoBean
    LlmAdmissionService admission;

    @MockitoBean
    WorkspaceContextBuilder contextBuilder;

    private User developer;
    private Workspace workspace;

    @BeforeEach
    void configureTurn() {
        developer = persistUser("mentor");
        workspace = createWorkspace("worker-mentor", "Worker Mentor", "worker-mentor", AccountType.ORG, developer);
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        var binding = new WorkspaceAgentBinding();
        binding.setPurpose(AgentPurpose.MENTOR);
        binding.setEnabled(true);
        binding.setTimeoutSeconds(600);
        when(routing.binding(eq(workspace.getId()), eq(AgentPurpose.MENTOR), any()))
                .thenReturn(Optional.of(binding));
        when(admission.admit(any(WorkspaceAgentBinding.class)))
                .thenReturn(new AdmittedLlmModel(
                        new ResolvedLlmModel(
                                "https://upstream.example/v1", "openai-completions", "test-model", null, null, null),
                        new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 1L, 2L, workspace.getId()),
                        new LlmPriceSnapshot(
                                FundingSource.INSTANCE, PricingState.NO_CHARGE, null, null, null, null, null, null)));
        when(contextBuilder.build(any()))
                .thenReturn(Map.of("inputs/context/observations_history.json", "[]".getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> {
                    service.start(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(starter)
                .start(any(), any());
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("stream-ts");
        doNothing().when(slack).stopStream(anyLong(), anyString(), anyString(), any());
        doNothing().when(slack).appendStream(anyLong(), anyString(), anyString(), anyString());
    }

    private TestMentorWorker worker() throws Exception {
        String id = "mentor-worker-" + UUID.randomUUID();
        var worker = new TestMentorWorker(
                URI.create("ws://localhost:" + port + "/api/workers/connect"),
                id,
                issuer.issue(id).token(),
                mapper);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                workers.findByWorkerId(id).orElseThrow().lastCapacity())
                        .isNotNull());
        return worker;
    }

    @Test
    @WithMentorUser
    void webAndSlackStreamWithoutAServerDockerClient() throws Exception {
        assertThat(context.getBeansOfType(DockerClient.class)).isEmpty();
        try (var worker = worker()) {
            UUID webThread = UUID.randomUUID();
            String stream = webTurn(webThread);
            assertThat(stream).contains("Hello ", "from the worker.", "finish");
            assertCompleted(webThread);
            UUID slackThread = UUID.randomUUID();
            var channel = new SlackStreamingMentorChannel(slack, workspace.getId(), "D123", "thread-ts");
            service.run(
                    MentorTurnRequest.slackDm(workspace.getId(), slackThread, "Hi Heph", UUID.randomUUID()),
                    channel,
                    developer.getId());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertCompleted(slackThread));
            verify(slack, timeout(5000))
                    .startStream(
                            eq(workspace.getId()), eq("D123"), eq("thread-ts"), contains("Hello from the worker."));
            verify(slack, timeout(5000)).stopStream(eq(workspace.getId()), eq("D123"), eq("stream-ts"), any());
            assertThat(worker.sandboxes).hasSize(1);
            assertThat(worker.sandboxes.getFirst().contextReceived).isTrue();
            assertThat(worker.capacity.snapshot().inFlightMentor()).isEqualTo(1);
            assertThat(threads.findById(webThread).orElseThrow().getSessionJsonl())
                    .isEqualTo(TestMentorWorker.SESSION_JSONL.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @WithMentorUser
    void noSpareCapacityReturnsRetryableBusyThroughTheWebStream() throws Exception {
        try (var worker = worker()) {
            worker.send(new de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport(1, 2, 0, 2, 1, 0));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> workers.sessions().stream()
                            .allMatch(session -> session.lastCapacity() != null
                                    && session.lastCapacity().spareMentor() == 0));
            assertThat(webTurn(UUID.randomUUID()))
                    .contains("Heph is busy. Please try again.")
                    .doesNotContain("\"type\":\"finish\"");
            assertThat(worker.sandboxes).isEmpty();
        }
    }

    @Test
    @WithMentorUser
    void drainEndsAWebStreamAndTheNextTurnRestoresOnAnotherWorker() throws Exception {
        UUID thread = UUID.randomUUID();
        try (var first = worker();
                var second = worker()) {
            assertThat(webTurn(thread)).contains("finish");
            await().atMost(Duration.ofSeconds(10)).until(() -> turnLock.activeKeys() == 0);
            var owning = first.sandboxes.isEmpty() ? second : first;
            var replacement = owning == first ? second : first;
            owning.sandboxes.getFirst().promptReceived = false;
            owning.sandboxes.getFirst().holdPrompt = true;
            var requestBody = Map.of(
                    "id",
                    thread,
                    "message",
                    Map.of(
                            "id",
                            UUID.randomUUID(),
                            "role",
                            "user",
                            "parts",
                            List.of(Map.of("type", "text", "text", "Wait while I drain the worker"))));
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/workspaces/"
                            + workspace.getWorkspaceSlug() + "/mentor/chat"))
                    .header("Authorization", "Bearer " + TestAuthUtils.getCurrentUserToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                    .build();
            var response = java.net.http.HttpClient.newHttpClient()
                    .sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                if (response.isDone()) {
                    assertThat(response.get().body())
                            .as("turn ended before the held prompt reached its worker")
                            .isEmpty();
                }
                assertThat(owning.sandboxes.getFirst().promptReceived).isTrue();
            });
            owning.drain();
            var terminal = response.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(terminal.statusCode()).isEqualTo(200);
            assertThat(terminal.body())
                    .contains("lost in transit")
                    .contains("\"type\":\"error\"")
                    .doesNotContain("\"type\":\"finish\"");
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> assertThat(messages.existsByThread_IdAndStatus(thread, ChatMessage.Status.in_flight))
                                    .isFalse());
            assertThat(webTurn(thread)).contains("finish");
            assertThat(replacement.sandboxes).hasSize(1);
            assertThat(replacement.sandboxes.getFirst().restoredSession).isEqualTo(TestMentorWorker.SESSION_JSONL);
            assertThat(owning.capacity.snapshot().inFlightMentor()).isZero();
        }
    }

    private String webTurn(UUID thread) {
        var result = web.post()
                .uri("/workspaces/{slug}/mentor/chat", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "id",
                        thread,
                        "message",
                        Map.of(
                                "id",
                                UUID.randomUUID(),
                                "role",
                                "user",
                                "parts",
                                List.of(Map.of("type", "text", "text", "Hi Heph")))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(result);
    }

    private void assertCompleted(UUID thread) {
        var created = messages.findContextMessages(workspace.getId(), developer.getId(), thread, null);
        assertThat(created).anySatisfy(message -> {
            assertThat(message.getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
            assertThat(message.getStatus()).isEqualTo(ChatMessage.Status.completed);
            assertThat(message.getParts().toString()).contains("Hello from the worker.");
        });
    }
}
