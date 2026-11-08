package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real document loading and JSON decoding with a provider transport that never resolves a host. */
public final class FeedbackReadbackFixtures {
    private FeedbackReadbackFixtures() {}

    public static HttpGraphQlClient client(String vendor, JsonNode data) {
        var body = new JsonMapper().createObjectNode().set("data", data).toString();
        return HttpGraphQlClient.builder(WebClient.builder()
                        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", "application/json")
                                .body(body)
                                .build())))
                .url("https://scm.example.test/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(new ClassPathResource("graphql/" + vendor + "/operations/")),
                        List.of(".graphql"),
                        List.of()))
                .build();
    }
}
