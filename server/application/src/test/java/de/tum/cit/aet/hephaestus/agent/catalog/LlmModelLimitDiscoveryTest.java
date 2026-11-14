package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.SsrfGuardedResolverGroup;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LlmModelLimitDiscoveryTest extends BaseUnitTest {

    private static final SystemSecrets SECRETS =
            TestSystemSecrets.systemSecrets("0123456789abcdef0123456789abcdef", "test");

    private MockWebServer upstream;
    private LlmConnectionRepository connections;
    private LlmModelRepository models;
    private WorkspaceLlmConnectionRepository workspaceConnections;
    private WorkspaceLlmModelRepository workspaceModels;
    private EgressPolicy egressPolicy;
    private LlmModelLimitDiscovery discovery;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        connections = mock(LlmConnectionRepository.class);
        models = mock(LlmModelRepository.class);
        workspaceConnections = mock(WorkspaceLlmConnectionRepository.class);
        workspaceModels = mock(WorkspaceLlmModelRepository.class);
        egressPolicy = mock(EgressPolicy.class);
        // Loopback allowed: the provider is a MockWebServer on localhost.
        when(egressPolicy.resolverGroup()).thenReturn(SsrfGuardedResolverGroup.LOOPBACK_EXEMPT_INSTANCE);
        discovery = new LlmModelLimitDiscovery(
                new LlmConnectionProbeService(egressPolicy, new ObjectMapper(), RestClient.builder()),
                connections,
                models,
                workspaceConnections,
                workspaceModels,
                egressPolicy,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                SECRETS);
    }

    /** The row a repository projects for {@code target}, its key sealed for an instance connection named "primary". */
    private static SealedProbeTarget sealed(LlmProbeTarget target) {
        String key = target.apiKey();
        return new SealedProbeTarget(
                target.baseUrl(),
                target.authMode(),
                key == null ? null : SECRETS.sealText(key, LlmConnection.secretBinding("primary")),
                target.apiProtocol(),
                "primary");
    }

    private static SealedProbeTarget sealedInWorkspace(LlmProbeTarget target) {
        String key = target.apiKey();
        return new SealedProbeTarget(
                target.baseUrl(),
                target.authMode(),
                key == null ? null : SECRETS.sealText(key, WorkspaceLlmConnection.secretBinding(1L, "own")),
                target.apiProtocol(),
                1L,
                "own");
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.close();
    }

    @Test
    void shouldStoreTheContextWindowThatAVllmServerListsAndAskTheOtherEndpointsOnlyForTheRest() throws Exception {
        LlmProbeTarget target = instance(
                "/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of("openai/gpt-oss-120b", "autotrust/JEV-27B-VL"));
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("vllm-models.json").toString()));
        // A gateway that serves its web app on unknown paths, as the recorded one does.
        upstream.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<!doctype html><html></html>")
                .build());
        upstream.enqueue(new MockResponse.Builder().code(404).build());

        var result = discovery.probeInstanceConnection(5L);

        assertThat(result.reachable()).isTrue();
        assertThat(target.baseUrl()).endsWith("/v1");
        assertThat(take().getTarget()).isEqualTo("/v1/models");
        assertThat(take().getTarget()).isEqualTo("/v1/model/info");
        RecordedRequest show = take();
        assertThat(show.getTarget()).isEqualTo("/api/show");
        assertThat(Objects.requireNonNull(show.getBody()).utf8()).isEqualTo("{\"model\":\"autotrust/JEV-27B-VL\"}");
        verify(models).recordDiscoveredLimits(5L, "openai/gpt-oss-120b", 131_072, null);
        // Listed without limits: what the provider reports now replaces what it reported before.
        verify(models).recordDiscoveredLimits(5L, "autotrust/JEV-27B-VL", null, null);
        verify(models, times(2)).recordDiscoveredLimits(anyLong(), anyString(), any(), any());
    }

    @Test
    void shouldStoreTheLimitsOfALiteLlmModelGroup() throws Exception {
        instance("/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of("team-gpt"));
        upstream.enqueue(json("{\"data\":[{\"id\":\"team-gpt\",\"object\":\"model\"}]}"));
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("litellm-model-info.json").toString()));

        discovery.probeInstanceConnection(5L);

        verify(models).recordDiscoveredLimits(5L, "team-gpt", 128_000, 16_384);
        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldStoreTheContextWindowThatOllamaShows() throws Exception {
        instance("/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of("llama3.2"));
        upstream.enqueue(json("{\"object\":\"list\",\"data\":[{\"id\":\"llama3.2\",\"object\":\"model\"}]}"));
        upstream.enqueue(new MockResponse.Builder().code(404).build());
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("ollama-show.json").toString()));

        discovery.probeInstanceConnection(5L);

        verify(models).recordDiscoveredLimits(5L, "llama3.2", 32_768, null);
    }

    @Test
    void shouldStoreAnthropicsLimitsFromAllItsModelsInOneListing() throws Exception {
        instance("", LlmApiProtocol.ANTHROPIC_MESSAGES, List.of("claude-opus-5-5", "claude-legacy"));
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("anthropic-models.json").toString()));

        discovery.probeInstanceConnection(5L);

        assertThat(take().getTarget()).isEqualTo("/v1/models?limit=1000");
        verify(models).recordDiscoveredLimits(5L, "claude-opus-5-5", 1_000_000, 128_000);
        verify(models).recordDiscoveredLimits(5L, "claude-legacy", null, null);
        // Anthropic lists every limit it has: there is nothing more to ask.
        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldAskNoFurtherForACatalogModelAndStopAtTheFirstModelOllamaCannotShow() throws Exception {
        instance("/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of("gpt-6.1-sol", "first", "second"));
        upstream.enqueue(json("{\"data\":[{\"id\":\"gpt-6.1-sol\"},{\"id\":\"first\"},{\"id\":\"second\"}]}"));
        upstream.enqueue(new MockResponse.Builder().code(404).build());
        upstream.enqueue(new MockResponse.Builder().code(404).build());

        discovery.probeInstanceConnection(5L);

        take();
        take();
        RecordedRequest show = take();
        assertThat(Objects.requireNonNull(show.getBody()).utf8()).isEqualTo("{\"model\":\"first\"}");
        assertThat(upstream.getRequestCount()).isEqualTo(3);
    }

    @Test
    void shouldStoreNothingWhenTheConnectionChangedWhileTheProbeRan() throws Exception {
        LlmProbeTarget probed = instance("/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of("openai/gpt-oss-120b"));
        when(connections.findProbeTargetById(5L))
                .thenReturn(Optional.of(sealed(probed)))
                .thenReturn(Optional.of(sealed(new LlmProbeTarget(
                        "https://other.example/v1", probed.authMode(), probed.apiKey(), probed.apiProtocol()))));
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("vllm-models.json").toString()));

        assertThat(discovery.probeInstanceConnection(5L).reachable()).isTrue();

        verify(models).findUpstreamModelIdsByConnectionId(5L);
        verifyNoMoreInteractions(models);
    }

    /** The probe blocks for its network timeout, so it reads a projection and holds no entity. */
    @Test
    void shouldProbeAStoredConnectionFromAProjectionRatherThanTheEntity() {
        instance("/v1", LlmApiProtocol.OPENAI_COMPLETIONS, List.of());
        upstream.enqueue(json("{\"data\":[{\"id\":\"gpt-5\"}]}"));

        var result = discovery.probeInstanceConnection(5L);

        assertThat(result.models()).containsExactly("gpt-5");
        verify(connections, never()).findById(any());
    }

    @Test
    void shouldReportNotFoundForAnUnknownStoredConnection() {
        when(connections.findProbeTargetById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> discovery.probeInstanceConnection(9L)).isInstanceOf(EntityNotFoundException.class);
    }

    /** A connection stored before the allowlist tightened is re-checked on every probe. */
    @Test
    void shouldNotDialAStoredConnectionWhoseBaseUrlTheEgressPolicyNowRejects() {
        when(connections.findProbeTargetById(5L))
                .thenReturn(Optional.of(sealed(new LlmProbeTarget(
                        upstream.url("/v1").toString(),
                        LlmAuthMode.BEARER,
                        "sk-abc",
                        LlmApiProtocol.OPENAI_COMPLETIONS))));
        doThrow(new IllegalArgumentException("Provider host must be a public HTTPS URL"))
                .when(egressPolicy)
                .validate(any());

        assertThatThrownBy(() -> discovery.probeInstanceConnection(5L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void shouldStoreAWorkspaceModelsLimitsOnlyInItsWorkspace() throws Exception {
        workspace(false, List.of("openai/gpt-oss-120b"));
        upstream.enqueue(
                json(ProviderModelLimitsTest.fixture("vllm-models.json").toString()));

        discovery.probeWorkspaceConnection(1L, 5L);

        verify(workspaceModels).recordDiscoveredLimits(1L, 5L, "openai/gpt-oss-120b", 131_072, null);
    }

    // The page tells an embedding or reranking provider that lists no models apart from a broken one by this status.
    @Test
    void shouldReportTheProvidersStatusWhenItsModelListAnswers404() throws Exception {
        workspace(false, List.of("m"));
        upstream.enqueue(new MockResponse.Builder().code(404).build());

        var result = discovery.probeWorkspaceConnection(1L, 5L);

        assertThat(result.reachable()).isFalse();
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(take().getTarget()).isEqualTo("/v1/models");
        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    /** Status and cause would let a workspace admin map the operator's network through the list. */
    @Test
    void shouldReportOnlyUnreachableWhenAPrivateHostFails() {
        workspace(true, List.of("m"));
        upstream.enqueue(new MockResponse.Builder().code(404).build());

        var result = discovery.probeWorkspaceConnection(1L, 5L);

        assertThat(result.reachable()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.message())
                .isEqualTo("Hephaestus could not reach the provider. The server log has the reason.");
    }

    private LlmProbeTarget instance(String path, LlmApiProtocol protocol, List<String> upstreamIds) {
        var target = new LlmProbeTarget(upstream.url(path).toString(), LlmAuthMode.BEARER, "sk-abc", protocol);
        when(connections.findProbeTargetById(5L)).thenReturn(Optional.of(sealed(target)));
        when(models.findUpstreamModelIdsByConnectionId(5L)).thenReturn(upstreamIds);
        return target;
    }

    private void workspace(boolean privateTarget, List<String> upstreamIds) {
        var target = new LlmProbeTarget(
                upstream.url("/v1").toString(), LlmAuthMode.BEARER, null, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(workspaceConnections.findProbeTargetByIdAndWorkspaceId(5L, 1L))
                .thenReturn(Optional.of(sealedInWorkspace(target)));
        when(workspaceModels.findUpstreamModelIdsByConnectionIdAndWorkspaceId(5L, 1L))
                .thenReturn(upstreamIds);
        when(egressPolicy.validate(target.baseUrl())).thenReturn(privateTarget);
    }

    private RecordedRequest take() throws InterruptedException {
        return Objects.requireNonNull(upstream.takeRequest(1, TimeUnit.SECONDS));
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
