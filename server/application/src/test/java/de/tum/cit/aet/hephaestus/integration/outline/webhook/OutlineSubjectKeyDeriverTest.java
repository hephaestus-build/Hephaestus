package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deriver builds {@code outline.<sub>.<event>} subjects (dots → {@code ~}, no case-folding) and a
 * stable, precise dedup key from subscription + event + document id, falling back to a body hash when
 * that triple is incomplete.
 */
class OutlineSubjectKeyDeriverTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final OutlineSubjectKeyDeriver deriver = new OutlineSubjectKeyDeriver(mapper);

    private JsonNode json(String body) {
        return mapper.readTree(body);
    }

    @Test
    void deriveSubject_sanitizesEventDots() {
        JsonNode payload = json("{\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\"}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("outline.sub-1.documents~update");
    }

    @Test
    void deriveSubject_fallsBackToUnknownTokens() {
        assertThat(deriver.deriveSubject(json("{}"), Map.of())).isEqualTo("outline.unknown.unknown");
    }

    @Test
    void deriveDedupKey_isTheDeliveryId_stableAcrossRetries() {
        // Outline retries a failed delivery with the same envelope id — that id is the dedup key.
        byte[] body =
                "{\"id\":\"delivery-1\",\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\",\"payload\":{\"id\":\"doc-9\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        String first = deriver.deriveDedupKey(body, Map.of());
        String second = deriver.deriveDedupKey(body, Map.of());
        assertThat(first).isEqualTo(second).isEqualTo("outline-delivery-1");
    }

    @Test
    void deriveDedupKey_differsForConsecutiveEditsOfTheSameDocument() {
        // Two edits of one document are distinct deliveries; a document-keyed dedup would swallow
        // the second edit inside the provider replay window.
        byte[] first =
                "{\"id\":\"delivery-1\",\"webhookSubscriptionId\":\"s\",\"event\":\"documents.update\",\"payload\":{\"id\":\"a\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] second =
                "{\"id\":\"delivery-2\",\"webhookSubscriptionId\":\"s\",\"event\":\"documents.update\",\"payload\":{\"id\":\"a\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        assertThat(deriver.deriveDedupKey(first, Map.of())).isNotEqualTo(deriver.deriveDedupKey(second, Map.of()));
    }

    @Test
    void deriveDedupKey_withoutDeliveryId_differsAcrossDistinctBodies() {
        byte[] update = "{\"webhookSubscriptionId\":\"s\",\"event\":\"documents.update\",\"payload\":{\"id\":\"a\"}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] delete = "{\"webhookSubscriptionId\":\"s\",\"event\":\"documents.delete\",\"payload\":{\"id\":\"a\"}}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(deriver.deriveDedupKey(update, Map.of())).isNotEqualTo(deriver.deriveDedupKey(delete, Map.of()));
    }

    @Test
    void deriveDedupKey_fallsBackToBodyHashWithoutDeliveryId() {
        byte[] body =
                "{\"webhookSubscriptionId\":\"s\",\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(deriver.deriveDedupKey(body, Map.of()))
                .startsWith("outline-")
                .hasSize("outline-".length() + 32);
    }
}
