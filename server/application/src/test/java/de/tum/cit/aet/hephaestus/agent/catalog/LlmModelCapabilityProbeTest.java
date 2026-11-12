package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.proxy.LlmModelProbeGateway;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LlmModelCapabilityProbeTest extends BaseUnitTest {
    private static final LlmModelResolver.ConnectionRef MODEL =
            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 1L, 2L, null);
    private final ArrayDeque<LlmModelProbeGateway.Answer> answers = new ArrayDeque<>();
    private final List<ObjectNode> requests = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private LlmModelCapabilityProbe enabledProbe() {
        var gateway = mock(LlmModelProbeGateway.class);
        when(gateway.call(any(), any(), any(), any())).thenAnswer(call -> {
            requests.add(call.getArgument(3, ObjectNode.class));
            return answers.removeFirst();
        });
        var beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        return new LlmModelCapabilityProbe(beans.getBeanProvider(LlmModelProbeGateway.class), mapper);
    }

    private static LlmProbeTarget target(LlmApiProtocol protocol) {
        return new LlmProbeTarget("https://model.invalid/v1", LlmAuthMode.BEARER, "test-key", protocol);
    }

    @ParameterizedTest
    @EnumSource(
            value = LlmApiProtocol.class,
            names = {"ANTHROPIC_MESSAGES", "OPENAI_RESPONSES", "OPENAI_COMPLETIONS"})
    void shouldEstablishForcedToolChoiceFromActualToolCallsForOpaqueAliases(LlmApiProtocol protocol) {
        var probe = enabledProbe();
        answers.add(new LlmModelProbeGateway.Answer(200, "{}"));
        String answer = switch (protocol) {
            case ANTHROPIC_MESSAGES -> "{\"content\":[{\"type\":\"tool_use\",\"name\":\"probe\"}]}";
            case OPENAI_RESPONSES -> "{\"output\":[{\"type\":\"function_call\",\"name\":\"probe\"}]}";
            default -> "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"probe\"}}]}}]}";
        };
        answers.add(new LlmModelProbeGateway.Answer(200, answer));

        assertThat(probe.probe(MODEL, target(protocol), "review-alias").forcedToolChoice())
                .isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).path("model").asString()).isEqualTo("review-alias");
        String limit = switch (protocol) {
            case ANTHROPIC_MESSAGES -> "max_tokens";
            case OPENAI_RESPONSES -> "max_output_tokens";
            default -> "max_completion_tokens";
        };
        assertThat(requests.get(1).path(limit).asInt()).isEqualTo(LlmModelProbeGateway.MAX_OUTPUT_TOKENS);
    }

    @Test
    void shouldRecordUnsupportedOnlyAfterTheSameModelAcceptsAuto() {
        var probe = enabledProbe();
        answers.add(new LlmModelProbeGateway.Answer(200, "{}"));
        answers.add(new LlmModelProbeGateway.Answer(400, "{}"));
        assertThat(probe.probe(MODEL, target(LlmApiProtocol.ANTHROPIC_MESSAGES), "alias")
                        .forcedToolChoice())
                .isFalse();
    }

    @Test
    void shouldLeaveCapabilityUnknownWhenTheCredentialFails() {
        var probe = enabledProbe();
        answers.add(new LlmModelProbeGateway.Answer(401, "{}"));
        assertThat(probe.probe(MODEL, target(LlmApiProtocol.ANTHROPIC_MESSAGES), "alias")
                        .forcedToolChoice())
                .isNull();
        assertThat(requests).hasSize(1);
    }

    @Test
    void shouldLeaveCapabilityUnknownWhenTheProviderFailsOrIgnoresForcedChoice() {
        var probe = enabledProbe();
        answers.add(new LlmModelProbeGateway.Answer(200, "{}"));
        answers.add(new LlmModelProbeGateway.Answer(503, "{}"));
        assertThat(probe.probe(MODEL, target(LlmApiProtocol.OPENAI_RESPONSES), "alias")
                        .forcedToolChoice())
                .isNull();
        answers.add(new LlmModelProbeGateway.Answer(200, "{}"));
        answers.add(new LlmModelProbeGateway.Answer(200, "{}"));
        assertThat(probe.probe(MODEL, target(LlmApiProtocol.OPENAI_RESPONSES), "alias")
                        .forcedToolChoice())
                .isNull();
    }

    @Test
    void shouldLeaveCapabilityUnknownWhenTheAgentRuntimeIsOff() {
        var beans = new StaticListableBeanFactory();
        var disabled = new LlmModelCapabilityProbe(beans.getBeanProvider(LlmModelProbeGateway.class), mapper);
        assertThat(disabled.probe(MODEL, target(LlmApiProtocol.OPENAI_COMPLETIONS), "alias")
                        .forcedToolChoice())
                .isNull();
    }
}
