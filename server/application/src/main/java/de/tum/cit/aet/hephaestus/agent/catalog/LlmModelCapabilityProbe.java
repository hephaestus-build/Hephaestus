package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.proxy.LlmModelProbeGateway;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Uses synthetic text only. Network calls run outside the catalog's database transactions. */
@Service
public class LlmModelCapabilityProbe {
    private final ObjectProvider<LlmModelProbeGateway> gateways;
    private final ObjectMapper mapper;

    LlmModelCapabilityProbe(ObjectProvider<LlmModelProbeGateway> gateways, ObjectMapper mapper) {
        this.gateways = gateways;
        this.mapper = mapper;
    }

    public LlmModelCapabilitiesDTO probe(LlmModelResolver.ConnectionRef modelRef, LlmProbeTarget target, String model) {
        LlmModelProbeGateway gateway = gateways.getIfAvailable();
        if (gateway == null) return new LlmModelCapabilitiesDTO(null);
        UUID probeId = UUID.randomUUID();
        try {
            // A bad key, wrong endpoint or failed model is not evidence that forced choice is unsupported.
            var baseline =
                    gateway.call(probeId, modelRef, target.apiProtocol(), body(target.apiProtocol(), model, false));
            if (baseline.status() < 200 || baseline.status() >= 300) return new LlmModelCapabilitiesDTO(null);
            var forced = gateway.call(probeId, modelRef, target.apiProtocol(), body(target.apiProtocol(), model, true));
            if (forced.status() == 400 || forced.status() == 422) return new LlmModelCapabilitiesDTO(false);
            if (forced.status() < 200 || forced.status() >= 300) return new LlmModelCapabilitiesDTO(null);
            JsonNode response = mapper.readTree(forced.body());
            boolean called = switch (target.apiProtocol()) {
                case ANTHROPIC_MESSAGES -> hasCall(response.path("content"), "tool_use");
                case OPENAI_RESPONSES -> hasCall(response.path("output"), "function_call");
                case OPENAI_COMPLETIONS -> {
                    boolean found = false;
                    for (JsonNode call :
                            response.path("choices").path(0).path("message").path("tool_calls"))
                        found |= "probe"
                                .equals(call.path("function").path("name").asString(""));
                    yield found;
                }
                default -> false;
            };
            return new LlmModelCapabilitiesDTO(called ? Boolean.TRUE : null);
        } catch (Exception unavailable) {
            return new LlmModelCapabilitiesDTO(null);
        }
    }

    private static boolean hasCall(JsonNode calls, String type) {
        for (JsonNode call : calls)
            if (type.equals(call.path("type").asString(""))
                    && "probe".equals(call.path("name").asString(""))) return true;
        return false;
    }

    private ObjectNode body(LlmApiProtocol protocol, String model, boolean forced) {
        var body = mapper.createObjectNode().put("model", model);
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        var tool = mapper.createObjectNode().put("name", "probe").put("description", "Confirm the connection.");
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
            body.put("max_tokens", LlmModelProbeGateway.MAX_OUTPUT_TOKENS);
            body.putArray("messages").addObject().put("role", "user").put("content", "Call probe.");
            tool.set("input_schema", schema);
            body.putArray("tools").add(tool);
            var choice = body.putObject("tool_choice").put("type", forced ? "tool" : "auto");
            if (forced) choice.put("name", "probe");
        } else if (protocol == LlmApiProtocol.OPENAI_RESPONSES) {
            body.put("max_output_tokens", LlmModelProbeGateway.MAX_OUTPUT_TOKENS)
                    .put("store", false)
                    .put("input", "Call probe.");
            tool.put("type", "function").set("parameters", schema);
            body.putArray("tools").add(tool);
            if (forced) body.putObject("tool_choice").put("type", "function").put("name", "probe");
            else body.put("tool_choice", "auto");
        } else {
            body.put("max_completion_tokens", LlmModelProbeGateway.MAX_OUTPUT_TOKENS)
                    .put("store", false);
            body.putArray("messages").addObject().put("role", "user").put("content", "Call probe.");
            tool.set("parameters", schema);
            body.putArray("tools")
                    .add(mapper.createObjectNode().put("type", "function").set("function", tool));
            if (forced)
                body.putObject("tool_choice")
                        .put("type", "function")
                        .putObject("function")
                        .put("name", "probe");
            else body.put("tool_choice", "auto");
        }
        return body;
    }
}
