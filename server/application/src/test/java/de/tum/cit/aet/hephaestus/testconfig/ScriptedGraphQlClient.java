package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * An {@link HttpGraphQlClient} that answers each request from a script, so a provider adapter runs its own
 * document selection, variables and response reading against a provider the test controls.
 */
public final class ScriptedGraphQlClient {

    private ScriptedGraphQlClient() {}

    public record Request(String document, Map<String, @Nullable Object> variables) {
        public String text(String name) {
            return String.valueOf(variables.get(name));
        }
    }

    public static HttpGraphQlClient of(Function<Request, Mono<ClientGraphQlResponse>> script) {
        return mock(HttpGraphQlClient.class, client -> {
            if (!client.getMethod().getName().equals("documentName")) return RETURNS_DEFAULTS.answer(client);
            String document = client.getArgument(0);
            Map<String, @Nullable Object> variables = new HashMap<>();
            return mock(HttpGraphQlClient.RequestSpec.class, spec -> switch (spec.getMethod()
                    .getName()) {
                case "variable" -> {
                    variables.put(spec.getArgument(0), spec.getArgument(1));
                    yield spec.getMock();
                }
                case "execute" ->
                    Mono.defer(() ->
                            script.apply(new Request(document, Collections.unmodifiableMap(new HashMap<>(variables)))));
                default -> RETURNS_DEFAULTS.answer(spec);
            });
        });
    }

    /** A response without errors whose field at each path reads, as a value or an entity, what {@code fields} holds. */
    public static Mono<ClientGraphQlResponse> respond(Map<String, ?> fields) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class, invocation -> switch (invocation
                .getMethod()
                .getName()) {
            case "getErrors" -> List.of();
            case "field" -> field(fields.get(invocation.<String>getArgument(0)));
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
        return Mono.just(response);
    }

    private static ClientResponseField field(@Nullable Object value) {
        return mock(
                ClientResponseField.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "getValue", "toEntity" -> value;
                    default -> RETURNS_DEFAULTS.answer(invocation);
                });
    }
}
