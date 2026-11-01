package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.agent.AgentControllerAdvice;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.proxy.SandboxGatewayFixtures;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import de.tum.cit.aet.hephaestus.core.exception.GlobalControllerAdvice;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnAttempts;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoints that give a conversation attempt its context folder: its tools Job, its documents and the renewal of
 * its lease, behind the gateway's own credential filter. The tools Jobs are the SPI's; this test checks what the
 * controller asks of them.
 */
class ConversationToolsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TURN_PATH = "/internal/llm/turn/{turnId}/";
    private static final AttemptSigningKey KEY = AttemptSigningKey.generate();

    private final AttemptTokenIssuer issuer = new AttemptTokenIssuer(KEY);
    private final AttemptTokenVerifier verifier = new AttemptTokenVerifier(KEY, new SimpleMeterRegistry());
    private final ToolsJobs tools = mock(ToolsJobs.class);

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;
    private MentorTurn turn;
    private MockMvc gateway;

    @BeforeEach
    void startATurn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User developer = persistUser("heph-tools-" + suffix);
        workspace = createWorkspace(
                "heph-tools-" + suffix, "Hephaestus Tools", "heph-tools-org", AccountType.ORG, developer);
        MentorConversation conversation = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        turn = MentorTurnStarts.askAndStart(
                turns, metrics, workspace.getId(), conversation.getId(), "What does my repository say?");
        var assignments = new ConversationAssignments(
                mock(MentorTurnStarter.class),
                turns,
                beans.getBean(MentorTurnAttempts.class),
                issuer,
                Clock.systemUTC(),
                Duration.ofMinutes(5));
        var controller = new ConversationToolsController(
                assignments,
                new ConversationFolder(
                        assignments,
                        ConversationTurnFixtures.contexts(beans),
                        ConversationTurnFixtures.documents(beans),
                        tools,
                        Clock.systemUTC()),
                mapper);
        var servletApi = new SecurityContextHolderAwareRequestFilter();
        servletApi.afterPropertiesSet();
        gateway = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentControllerAdvice(), new GlobalControllerAdvice())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(
                        SandboxGatewayFixtures.credentialFilter(jobRepository, turnRepository, verifier), servletApi)
                .build();
    }

    @Test
    void shouldGiveTheAttemptAToolsJobBoundToItsLeaseAndCurrentContext() throws Exception {
        UUID generation = Objects.requireNonNull(reloaded().getContextGeneration());
        UUID leaseId = Objects.requireNonNull(turn.getLeaseId());
        when(tools.acquire(
                        eq(workspace.getId()),
                        eq(turn.getId()),
                        eq(turn.getAttempt()),
                        eq(leaseId),
                        eq(generation),
                        any()))
                .thenReturn(new ToolsJobs.Endpoint("10.42.0.9", 7070, "n".repeat(43)));

        gateway.perform(post(TURN_PATH + "tools", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("10.42.0.9"))
                .andExpect(jsonPath("$.port").value(7070))
                .andExpect(jsonPath("$.generation").value(generation.toString()));
    }

    @Test
    void shouldKeepTheToolsJobOfALiveAttemptOnlyUntilItLosesItsLease() {
        var revocations = new ConversationToolsRevocations(
                tools, beans.getBean(MentorTurnAttempts.class), mock(MentorLedgerNotifications.class));
        try {
            ToolsJobs.Binding live = new ToolsJobs.Binding(
                    workspace.getId(),
                    turn.getId(),
                    turn.getAttempt(),
                    Objects.requireNonNull(turn.getLeaseId()),
                    Objects.requireNonNull(reloaded().getContextGeneration()));

            revocations.contextChanged(new MentorLedgerEvents.ContextChanged(workspace.getId()));
            verify(tools, timeout(5_000)).retainOnly(argThat(kept -> kept.contains(live)));

            loseTheLease();
            revocations.resync();
            verify(tools, timeout(5_000)).retainOnly(argThat(kept -> !kept.contains(live)));
        } finally {
            revocations.stop();
        }
    }

    @Test
    void shouldBindANewToolsJobToTheNewContextOnceTheWorkspaceContextChanged() throws Exception {
        UUID rotated = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> turnRepository.changeContextGeneration(workspace.getId(), rotated));
        when(tools.acquire(anyLong(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new ToolsJobs.Endpoint("10.42.0.9", 7070, "n".repeat(43)));

        gateway.perform(post(TURN_PATH + "tools", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(rotated.toString()));

        verify(tools)
                .acquire(eq(workspace.getId()), eq(turn.getId()), eq(turn.getAttempt()), any(), eq(rotated), any());
    }

    @Test
    void shouldTellTheLoopThatTheToolsAreBusyWhenTheQuotaIsFull() throws Exception {
        when(tools.acquire(anyLong(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new SandboxCapacityException(
                        "the tools quota is full", SandboxCapacityException.Reason.QUOTA_EXCEEDED));

        gateway.perform(post(TURN_PATH + "tools", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldStartNoToolsJobForAnAttemptThatLostItsLease() throws Exception {
        String credential = bearer();
        loseTheLease();

        gateway.perform(post(TURN_PATH + "tools", turn.getId()).header(HttpHeaders.AUTHORIZATION, credential))
                .andExpect(status().isConflict());

        verify(tools, never()).acquire(anyLong(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void shouldDiscardExactlyTheGenerationTheLoopNames() throws Exception {
        UUID old = UUID.randomUUID();

        gateway.perform(delete(TURN_PATH + "tools/{generation}", turn.getId(), old)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNoContent());

        verify(tools)
                .discard(
                        workspace.getId(),
                        turn.getId(),
                        turn.getAttempt(),
                        Objects.requireNonNull(turn.getLeaseId()),
                        old);
    }

    @Test
    void shouldServeTheDocumentsArchiveWithItsDigestAndContextGeneration() throws Exception {
        var response = gateway.perform(
                        get(TURN_PATH + "documents/archive", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "X-Context-Generation",
                                Objects.requireNonNull(reloaded().getContextGeneration())
                                        .toString()))
                .andReturn()
                .getResponse();
        byte[] archive = response.getContentAsByteArray();

        assertThat(archive).isNotEmpty();
        assertThat(response.getHeader("X-Archive-Sha256"))
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
    }

    @Test
    void shouldReadTheFolderIndexAndAnswerNotFoundForADocumentTheFolderLacks() throws Exception {
        gateway.perform(post(TURN_PATH + "documents/read", turn.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"INDEX.md\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").isNotEmpty());
        gateway.perform(post(TURN_PATH + "documents/read", turn.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"inputs/context/nothing-here.md\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRenewTheLeaseAndSayStoppedOnceTheDeveloperStoppedTheTurn() throws Exception {
        Instant before = Objects.requireNonNull(reloaded().getLeasedUntil());

        gateway.perform(post(TURN_PATH + "renew", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        assertThat(reloaded().getLeasedUntil()).isAfterOrEqualTo(before);

        turns.stop(workspace.getId(), turn.getId());

        gateway.perform(post(TURN_PATH + "renew", turn.getId()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    void shouldRenewNoLeasePastTheAttemptsDeadline() throws Exception {
        var late = new ConversationAssignments(
                mock(MentorTurnStarter.class),
                turns,
                beans.getBean(MentorTurnAttempts.class),
                issuer,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(1)),
                Duration.ofMinutes(5));
        Instant leased = Objects.requireNonNull(reloaded().getLeasedUntil());

        assertThatThrownBy(() -> late.renew(verifier.verify(bearer().substring(7))))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThat(reloaded().getLeasedUntil()).as("the lease was not renewed").isEqualTo(leased);
    }

    private String bearer() {
        return "Bearer "
                + issuer.issueForTurn(
                        turn.getId(),
                        workspace.getId(),
                        turn.getAttempt(),
                        Objects.requireNonNull(turn.getLeaseId()),
                        Instant.now().plus(Duration.ofMinutes(5)));
    }

    private MentorTurn reloaded() {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    private void loseTheLease() {
        turns.renewLease(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                Duration.ofSeconds(-1));
        assertThat(turns.expireLease(turn.getId())).isPresent();
    }
}
