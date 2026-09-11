package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineNavigationNode;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the tolerant-reader contract for the generated Outline models against response bodies captured from a
 * real self-hosted Outline instance: {@code documents.list}, {@code documents.info} (a root document and a
 * nested child with {@code parentDocumentId} set), {@code collections.list}, {@code collections.documents}
 * (a real nested tree), and {@code webhookSubscriptions.list} (signing {@code secret} redacted).
 *
 * <p>Real Outline responses carry many more fields than we map (e.g. {@code documents.list}'s {@code text},
 * {@code tasks}, {@code revision}, {@code publishedAt}, …). Booting the real Spring-Boot-autoconfigured
 * Jackson&nbsp;3 mapper, rather than a hand-built one, exercises that unknown-field tolerance is mapper
 * configuration — the generated models cannot carry {@code @JsonIgnoreProperties}. The payloads are read
 * through the hand-written {@link OutlineEnvelope} wrapper, exactly as {@link OutlineApiClient} reads them.
 */
@Tag("unit")
@JsonTest
@ContextConfiguration(
        classes = JacksonAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class OutlineApiFixtureDeserializationTest {

    @Autowired
    private JsonMapper jackson3;

    @Test
    void documentsList_mapsRealDocumentsIncludingTheNestedChild() throws Exception {
        OutlineEnvelope<List<OutlineDocumentModel>> response =
                deserialize("/outline-api/documents.list.json", new TypeReference<>() {});

        assertThat(response.data()).isNotNull().isNotEmpty();
        OutlineDocumentModel parent = findById(response.data(), "7d11d73d-1b36-43e3-9f31-b43e98c69b5b");
        assertThat(parent.getTitle()).isEqualTo("Fixture Capture Doc Renamed");
        assertThat(parent.getUrl()).isEqualTo("/doc/fixture-capture-doc-renamed-JpRHHJuY8M");
        assertThat(parent.getUrlId()).isEqualTo("JpRHHJuY8M");
        assertThat(parent.getParentDocumentId()).isNull();
        assertThat(parent.getCollectionId()).isEqualTo("fbe68839-b131-44e2-bb93-0bc533d39193");
        assertThat(parent.getCreatedAt()).isNotNull();
        assertThat(parent.getUpdatedAt()).isNotNull();
        assertThat(parent.getCreatedBy()).isNotNull();
        assertThat(parent.getCreatedBy().getName()).isEqualTo("Felix Admin");
        assertThat(parent.getCollaboratorIds()).isNotEmpty();

        // The nested child — proves parentDocumentId maps correctly off a real tree, not just a flat list.
        OutlineDocumentModel child = findById(response.data(), "cec98e59-623c-4392-a343-6e96b0995e51");
        assertThat(child.getTitle()).isEqualTo("Fixture Capture Child 2");
        assertThat(child.getParentDocumentId()).isEqualTo("7d11d73d-1b36-43e3-9f31-b43e98c69b5b");
    }

    @Test
    void documentsInfo_mapsARootDocumentWithNoParent() throws Exception {
        OutlineEnvelope<OutlineDocumentModel> response =
                deserialize("/outline-api/documents.info.json", new TypeReference<>() {});

        assertThat(response.data()).isNotNull();
        OutlineDocumentModel data = response.data();
        assertNotNull(data);
        assertThat(data.getId()).isEqualTo("7d11d73d-1b36-43e3-9f31-b43e98c69b5b");
        assertThat(data.getTitle()).isEqualTo("Fixture Capture Doc Renamed");
        assertThat(data.getUrl()).isEqualTo("/doc/fixture-capture-doc-renamed-JpRHHJuY8M");
        assertThat(data.getParentDocumentId()).isNull();
        assertThat(data.getCollectionId()).isEqualTo("fbe68839-b131-44e2-bb93-0bc533d39193");
        assertThat(data.getCreatedAt()).isEqualTo(Instant.parse("2026-07-11T05:23:17.240Z"));
        assertThat(data.getCreatedBy()).isNotNull();
        assertThat(data.getUpdatedBy()).isNotNull();
        assertThat(data.getCollaboratorIds()).contains("99bdd8e2-176a-42fa-ba0c-4f9c4ce6caa9");
    }

    @Test
    void documentsInfo_mapsANestedChildWithItsParentId() throws Exception {
        OutlineEnvelope<OutlineDocumentModel> response =
                deserialize("/outline-api/documents.info.child.json", new TypeReference<>() {});

        OutlineDocumentModel data = response.data();
        assertNotNull(data);
        assertThat(data.getId()).isEqualTo("cec98e59-623c-4392-a343-6e96b0995e51");
        assertThat(data.getParentDocumentId()).isEqualTo("7d11d73d-1b36-43e3-9f31-b43e98c69b5b");
        assertThat(data.getCollectionId()).isEqualTo("fbe68839-b131-44e2-bb93-0bc533d39193");
    }

    @Test
    void collectionsList_mapsTheRealCollectionCatalog() throws Exception {
        OutlineEnvelope<List<OutlineCollectionModel>> response =
                deserialize("/outline-api/collections.list.json", new TypeReference<>() {});

        assertThat(response.data()).isNotNull().isNotEmpty();
        OutlineCollectionModel engineering = response.data().stream()
                .filter(c -> "fbe68839-b131-44e2-bb93-0bc533d39193".equals(c.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(engineering.getName()).isEqualTo("Engineering Docs");
        assertThat(engineering.getUrlId()).isEqualTo("j4Gxqv1NCn");
    }

    @Test
    void collectionsDocuments_mapsTheRealNestedTree() throws Exception {
        OutlineEnvelope<List<OutlineNavigationNode>> response =
                deserialize("/outline-api/collections.documents.json", new TypeReference<>() {});

        assertThat(response.data()).isNotNull().isNotEmpty();
        OutlineNavigationNode parent = response.data().stream()
                .filter(n -> "7d11d73d-1b36-43e3-9f31-b43e98c69b5b".equals(n.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(parent.getTitle()).isEqualTo("Fixture Capture Doc Renamed");
        assertThat(parent.getChildren()).isNotNull().isNotEmpty();
        assertThat(parent.getChildren().get(0).getId()).isEqualTo("cec98e59-623c-4392-a343-6e96b0995e51");
        assertThat(parent.getChildren().get(0).getUrl()).isEqualTo("/doc/fixture-capture-child-2-OHQpaAib7z");
    }

    @Test
    void webhookSubscriptionsList_mapsTheRegisteredSubscription() throws Exception {
        OutlineEnvelope<List<OutlineWebhookSubscription>> response =
                deserialize("/outline-api/webhookSubscriptions.list.json", new TypeReference<>() {});

        assertThat(response.data()).isNotNull().hasSize(1);
        OutlineWebhookSubscription subscription = response.data().get(0);
        assertThat(subscription.getId()).isEqualTo("451e2dc4-010e-44e7-8052-8537a3927ba8");
        assertThat(subscription.getEnabled()).isTrue();
        assertThat(subscription.getEvents()).contains("documents.update", "collections.delete");
        // The secret itself is redacted in the committed fixture — the model field still maps.
        assertThat(subscription.getUrl()).isEqualTo("https://hephaestus.example.com/webhooks/outline");
    }

    private static OutlineDocumentModel findById(List<OutlineDocumentModel> data, String id) {
        return data.stream().filter(m -> id.equals(m.getId())).findFirst().orElseThrow();
    }

    private <T> T deserialize(String classpath, TypeReference<T> type) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(classpath)) {
            assertThat(in).as("fixture %s must be on the classpath", classpath).isNotNull();
            // Read through the exact tolerant policy the running client uses. These fixtures exercise the
            // unknown-field tolerance (FAIL_ON_UNKNOWN_PROPERTIES=false); the out-of-enum tolerance
            // (generator-level enumUnknownDefaultCase) is pinned separately by
            // OutlineDeserializationToleranceTest on the WebClient path with a poison "admin" permission.
            return OutlineClientConfig.tolerantMapper(jackson3).readValue(in.readAllBytes(), type);
        }
    }
}
