package de.tum.cit.aet.hephaestus.integration.scm.github.graphql;

import java.time.Duration;
import java.util.Objects;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Decodes synthetic responses through Spring's client with the GitHub mapper and no network. */
public final class GitHubGraphQlTestResponses {
    private GitHubGraphQlTestResponses() {}

    public static ClientGraphQlResponse decode(String body) {
        return Objects.requireNonNull(HttpGraphQlClient.builder(WebClient.builder()
                        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(body)
                                .build())))
                .codecConfigurer(codecs -> {
                    var mapper = GitHubGraphQlTestMapper.create();
                    codecs.customCodecs().register(new JacksonJsonEncoder(mapper));
                    codecs.customCodecs().register(new JacksonJsonDecoder(mapper));
                })
                .url("https://github.example.test/graphql")
                .build()
                .document("{ __typename }")
                .execute()
                .block(Duration.ofSeconds(2)));
    }
}
