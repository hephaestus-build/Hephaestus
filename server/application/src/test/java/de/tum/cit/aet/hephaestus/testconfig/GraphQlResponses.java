package de.tum.cit.aet.hephaestus.testconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.GraphQlRequest;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.GraphQlTransport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A GraphQL response decoded by Spring's own client from a response body, so a field's value and its errors — at, above
 * or below its path — read as they do for a provider's reply.
 */
public final class GraphQlResponses {

    private GraphQlResponses() {}

    /**
     * The response GraphQL would send with {@code data} and {@code errors}, each error a map with a {@code message} and
     * a {@code path} such as {@code List.of("project", "mergeRequest", "headPipeline")}.
     */
    public static ClientGraphQlResponse of(@Nullable Map<String, ?> data, List<Map<String, ?>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }
        GraphQlTransport transport = new GraphQlTransport() {
            @Override
            public Mono<GraphQlResponse> execute(GraphQlRequest request) {
                return Mono.just(GraphQlTransport.createResponse(body));
            }

            @Override
            public Flux<GraphQlResponse> executeSubscription(GraphQlRequest request) {
                return Flux.error(new UnsupportedOperationException("No subscriptions"));
            }
        };
        return Objects.requireNonNull(GraphQlClient.builder(transport)
                .build()
                .document("{ stub }")
                .execute()
                .block());
    }

    /** One GraphQL error at {@code path}. */
    public static Map<String, ?> error(String message, Object... path) {
        return Map.of("message", message, "path", List.of(path));
    }
}
