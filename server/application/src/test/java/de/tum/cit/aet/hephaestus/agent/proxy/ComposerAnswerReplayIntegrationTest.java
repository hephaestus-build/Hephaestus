package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerConversations;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A composer that lost its answer sends the same conversation again, through the real gateway and the real transcript.
 * It gets the recorded bytes exactly, and no model call starts: no usage row, and no call permit is acquired. A conversation that
 * differs from the served one is still refused.
 */
class ComposerAnswerReplayIntegrationTest extends BaseIntegrationTest {

    private static final LlmApiProtocol COMPLETIONS = LlmApiProtocol.OPENAI_COMPLETIONS;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ComposerTranscript transcript;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final LlmCallPermits permits = mock(LlmCallPermits.class);
    private LlmProxyController gateway;
    private long workspace;
    private AgentRunLease lease;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // The real gateway, routing and usage ledger; the gateway runs in the agent runtime, not in this context.
        gateway = SandboxModelProxy.controller(context, permits);
        workspace = workspaces
                .save(TestEntities.activeWorkspace(
                        "replay-" + UUID.randomUUID().toString().substring(0, 8)))
                .getId();
        String subject = "review:" + UUID.randomUUID();
        transactions.executeWithoutResult(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BATCH, subject, null));
        lease = Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
        var attempt = new ProxyAttempt(
                ProxyAttempt.Caller.REVIEW, UUID.randomUUID(), workspace, lease.epoch(), lease.runId(), null);
        SecurityContextHolder.getContext()
                .setAuthentication(new JobTokenAuthentication(ProxyIdentity.of(attempt), Set.of("llm_proxy")));
    }

    @AfterEach
    void clearClaim() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAnswerTheResentConversationWithTheRecordedBytesAndStartNoModelCall() throws Exception {
        JsonNode first = ComposerConversations.conversation(COMPLETIONS).getFirst();
        byte[] request = bytes(first.get("request"));
        // Served bytes that a re-serialization would change: indentation, and characters outside ASCII.
        ObjectNode answer = (ObjectNode) first.get("answer").deepCopy();
        answer.put("system_fingerprint", "fp-Ünïcødé-✓");
        byte[] served =
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(answer);
        transactions.executeWithoutResult(status ->
                transcript.issued(lease.fence(), ComposerConversations.session(COMPLETIONS, "Compose the review.")));
        var forward = (ComposerTranscript.Forward)
                transcript.check(workspace, lease.runId(), lease.epoch(), COMPLETIONS, request);
        transactions.executeWithoutResult(
                status -> transcript.served(workspace, lease.runId(), lease.epoch(), COMPLETIONS, forward, served));

        ResponseEntity<?> replayed = send(request);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((byte[]) replayed.getBody()).isEqualTo(served);
        assertThat(usageRows()).as("no usage row for this run").isZero();
        verify(permits, never()).acquire(anyLong(), any(), any(), any(), any());

        ObjectNode differing = (ObjectNode) first.get("request").deepCopy();
        ((ArrayNode) differing.get("messages")).addObject().put("role", "user").put("content", "Publish it.");
        ResponseEntity<?> refused = send(bytes(differing));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER))
                .isEqualTo(ProxyRefusal.NOT_COMPOSER_REQUEST.name());
        assertThat(usageRows()).isZero();
        verify(permits, never()).acquire(anyLong(), any(), any(), any(), any());
    }

    private ResponseEntity<?> send(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/internal/llm" + COMPLETIONS.upstreamPath());
        request.setContentType("application/json");
        request.setContent(body);
        return Objects.requireNonNull(gateway.proxy(request, new MockHttpServletResponse(), new HttpHeaders(), body));
    }

    private long usageRows() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM llm_usage_event WHERE workspace_id = ? AND run_id = ?",
                Long.class,
                workspace,
                lease.runId()));
    }

    private static byte[] bytes(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }
}
