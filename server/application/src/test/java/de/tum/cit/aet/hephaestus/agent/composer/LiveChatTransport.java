package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Sends a composer's chat request bytes to a live endpoint, without the proxy that fences and bills them. */
final class LiveChatTransport implements ModelSession.Transport {
    private static final JsonMapper MAPPER = new JsonMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final LiveLlmCredentials creds;
    private @Nullable String path;
    private final Set<String> servedModels = new HashSet<>();

    LiveChatTransport(LiveLlmCredentials creds) {
        this.creds = creds;
    }

    /**
     * The model names the endpoint reported serving since the last {@link #resetServedModels}, which can differ from
     * the one requested.
     */
    Set<String> servedModels() {
        return Set.copyOf(servedModels);
    }

    /**
     * Answers each of a composer's model calls with the live model, under the model's own name, and keeps every
     * answer, a failed call's too.
     */
    Function<byte[], ModelSession.Answer> answering(String model, List<ModelSession.Answer> answers) {
        return body -> {
            var request = (ObjectNode) MAPPER.readTree(body);
            request.put("model", model);
            var answer = call(MAPPER.writeValueAsBytes(request));
            answers.add(answer);
            return answer;
        };
    }

    /** Starts a new measurement, so that one case reports only the models that served its own calls. */
    void resetServedModels() {
        servedModels.clear();
    }

    @Override
    public ModelSession.Answer call(byte[] body) {
        String base = creds.baseUrl().replaceAll("/+$", "");
        // Endpoints differ in whether their base URL already ends with the API version.
        for (String candidate : path == null ? List.of("/chat/completions", "/v1/chat/completions") : List.of(path)) {
            var answer = send(base + candidate, body);
            if (answer.status() != 404) {
                path = candidate;
                return answer;
            }
        }
        return new ModelSession.Answer(404, new byte[0], true, null);
    }

    private ModelSession.Answer send(String url, byte[] body) {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("authorization", "Bearer " + creds.apiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                String served = MAPPER.readTree(response.body()).path("model").asString("");
                if (!served.isBlank()) servedModels.add(served);
            }
            return new ModelSession.Answer(response.statusCode(), response.body(), true, null);
        } catch (IOException failed) {
            return new ModelSession.Answer(503, new byte[0], true, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ModelSession.Answer(503, new byte[0], true, null);
        }
    }
}
