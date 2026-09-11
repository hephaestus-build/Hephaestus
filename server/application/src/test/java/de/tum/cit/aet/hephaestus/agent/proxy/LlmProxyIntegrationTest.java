package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver.ConnectionRef;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LlmProxyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmModelResolver modelResolver;

    @Autowired
    private WorkerJwtVerifier jwtVerifier;

    @Autowired
    private WorkerJwtIssuer jwtIssuer;

    private JobTokenAuthenticationFilter filter;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new JobTokenAuthenticationFilter(
                jobRepository, jwtVerifier, new MentorProxyCredentialRegistry(), objectMapper, "worker-1");
        User owner = persistUser("proxy-owner");
        workspace = createWorkspace("proxy-ws", "Proxy Workspace", "proxy-org", AccountType.ORG, owner);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateRunningJobTokenAgainstPersistedRouting() throws Exception {
        AgentJob job = runningJob(true);
        AuthenticationResult result = authenticate(
                jwtIssuer.issueForJob(job.getId(), workspace.getId(), job.getRetryCount(), Duration.ofMinutes(5)));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.authentication())
                .isNotNull()
                .extracting(Authentication::getPrincipal)
                .isInstanceOf(ProxyRouting.class);
    }

    @Test
    void shouldRejectLegacyOpaqueJobToken() throws Exception {
        // The database-backed secret the entity still persists is a pre-migration credential; the
        // proxy accepts only per-job JWTs — there is deliberately no dual-accept window.
        AgentJob job = runningJob(true);

        AuthenticationResult result = authenticate(job.getJobToken());

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectWorkerSessionJwtAtTheProxy() throws Exception {
        runningJob(true);

        AuthenticationResult result = authenticate(jwtIssuer.issue("worker-1").token());

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldRejectJobJwtBoundToAnotherWorkspace() throws Exception {
        AgentJob job = runningJob(true);

        AuthenticationResult result = authenticate(
                jwtIssuer.issueForJob(job.getId(), workspace.getId() + 1, job.getRetryCount(), Duration.ofMinutes(5)));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    /**
     * Two sandboxes share a worker, so one holding the other's token must not become the other: the
     * route and the execution it bills both come from the row the token names.
     */
    @Test
    void shouldRouteEachJobTokenOnlyToTheJobThatMintedIt() throws Exception {
        AgentJob first = runningJob(true);
        AgentJob second = runningJob(true);

        ProxyRouting firstRouting = routingOf(authenticate(tokenFor(first)));
        ProxyRouting secondRouting = routingOf(authenticate(tokenFor(second)));

        assertThat(firstRouting.principalDescription()).isEqualTo("job:" + first.getId());
        assertThat(firstRouting.sourceId()).isEqualTo(first.getId());
        assertThat(secondRouting.principalDescription()).isEqualTo("job:" + second.getId());
        assertThat(secondRouting.sourceId()).isEqualTo(second.getId());
    }

    /**
     * A requeue starts a new attempt with its own token, so the one the dead attempt handed its
     * sandbox has to stop working — otherwise an orphaned container keeps spending on a row that has
     * already been re-admitted.
     */
    @Test
    void shouldRejectAJobTokenMintedForAnEarlierAttempt() throws Exception {
        AgentJob job = runningJob(true);
        String earlierAttempt = tokenFor(job);
        job.setRetryCount(job.getRetryCount() + 1);
        jobRepository.save(job);

        AuthenticationResult result = authenticate(earlierAttempt);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.authentication()).isNull();
    }

    @Test
    void shouldFailClosedWhenCatalogModelIsDisabled() {
        AgentJob job = runningJob(false);
        long connectionId = job.getConfigSnapshot().get("connectionId").asLong();
        long modelId = job.getConfigSnapshot().get("modelId").asLong();

        assertThat(modelResolver.resolveProxyCredential(
                        new ConnectionRef(FundingSource.INSTANCE, connectionId, modelId, workspace.getId())))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"GET,''", "GET,/workspace", "GET,/frames", "POST,/result"})
    void shouldAuthorizeRuntimeRoutesOnlyForThePersistedWorkerAndAttempt(String method, String suffix)
            throws Exception {
        AgentJob job = runningJob(true);
        String token = tokenFor(job);
        String path = "/internal/llm/runtime/" + java.util.UUID.randomUUID() + suffix;
        assertThat(authenticate(method, path, token).status()).isEqualTo(200);

        job.setWorkerId("another-worker");
        jobRepository.save(job);
        assertThat(authenticate(method, path, token).status()).isEqualTo(401);

        job.setWorkerId("worker-1");
        job.setRetryCount(job.getRetryCount() + 1);
        jobRepository.save(job);
        assertThat(authenticate(method, path, token).status()).isEqualTo(401);
    }

    private AuthenticationResult authenticate(String token) throws Exception {
        return authenticate("POST", "/internal/llm/chat/completions", token);
    }

    private AuthenticationResult authenticate(String method, String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> authentication.set(Objects.requireNonNull(
                        SecurityContextHolder.getContext().getAuthentication())));
        return new AuthenticationResult(response.getStatus(), authentication.get());
    }

    private record AuthenticationResult(int status, Authentication authentication) {}

    private String tokenFor(AgentJob job) {
        return jwtIssuer.issueForJob(job.getId(), workspace.getId(), job.getRetryCount(), Duration.ofMinutes(5));
    }

    private static ProxyRouting routingOf(AuthenticationResult result) {
        Authentication authentication = Objects.requireNonNull(result.authentication(), "token did not authenticate");
        return (ProxyRouting) Objects.requireNonNull(authentication.getPrincipal(), "no routing on the principal");
    }

    private AgentJob runningJob(boolean modelEnabled) {
        LlmConnection connection = LlmCatalogTestFixtures.connection("connection-" + System.nanoTime());
        connection.setBaseUrl("https://api.example.com/v1");
        connection.setApiProtocol("openai-completions");
        connection.setAuthMode(LlmAuthMode.BEARER);
        connection.setApiKey("upstream-secret");
        connection = connectionRepository.save(connection);

        LlmModel model = modelRepository.save(LlmCatalogTestFixtures.model(
                connection, "model-" + System.nanoTime(), "catalog-model", ModelVisibility.PUBLIC, modelEnabled));
        // A workspace binds one model per purpose, so a second job re-points the binding it already has.
        WorkspaceAgentBinding binding = agentBindingRepository
                .findByWorkspaceIdAndPurpose(workspace.getId(), AgentPurpose.PRACTICE_REVIEW)
                .orElseGet(WorkspaceAgentBinding::new);
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(model);
        binding.setEnabled(true);
        binding.setTimeoutSeconds(600);
        agentBindingRepository.save(binding);

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("apiProtocol", "openai-completions");
        snapshot.put("baseUrl", connection.getBaseUrl());
        snapshot.put("upstreamModelId", model.getUpstreamModelId());
        snapshot.put("connectionScope", "INSTANCE");
        snapshot.put("fundingSource", "INSTANCE");
        snapshot.put("connectionId", connection.getId());
        snapshot.put("modelId", model.getId());
        snapshot.put("workspaceId", workspace.getId());
        snapshot.put("timeoutSeconds", 600);
        snapshot.put("allowInternet", false);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(snapshot);
        return jobRepository.save(job);
    }
}
