package de.tum.cit.aet.hephaestus.agent.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit coverage for {@link MentorPiAdapter#buildSandboxSpec}: the genuinely error-prone branches the
 * orchestration-level {@code MentorChatServiceTest} stubs over — context-key validation, resolved routing,
 * session-restore injection, and the always-present system prompt. {@link PiRuntimeFactory} is mocked so the
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

    private PiPlanSpec capturePlanSpec(
            MentorLlmConfig config, Map<String, byte[]> contexts, @Nullable SessionRestore restore) {
        adapter.buildSandboxSpec(REQUEST, config, contexts, restore);
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
    @DisplayName("only whitelisted mentor context keys pass")
    void contextKeyValidation() {
        Map<String, byte[]> ok = Map.of(
                MentorPiAdapter.CONTEXT_INPUT_PREFIX + "recent_authored_work.json",
                "{}".getBytes(StandardCharsets.UTF_8));
        // does not throw
        adapter.buildSandboxSpec(REQUEST, llmConfig(null), ok, null);

        Map<String, byte[]> stray = Map.of("out/leak.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> adapter.buildSandboxSpec(REQUEST, llmConfig(null), stray, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MentorPiAdapter.CONTEXT_INPUT_PREFIX);

        Map<String, byte[]> unsupported = Map.of(
                MentorPiAdapter.CONTEXT_INPUT_PREFIX + "future_unreviewed_context.json",
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> adapter.buildSandboxSpec(REQUEST, llmConfig(null), unsupported, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported mentor context input key");
    }

    @Test
    @DisplayName("a binding stored above the ceiling still produces a turn bounded by the ceiling")
    void turnBudgetIsClampedDownToTheConfigurableCeiling() {
        PiPlanSpec spec =
                capturePlanSpec(llmConfig(null, false, AgentBindingLimits.MAX_TIMEOUT_SECONDS * 2), Map.of(), null);

        assertThat(spec.timeoutSeconds()).isEqualTo(AgentBindingLimits.MAX_TIMEOUT_SECONDS);
    }

    /**
     * The binding API floor sits below {@link PiPlanSpec}'s runtime floor, so a legitimately persisted
     * 30-60s binding would otherwise throw building the spec and reach the mentee as an ERROR instead of an answer.
     */
    @Test
    @DisplayName("a binding at the configurable floor still yields a buildable sandbox")
    void turnBudgetIsClampedUpToTheSmallestBuildableBudget() {
        PiPlanSpec spec =
                capturePlanSpec(llmConfig(null, false, AgentBindingLimits.MIN_TIMEOUT_SECONDS), Map.of(), null);

        assertThat(spec.timeoutSeconds()).isEqualTo(PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1);
    }

    @Test
    @DisplayName("the resolved catalog base URL is carried into proxy routing")
    void resolvedCatalogBaseUrlIsUsed() {
        PiPlanSpec spec = capturePlanSpec(llmConfig("https://config.example"), Map.of(), null);
        assertThat(routingFor(spec).baseUrl()).isEqualTo("https://config.example");
    }

    @Test
    @DisplayName("a blank instance base URL property yields the resolver default when the config has none")
    void blankPropertyYieldsResolverDefault() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null), Map.of(), null);
        assertThat(routingFor(spec).baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("every sandbox build mints a fresh, non-blank proxy token")
    void mintsProxyToken() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null), Map.of(), null);
        String jobToken = spec.jobToken();
        org.junit.jupiter.api.Assertions.assertNotNull(jobToken);
        assertThat(jobToken).isNotBlank();
        assertThat(proxyRegistry.validate(jobToken)).isPresent();
    }

    @Test
    void carriesConfiguredInternetPolicyIntoTheRuntimePlan() {
        adapter.buildSandboxSpec(REQUEST, llmConfig(null, true), Map.of(), null);
        adapter.buildSandboxSpec(REQUEST, llmConfig(null, false), Map.of(), null);

        ArgumentCaptor<PiPlanSpec> captor = ArgumentCaptor.forClass(PiPlanSpec.class);
        verify(runtimeFactory, times(2)).build(captor.capture());
        assertThat(captor.getAllValues()).extracting(PiPlanSpec::allowInternet).containsExactly(true, false);
    }

    @Test
    @DisplayName("sessionRestore injects exactly .sessions/<threadId>.jsonl with the supplied bytes")
    void sessionRestoreInjectsJsonl() {
        UUID threadId = UUID.randomUUID();
        byte[] bytes = "{\"replay\":true}".getBytes(StandardCharsets.UTF_8);
        PiPlanSpec spec = capturePlanSpec(llmConfig(null), Map.of(), new SessionRestore(threadId, bytes));

        String expectedKey = MentorPiAdapter.SESSIONS_DIR_PREFIX + threadId + ".jsonl";
        assertThat(spec.extraInputs()).containsKey(expectedKey);
        assertThat(spec.extraInputs().get(expectedKey)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("no sessionRestore adds no .sessions entry")
    void noSessionRestoreAddsNoSessionsEntry() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null), Map.of(), null);
        assertThat(spec.extraInputs().keySet()).noneMatch(k -> k.startsWith(MentorPiAdapter.SESSIONS_DIR_PREFIX));
    }

    @Test
    @DisplayName("the mentor system prompt is always injected at SYSTEM_PROMPT_PATH")
    void systemPromptAlwaysInjected() {
        PiPlanSpec spec = capturePlanSpec(llmConfig(null), Map.of(), null);
        assertThat(spec.extraInputs()).containsKey(MentorPiAdapter.SYSTEM_PROMPT_PATH);
        assertThat(spec.extraInputs().get(MentorPiAdapter.SYSTEM_PROMPT_PATH)).isNotEmpty();
    }

    @Test
    @DisplayName("the sandbox spec carries the routing identity from the request")
    void specCarriesRoutingIdentity() {
        InteractiveSandboxSpec spec = adapter.buildSandboxSpec(REQUEST, llmConfig(null), Map.of(), null);
        assertThat(spec.userId()).isEqualTo("42");
        assertThat(spec.workspaceId()).isEqualTo("7");
        assertThat(spec.image()).isEqualTo("test-image:latest");
    }
}
