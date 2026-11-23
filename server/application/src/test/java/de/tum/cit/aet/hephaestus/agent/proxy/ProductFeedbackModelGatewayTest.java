package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProductFeedbackModelGatewayTest extends BaseUnitTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProxyService proxy = mock(LlmProxyService.class);
    private final ProductFeedbackModelGateway gateway = new ProductFeedbackModelGateway(proxy, MAPPER);

    @Test
    void shouldCarryTheOutputBoundAndCapacityRefusalThroughTheSharedProxy() {
        answer(ResponseEntity.status(503)
                .header(LlmProxyService.REFUSAL_HEADER, ProxyRefusal.CAPACITY.name())
                .body("busy"));
        UUID report = UUID.randomUUID();
        var draft = gateway.draft(
                UUID.randomUUID(),
                new ProductFeedbackDrafts.Payload(report, 3),
                model(LlmApiProtocol.OPENAI_COMPLETIONS, null),
                Instant.now().plusSeconds(30),
                new ProductFeedbackDraftCalls.DraftCall(8L, "Draft it.", "The report.", 900));
        assertThat(draft.failure()).isEqualTo(ProductFeedbackModelGateway.Failure.NO_CAPACITY);
        var route = ArgumentCaptor.forClass(ProxyRouting.class);
        verify(proxy).serve(route.capture(), any(), isNull(), any(), any(), isNull());
        assertThat(route.getValue().workspaceId()).isNull();
        assertThat(route.getValue().attempt().runId()).isNull();
        assertThat(route.getValue().attempt().sourceType()).isEqualTo(UsageSourceType.PRODUCT_FEEDBACK_DRAFT);
        assertThat(route.getValue().attempt().sourceId()).isEqualTo(report);
        assertThat(route.getValue().outputLimit()).isEqualTo(700L);
    }

    @ParameterizedTest
    @EnumSource(LlmApiProtocol.class)
    void shouldOfferTheModelNoToolsAndReadItsText(LlmApiProtocol protocol) {
        answer(ResponseEntity.ok(served(protocol, "{\"disposition\":\"ISSUE\"}")));

        var draft = draft(model(protocol, ReasoningEffort.LOW));

        assertThat(draft).isEqualTo(new ProductFeedbackModelGateway.Draft("{\"disposition\":\"ISSUE\"}", null));
        JsonNode request = sent();
        assertThat(request.has("tools")).isFalse();
        assertThat(request.has("tool_choice")).isFalse();
        assertThat(request.path("model").asString()).isEqualTo("draft-upstream");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "ANTHROPIC_MESSAGES | {\"model\":\"draft-upstream\",\"system\":\"Draft it.\",\"messages\":[{\"role\":\"user\",\"content\":\"The report.\"}],\"max_tokens\":900}",
                "OPENAI_COMPLETIONS | {\"model\":\"draft-upstream\",\"messages\":[{\"role\":\"system\",\"content\":\"Draft it.\"},{\"role\":\"user\",\"content\":\"The report.\"}],\"store\":false,\"max_completion_tokens\":900,\"reasoning_effort\":\"low\"}",
                "OPENAI_RESPONSES | {\"model\":\"draft-upstream\",\"instructions\":\"Draft it.\",\"input\":[{\"role\":\"user\",\"content\":\"The report.\"}],\"store\":false,\"max_output_tokens\":900,\"reasoning\":{\"effort\":\"low\"},\"include\":[\"reasoning.encrypted_content\"]}",
            })
    void shouldWriteThePromptAndReasoningWhereEachProtocolReadsThem(LlmApiProtocol protocol, String expected) {
        answer(ResponseEntity.ok(served(protocol, "{}")));

        draft(model(protocol, ReasoningEffort.LOW));

        assertThat(sent()).isEqualTo(MAPPER.readTree(expected));
    }

    @Test
    void shouldUseTheProviderDefaultsOfAnOpaqueAnthropicModel() {
        answer(ResponseEntity.ok(served(LlmApiProtocol.ANTHROPIC_MESSAGES, "{}")));

        draft(model(LlmApiProtocol.ANTHROPIC_MESSAGES, ReasoningEffort.HIGH));

        JsonNode request = sent();
        assertThat(request.has("thinking")).isFalse();
        assertThat(request.has("output_config")).isFalse();
        assertThat(request.has("temperature")).isFalse();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(900);
    }

    @ParameterizedTest
    @CsvSource({
        "503, , PROVIDER",
        "429, , PROVIDER",
        "408, , PROVIDER",
        "400, , REFUSED",
        "403, AI_CHOICE, AI_CHOICE",
        "402, BUDGET, BUDGET",
        "504, WORK_TIME, WORK_TIME",
        "409, ATTEMPT_ENDED, ATTEMPT_ENDED",
        "429, CALLS_IN_FLIGHT, PROVIDER",
    })
    void shouldNameWhyTheCallGaveNoText(
            int status, @Nullable ProxyRefusal refusal, ProductFeedbackModelGateway.Failure failure) {
        var response = ResponseEntity.status(status);
        if (refusal != null) response.header(LlmProxyService.REFUSAL_HEADER, refusal.name());
        answer(response.body(new byte[0]));

        assertThat(draft(model(LlmApiProtocol.OPENAI_COMPLETIONS, null)).failure())
                .isEqualTo(failure);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "OPENAI_COMPLETIONS | {\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":true}}]}",
                "ANTHROPIC_MESSAGES | {\"content\":[{\"type\":\"text\",\"text\":42}]}",
                "OPENAI_RESPONSES | {\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":{}}]}]}",
            })
    void shouldReadNoTextFromAnAnswerWhoseTextIsNoString(LlmApiProtocol protocol, String answer) {
        answer(ResponseEntity.ok(answer));

        assertThat(draft(model(protocol, null))).isEqualTo(new ProductFeedbackModelGateway.Draft(null, null));
    }

    @Test
    void shouldReadAnAnthropicRefusalAsRefused() {
        answer(ResponseEntity.ok("{\"stop_reason\":\"refusal\",\"content\":[]}"));

        assertThat(draft(model(LlmApiProtocol.ANTHROPIC_MESSAGES, null)).failure())
                .isEqualTo(ProductFeedbackModelGateway.Failure.REFUSED);
    }

    @Test
    void shouldReadAnUnreadableAnswerAsAProviderFailure() {
        answer(ResponseEntity.ok("not json"));

        assertThat(draft(model(LlmApiProtocol.OPENAI_RESPONSES, null)).failure())
                .isEqualTo(ProductFeedbackModelGateway.Failure.PROVIDER);
    }

    private void answer(ResponseEntity<?> response) {
        doReturn(response).when(proxy).serve(any(), any(), isNull(), any(), any(), isNull());
    }

    private ProductFeedbackModelGateway.Draft draft(LlmModelResolver.InstanceModel model) {
        return gateway.draft(
                UUID.randomUUID(),
                new ProductFeedbackDrafts.Payload(UUID.randomUUID(), 1),
                model,
                Instant.now().plusSeconds(30),
                new ProductFeedbackDraftCalls.DraftCall(8L, "Draft it.", "The report.", 900));
    }

    private JsonNode sent() {
        var body = ArgumentCaptor.forClass(byte[].class);
        verify(proxy).serve(any(), any(), isNull(), any(), body.capture(), isNull());
        return MAPPER.readTree(body.getValue());
    }

    private static LlmModelResolver.InstanceModel model(
            LlmApiProtocol protocol, @Nullable ReasoningEffort reasoningEffort) {
        return new LlmModelResolver.InstanceModel(
                new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 8L, null),
                new LlmModelResolver.ProxyCredential(
                        "https://model.example",
                        protocol.wire(),
                        LlmAuthMode.BEARER,
                        "draft-upstream",
                        "synthetic",
                        1L),
                protocol,
                reasoningEffort,
                null,
                700);
    }

    private static String served(LlmApiProtocol protocol, String text) {
        var body = MAPPER.createObjectNode();
        switch (protocol) {
            case ANTHROPIC_MESSAGES ->
                body.putArray("content").addObject().put("type", "text").put("text", text);
            case OPENAI_COMPLETIONS ->
                body.putArray("choices")
                        .addObject()
                        .putObject("message")
                        .put("role", "assistant")
                        .put("content", text);
            case OPENAI_RESPONSES ->
                body.putArray("output")
                        .addObject()
                        .put("type", "message")
                        .putArray("content")
                        .addObject()
                        .put("type", "output_text")
                        .put("text", text);
        }
        return body.toString();
    }
}
