package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends a composer's chat request bytes to a live endpoint, without the proxy that fences and bills them. The composer
 * streams its calls and reads one body, so this transport assembles a streamed answer with the server's own
 * {@link ComposerStreamAnswer}, as the proxy does.
 */
final class LiveChatTransport {
    private static final JsonMapper MAPPER = new JsonMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final LiveLlmCredentials creds;
    private final Set<String> servedModels = new HashSet<>();
    private int requests;

    LiveChatTransport(LiveLlmCredentials creds) {
        this.creds = creds;
    }

    /**
     * The model names the endpoint reported serving since the last {@link #startCase}, which can differ from
     * the one requested.
     */
    Set<String> servedModels() {
        return Set.copyOf(servedModels);
    }

    /**
     * Answers each of a composer's model calls with the live model, under the model's own name, and keeps every
     * answer, a failed call's too.
     */
    Function<byte[], ModelAnswer> answering(String model, List<ModelAnswer> answers) {
        return body -> {
            var request = (ObjectNode) MAPPER.readTree(body);
            request.put("model", model);
            var answer = call(MAPPER.writeValueAsBytes(request));
            answers.add(answer);
            return answer;
        };
    }

    /**
     * Starts a new measurement, so that one case reports only the models that served its own calls and the requests it
     * sent.
     */
    void startCase() {
        servedModels.clear();
        requests = 0;
    }

    /** The HTTP requests sent since the last {@link #startCase}: one for each model call, never a second one. */
    int requests() {
        return requests;
    }

    ModelAnswer call(byte[] body) {
        requests++;
        return send(creds.chatCompletions(), body);
    }

    private ModelAnswer send(URI url, byte[] body) {
        var request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofMinutes(10))
                .header("authorization", "Bearer " + creds.apiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return new ModelAnswer(response.statusCode(), response.body(), null);
            }
            if (!isEventStream(response)) {
                noteServedModel(MAPPER.readTree(response.body()));
                return new ModelAnswer(200, response.body(), null);
            }
            var streamed = new ComposerStreamAnswer(LlmApiProtocol.OPENAI_COMPLETIONS, MAPPER);
            streamed.accept(response.body());
            JsonNode answer = streamed.answer();
            // A stream cut before its end has no answer: the endpoint failed, as the proxy reports it.
            if (answer == null) return new ModelAnswer(503, new byte[0], null);
            noteStreamedModel(response.body());
            return new ModelAnswer(200, MAPPER.writeValueAsBytes(answer), null);
        } catch (IOException failed) {
            return new ModelAnswer(503, new byte[0], null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ModelAnswer(503, new byte[0], null);
        }
    }

    private static boolean isEventStream(HttpResponse<byte[]> response) {
        return response.headers()
                .firstValue("content-type")
                .map(type -> type.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                .orElse(false);
    }

    private void noteServedModel(JsonNode answer) {
        String served = answer.path("model").asString("");
        if (!served.isBlank()) servedModels.add(served);
    }

    /** The model that a stream's chunks name: the assembled answer keeps the reply, not the chunks' model. */
    private void noteStreamedModel(byte[] stream) {
        for (String line : new String(stream, StandardCharsets.UTF_8).lines().toList()) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if (data.isEmpty() || data.equals("[DONE]")) continue;
            try {
                noteServedModel(MAPPER.readTree(data));
            } catch (JacksonException unreadable) {
                // An event that is not JSON names no model; the assembler has already judged the stream.
            }
        }
    }
}
