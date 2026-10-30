package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Uses synthetic text only. Network calls run outside the catalog's database transactions. */
@Service
public class LlmModelCapabilityProbe {
    private final EgressPolicy egress;
    private final RestClient client;
    private final ObjectMapper mapper;

    LlmModelCapabilityProbe(EgressPolicy egress, RestClient.Builder clients, ObjectMapper mapper) {
        this.egress = egress;
        this.mapper = mapper;
        var factory = new ReactorClientHttpRequestFactory(
                HttpClient.create().resolver(egress.resolverGroup()).followRedirect(false));
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = clients.requestFactory(factory).build();
    }

    public LlmModelCapabilitiesDTO probe(LlmProbeTarget target, String model) {
        egress.validate(target.baseUrl());
        if (target.apiProtocol().kind() != ModelKind.CHAT) return new LlmModelCapabilitiesDTO(null);
        try {
            // A bad key, wrong endpoint or failed model is not evidence that forced choice is unsupported.
            Answer baseline = call(target, body(target.apiProtocol(), model, false));
            if (baseline.status() < 200 || baseline.status() >= 300) return new LlmModelCapabilitiesDTO(null);
            Answer forced = call(target, body(target.apiProtocol(), model, true));
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
            body.put("max_tokens", 64);
            body.putArray("messages").addObject().put("role", "user").put("content", "Call probe.");
            tool.set("input_schema", schema);
            body.putArray("tools").add(tool);
            var choice = body.putObject("tool_choice").put("type", forced ? "tool" : "auto");
            if (forced) choice.put("name", "probe");
        } else if (protocol == LlmApiProtocol.OPENAI_RESPONSES) {
            body.put("max_output_tokens", 64).put("store", false).put("input", "Call probe.");
            tool.put("type", "function").set("parameters", schema);
            body.putArray("tools").add(tool);
            if (forced) body.putObject("tool_choice").put("type", "function").put("name", "probe");
            else body.put("tool_choice", "auto");
        } else {
            body.put("max_completion_tokens", 64).put("store", false);
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

    private record Answer(int status, String body) {}

    private Answer call(LlmProbeTarget target, ObjectNode body) {
        return client.post()
                .uri(target.baseUrl().strip().replaceAll("/+$", "")
                        + target.apiProtocol().upstreamPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (target.apiProtocol() == LlmApiProtocol.ANTHROPIC_MESSAGES) {
                        headers.set("anthropic-version", "2023-06-01");
                        if (target.apiKey() != null) headers.set("x-api-key", target.apiKey());
                    } else if (target.apiKey() != null) {
                        if (target.authMode() == LlmAuthMode.API_KEY) headers.set("api-key", target.apiKey());
                        else headers.setBearerAuth(target.apiKey());
                    }
                })
                .body(body)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readNBytes(64 * 1024 + 1);
                    if (bytes.length > 64 * 1024) throw new IllegalArgumentException("Probe response too large");
                    return new Answer(response.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
                });
    }
}
