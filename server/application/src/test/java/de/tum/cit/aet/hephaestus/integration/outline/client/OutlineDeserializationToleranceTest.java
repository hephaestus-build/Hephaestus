package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Locks the tolerant-reader contract of the actual {@code outlineWebClient} decoder. The generated Outline
 * models carry no {@code @JsonIgnoreProperties}, so tolerance to Outline's many extra fields is decoder
 * configuration ({@code FAIL_ON_UNKNOWN_PROPERTIES} disabled in {@link OutlineClientConfig}), not an
 * annotation effect. This exercises the real bean — built from the Boot-autoconfigured Jackson mapper — with
 * a body carrying unknown fields at both the envelope level and inside a generated model.
 */
@Tag("unit")
@JsonTest
@ContextConfiguration(
        classes = JacksonAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class OutlineDeserializationToleranceTest {

    @Autowired
    private JsonMapper baseObjectMapper;

    @Test
    void ignoresUnknownFieldsAtEnvelopeAndModelLevel() {
        // Unknown envelope siblings (ok/status/policies) AND an unknown model field, plus a nested object
        // the model doesn't declare — a real Outline response is far wider than what we map.
        String body = """
            {
              "ok": true,
              "status": 200,
              "policies": [{"id": "p1", "abilities": {"read": true}}],
              "data": {
                "id": "7d11d73d-1b36-43e3-9f31-b43e98c69b5b",
                "title": "Tolerant Reader",
                "url": "/doc/tolerant-reader-JpRHHJuY8M",
                "collectionId": "fbe68839-b131-44e2-bb93-0bc533d39193",
                "someBrandNewOutlineFieldAddedUpstream": 42,
                "insights": {"views": 7, "nested": {"deep": true}}
              },
              "pagination": {"offset": 0, "limit": 25, "somethingNew": "x"}
            }
            """;

        WebClient stubbed = new OutlineClientConfig(new OutlineRateLimitTracker(new SimpleMeterRegistry()))
                .outlineWebClient(baseObjectMapper)
                .mutate()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()))
                .build();

        OutlineEnvelope<OutlineDocumentModel> envelope = stubbed.post()
                .uri("https://wiki.example.com/api/documents.info")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<OutlineEnvelope<OutlineDocumentModel>>() {})
                .block();

        assertThat(envelope).isNotNull();
        assertThat(envelope.data()).isNotNull();
        assertThat(envelope.data().getId()).isEqualTo("7d11d73d-1b36-43e3-9f31-b43e98c69b5b");
        assertThat(envelope.data().getTitle()).isEqualTo("Tolerant Reader");
        assertThat(envelope.data().getCollectionId()).isEqualTo("fbe68839-b131-44e2-bb93-0bc533d39193");
        assertThat(envelope.pagination()).isNotNull();
    }

    @Test
    void listEnvelopeToleratesUnknownFields() {
        String body = """
            {
              "data": [
                {"id": "doc-1", "title": "One", "unexpected": "field"},
                {"id": "doc-2", "title": "Two"}
              ],
              "pagination": {"offset": 0, "limit": 100}
            }
            """;

        WebClient stubbed = new OutlineClientConfig(new OutlineRateLimitTracker(new SimpleMeterRegistry()))
                .outlineWebClient(baseObjectMapper)
                .mutate()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()))
                .build();

        OutlineEnvelope<List<OutlineDocumentModel>> envelope = stubbed.post()
                .uri("https://wiki.example.com/api/documents.list")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<OutlineEnvelope<List<OutlineDocumentModel>>>() {})
                .block();

        assertThat(envelope).isNotNull();
        assertThat(envelope.data()).hasSize(2);
        assertThat(envelope.data().get(0).getId()).isEqualTo("doc-1");
    }

    /**
     * The other half of the tolerant-reader contract, exercised on the <b>production decode path</b>: a
     * poison enum value from Outline must not abort the whole {@code collections.list} response.
     *
     * <p>Outline ships enum values ahead of its published spec — a collection {@code permission} of
     * {@code "admin"} is outside the generated {@code OutlinePermission} enum ({@code read}/{@code read_write}).
     * openapi-generator's default {@code @JsonCreator fromValue} throws {@code IllegalArgumentException} on an
     * unknown value, and the mapper's {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} feature rescues that <em>only on
     * the blocking {@code readValue} path</em> — not on the reactive {@code Jackson2JsonDecoder} codec the
     * running client uses, where the throw surfaces as a {@code DecodingException} and breaks sync for the host.
     *
     * <p>The fix is contract-level: the enums are generated with {@code enumUnknownDefaultCase=true}
     * (see {@code server/generated-clients/build.gradle.kts}), so {@code fromValue} returns {@code UNKNOWN_DEFAULT_OPEN_API} instead of
     * throwing — path-independent. This test drives the <em>real</em> {@code outlineWebClient} decoder (not
     * {@code readValue}); reverting the generator knob restores the throwing {@code fromValue}, the decode
     * aborts, and this test fails — exactly the regression it guards.
     */
    @Test
    void unknownEnumValueDecodesToUnknownDefaultOnWebClientPath() {
        // A collection list page whose sole row carries an out-of-spec permission ("admin").
        String body = """
            {
              "data": [
                {
                  "id": "fbe68839-b131-44e2-bb93-0bc533d39193",
                  "name": "Engineering Docs",
                  "urlId": "j4Gxqv1NCn",
                  "permission": "admin"
                }
              ],
              "pagination": {"offset": 0, "limit": 100}
            }
            """;

        WebClient stubbed = new OutlineClientConfig(new OutlineRateLimitTracker(new SimpleMeterRegistry()))
                .outlineWebClient(baseObjectMapper)
                .mutate()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()))
                .build();

        // The production decode path: retrieve().bodyToMono(...), the reactive Jackson codec — NOT readValue.
        OutlineEnvelope<List<OutlineCollectionModel>> envelope = stubbed.post()
                .uri("https://wiki.example.com/api/collections.list")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<OutlineEnvelope<List<OutlineCollectionModel>>>() {})
                .block();

        assertThat(envelope).isNotNull();
        assertThat(envelope.data()).hasSize(1);
        OutlineCollectionModel collection = envelope.data().get(0);
        // The poison enum decodes to the generated unknown-default rather than aborting the whole page.
        // Asserted by wire value (not the constant) so a reverted generator knob fails at decode, not compile.
        assertThat(collection.getPermission()).isNotNull();
        assertThat(collection.getPermission().getValue()).isEqualTo("unknown_default_open_api");
        // Sibling fields on the same model still map — tolerance is scoped to the poison field only.
        assertThat(collection.getId()).isEqualTo("fbe68839-b131-44e2-bb93-0bc533d39193");
        assertThat(collection.getName()).isEqualTo("Engineering Docs");
        assertThat(collection.getUrlId()).isEqualTo("j4Gxqv1NCn");
    }
}
