package de.tum.cit.aet.hephaestus.testconfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub's GraphQL endpoint as a script behind the application's own client: the client, its document loading,
 * variables and headers stay real, and only the HTTP exchange answers from here, through default codecs. It holds
 * the comment threads of every pull request a test writes to, keyed by repository and number, so tests that share
 * a context never read each other's comments. A document it has no answer for fails as a GraphQL error, as a
 * refusing provider does.
 */
public final class ScriptedGitHub implements ExchangeFunction {

    private static final Pattern OPERATION = Pattern.compile("^\\s*(?:query|mutation)\\s+(\\w+)", Pattern.MULTILINE);

    private static final String COMMENT_ID = "IC_";

    private final ScriptedCommentThreads threads = new ScriptedCommentThreads(COMMENT_ID);
    private final JsonMapper json = JsonMapper.builder().build();

    public ScriptedCommentThreads threads() {
        return threads;
    }

    /** The node id GitHub gives a pull request, and the thread its comments are kept under. */
    public static String pullRequest(String nameWithOwner, int number) {
        return "PR_" + nameWithOwner + "#" + number;
    }

    /**
     * GitHub's REST budget endpoint, which the provider asks once per scope before its first GraphQL call. It
     * answers that request alone and passes every other one on.
     */
    public ExchangeFilterFunction restRateLimit() {
        return (request, next) -> request.url().getHost().equals("api.github.com")
                        && request.url().getPath().equals("/rate_limit")
                ? Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(json.writeValueAsString(Map.of(
                                "resources", Map.of("graphql", Map.of("limit", 5000, "remaining", 5000, "reset", 0)))))
                        .build())
                : next.exchange(request);
    }

    @Override
    public Mono<ClientResponse> exchange(ClientRequest request) {
        return body(request).map(body -> {
            JsonNode document = json.readTree(body);
            var matcher = OPERATION.matcher(document.path("query").asString());
            String operation = matcher.find() ? matcher.group(1) : "";
            Object answer = answer(operation, document.path("variables"));
            return ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(json.writeValueAsString(answer))
                    .build();
        });
    }

    private Object answer(String operation, JsonNode variables) {
        return switch (operation) {
            case "GetPullRequestNodeId" ->
                data(Map.of(
                        "repository",
                        Map.of("pullRequest", Map.of("id", pullRequest(path(variables), number(variables))))));
            case "AddPullRequestComment" -> {
                String id = threads.append(
                        variables.path("subjectId").asString(),
                        COMMENT_ID,
                        variables.path("body").asString());
                yield data(Map.of("addComment", Map.of("commentEdge", Map.of("node", Map.of("id", id)))));
            }
            case "GetPullRequestCommentsNewest" ->
                data(Map.of(
                        "repository",
                        Map.of("pullRequest", comments(pullRequest(path(variables), number(variables))))));
            default -> Map.of("errors", List.of(Map.of("message", "Unscripted GitHub document " + operation)));
        };
    }

    /** Every scripted comment was written through the application, so the viewer wrote it. */
    private Map<String, Object> comments(String thread) {
        return Map.of(
                "comments",
                Map.of(
                        "nodes",
                        threads.on(thread).stream()
                                .map(comment ->
                                        Map.of("id", comment.id(), "body", comment.body(), "viewerDidAuthor", true))
                                .toList(),
                        "pageInfo",
                        Map.of("hasPreviousPage", false)));
    }

    private static Map<String, Object> data(Map<String, Object> fields) {
        return Map.of("data", fields);
    }

    private static String path(JsonNode variables) {
        return variables.path("owner").asString() + "/" + variables.path("name").asString();
    }

    private static int number(JsonNode variables) {
        return variables.path("number").asInt();
    }

    private static Mono<String> body(ClientRequest request) {
        var written = new MockClientHttpRequest(request.method(), request.url());
        return request.body()
                .insert(written, new BodyInserter.Context() {
                    @Override
                    public List<HttpMessageWriter<?>> messageWriters() {
                        return ExchangeStrategies.withDefaults().messageWriters();
                    }

                    @Override
                    public Optional<ServerHttpRequest> serverRequest() {
                        return Optional.empty();
                    }

                    @Override
                    public Map<String, Object> hints() {
                        return Map.of();
                    }
                })
                .then(Mono.defer(written::getBodyAsString));
    }
}
