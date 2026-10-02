package de.tum.cit.aet.hephaestus.agent.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory.PiPlan;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit coverage for {@link MentorPiAdapter#buildSandboxSpec}: the genuinely error-prone branches the
 * orchestration-level {@code MentorChatServiceTest} stubs over — resolved routing, the turn budget, and a
 * spec that carries the system prompt and nothing of a turn. {@link PiRuntimeFactory} is mocked so the
 * captured {@link PiPlanSpec} can be asserted on directly.
 */
class MentorPiAdapterTest extends BaseUnitTest {

    private static final MentorAgentRequest REQUEST = new MentorAgentRequest(7L, 42L);

    @Mock
    private PiRuntimeFactory runtimeFactory;

    private MentorProxyCredentialRegistry proxyRegistry;
    private MentorPiAdapter adapter;

    @BeforeEach
    void setUp() {
        // A minimal valid plan; the tests assert on the captured spec, not on the returned plan content.
        PiPlan plan = new PiPlan(
                List.of("sh", "-c", "true"), Map.of(), Map.of(), new NetworkPolicy(true, null, null), "0".repeat(64));
        when(runtimeFactory.build(any())).thenReturn(plan);
        proxyRegistry = new MentorProxyCredentialRegistry();
        adapter = newAdapter();
    }

    private MentorPiAdapter newAdapter() {
        return new MentorPiAdapter(
                runtimeFactory,
                new AgentImageProperties("test-image:latest", ImagePullPolicy.IF_NOT_PRESENT),
                proxyRegistry);
    }

    private static MentorLlmConfig llmConfig(@Nullable String rawBaseUrl) {
        return llmConfig(rawBaseUrl, false);
    }

    private static MentorLlmConfig llmConfig(@Nullable String rawBaseUrl, boolean allowInternet) {
        return llmConfig(rawBaseUrl, allowInternet, 120);
    }

    private static MentorLlmConfig llmConfig(@Nullable String rawBaseUrl, boolean allowInternet, int timeoutSeconds) {
        String resolvedBaseUrl =
                rawBaseUrl != null && !rawBaseUrl.isBlank() ? rawBaseUrl.trim() : "https://api.openai.com";
        return new MentorLlmConfig(
                "openai-completions",
                resolvedBaseUrl,
                "gpt-5.4",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                allowInternet,
                timeoutSeconds);
    }

    private PiPlanSpec capturePlanSpec(MentorLlmConfig config) {
        adapter.buildSandboxSpec(REQUEST, config);
        ArgumentCaptor<PiPlanSpec> captor = ArgumentCaptor.forClass(PiPlanSpec.class);
        verify(runtimeFactory).build(captor.capture());
        return captor.getValue();
    }

    private ProxyRouting routingFor(PiPlanSpec spec) {
        String jobToken = spec.jobToken();
        org.junit.jupiter.api.Assertions.assertNotNull(jobToken);
        return proxyRegistry.validate(jobToken).orElseThrow();
    }

    @Test
    @DisplayName("a binding stored above the ceiling still produces a turn bounded by the ceiling")
    void turnBudgetIsClampedDownToTheConfigurableCeiling() {
        MentorLlmConfig config = llmConfig(null, false, AgentBindingLimits.MAX_TIMEOUT_SECONDS * 2);
        PiPlanSpec spec = capturePlanSpec(config);

        assertThat(spec.timeoutSeconds()).isEqualTo(AgentBindingLimits.MAX_TIMEOUT_SECONDS);
        assertThat(config.timeoutSeconds()).isEqualTo(spec.timeoutSeconds());
    }

    /**
     * The binding API floor sits below {@link PiPlanSpec}'s runtime floor, so a legitimately persisted
     * 30-60s binding would otherwise throw building the spec and reach the mentee as an ERROR instead of an answer.
     */
    @Test
    @DisplayName("a binding at the configurable floor still yields a buildable sandbox")
    void turnBudgetIsClampedUpToTheSmallestBuildableBudget() {
        MentorLlmConfig config = llmConfig(null, false, AgentBindingLimits.MIN_TIMEOUT_SECONDS);
        PiPlanSpec spec = capturePlanSpec(config);

        assertThat(spec.timeoutSeconds()).isEqualTo(PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1);
        assertThat(config.timeoutSeconds()).isEqualTo(spec.timeoutSeconds());
    }

    @Test
    void shouldReserveCleanupFromTheFrozenTimeoutInTheActualRunnerEnvironment() {
        org.mockito.Mockito.reset(runtimeFactory);
        var actualAdapter = new MentorPiAdapter(
                new PiRuntimeFactory(new tools.jackson.databind.ObjectMapper()),
                new AgentImageProperties("test-image:latest", ImagePullPolicy.IF_NOT_PRESENT),
                proxyRegistry);
        MentorLlmConfig config = llmConfig(null, false, 300);

        InteractiveSandboxSpec sandbox = actualAdapter.buildSandboxSpec(REQUEST, config);

        assertThat(config.timeoutSeconds()).isEqualTo(300);
        assertThat(sandbox.environment()).containsEntry("AGENT_BUDGET_MS", "240000");
        assertThat(sandbox.environment()).doesNotContainKey("MENTOR_TURN_BUDGET_MS");
    }

    @Test
    @DisplayName("the resolved catalog base URL is carried into proxy routing")
    void resolvedCatalogBaseUrlIsUsed() {
        PiPlanSpec spec = capturePlanSpec(llmConfig("https://config.example"));
        assertThat(routingFor(spec).baseUrl()).isEqualTo("https://config.example");
    }

    @Test
    @DisplayName("a blank instance base URL property yields the resolver default when the config has none")
    void blankPropertyYieldsResolverDefault() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null));
        assertThat(routingFor(spec).baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("every sandbox build mints a fresh, non-blank proxy token")
    void mintsProxyToken() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null));
        String jobToken = spec.jobToken();
        org.junit.jupiter.api.Assertions.assertNotNull(jobToken);
        assertThat(jobToken).isNotBlank();
        assertThat(proxyRegistry.validate(jobToken)).isPresent();
    }

    @Test
    void carriesConfiguredInternetPolicyIntoTheRuntimePlan() {
        adapter.buildSandboxSpec(REQUEST, llmConfig(null, true));
        adapter.buildSandboxSpec(REQUEST, llmConfig(null, false));

        ArgumentCaptor<PiPlanSpec> captor = ArgumentCaptor.forClass(PiPlanSpec.class);
        verify(runtimeFactory, times(2)).build(captor.capture());
        assertThat(captor.getAllValues()).extracting(PiPlanSpec::allowInternet).containsExactly(true, false);
    }

    @Test
    @DisplayName("the spec carries only the system prompt, so a prepared sandbox serves any thread")
    void specCarriesNothingOfATurn() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null));
        assertThat(spec.extraInputs()).containsOnlyKeys(MentorPiAdapter.SYSTEM_PROMPT_PATH);
    }

    @Test
    @DisplayName("the mentor system prompt is always injected at SYSTEM_PROMPT_PATH")
    void systemPromptAlwaysInjected() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null));
        assertThat(spec.extraInputs()).containsKey(MentorPiAdapter.SYSTEM_PROMPT_PATH);
        assertThat(spec.extraInputs().get(MentorPiAdapter.SYSTEM_PROMPT_PATH)).isNotEmpty();
    }

    @Test
    @DisplayName("the sandbox spec carries the routing identity from the request")
    void specCarriesRoutingIdentity() {
        InteractiveSandboxSpec spec = adapter.buildSandboxSpec(REQUEST, llmConfig(null));
        assertThat(spec.userId()).isEqualTo("42");
        assertThat(spec.workspaceId()).isEqualTo("7");
        assertThat(spec.image()).isEqualTo("test-image:latest");
    }
}
