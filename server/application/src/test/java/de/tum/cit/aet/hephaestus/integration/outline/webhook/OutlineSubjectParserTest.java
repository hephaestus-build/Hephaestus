package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser collapses any well-formed {@code outline.<sub>.<event>} subject onto the single logical
 * key {@code EventTypeKey(OUTLINE, "document")} and rejects malformed subjects. The round-trip case
 * proves a subject the deriver emits is (a) matched by the consumer's subscription filter prefix and
 * (b) parses back to the handler's key — publisher and consumer agree.
 */
class OutlineSubjectParserTest extends BaseUnitTest {

    private final OutlineSubjectParser parser = new OutlineSubjectParser();

    @Test
    void parse_collapsesEveryDocumentEventToTheHandlerKey() {
        assertThat(parser.parse("outline.sub-1.documents~update"))
                .isEqualTo(new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE));
        assertThat(parser.parse("outline.sub-1.documents~delete"))
                .isEqualTo(new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE));
    }

    @Test
    void parse_rejectsMalformedSubjects() {
        assertThatThrownBy(() -> parser.parse("outline.sub-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("gitlab.a.b.c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("outline.sub-1.")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsAnUnsanitizedEvent() {
        // The deriver has always replaced '.' in the event with '~', so a fourth component has no publisher.
        assertThatThrownBy(() -> parser.parse("outline.sub-1.documents.update"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 3 components");
    }

    @Test
    void deriverAndParserAgree_roundTrip() {
        String subscriptionId = "sub-abc";
        OutlineSubjectKeyDeriver deriver =
                new OutlineSubjectKeyDeriver(JsonMapper.builder().build());
        var payload = JsonMapper.builder()
                .build()
                .readTree("{\"webhookSubscriptionId\":\"" + subscriptionId + "\",\"event\":\"documents.update\"}");

        String subject = deriver.deriveSubject(payload, Map.of());

        // Consumer subscribes to outline.<sub>.> — the deriver's subject must fall under it.
        String filterPrefix = ConsumerSubjectMath.subscriptionFilter("outline", subscriptionId)
                .replace(".>", ".");
        assertThat(subject).startsWith(filterPrefix);
        // And that subject parses back to the single handler key.
        assertThat(parser.parse(subject))
                .isEqualTo(new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE));
    }
}
