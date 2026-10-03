package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.OutlineSubscription;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource.SecretLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEvent;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEventRepository;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineDocumentSyncScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives {@link OutlineWebhookSignatureVerifier}, {@link OutlineSubjectKeyDeriver},
 * {@link OutlineSubjectParser}, and {@link OutlineWebhookMessageHandler} end-to-end against every
 * committed Outline webhook delivery body under {@code src/test/resources/outline}, captured live from
 * a self-hosted Outline instance's {@code webhook_deliveries} table rather than hand-authored.
 *
 * <p>For each fixture: (1) the raw bytes verify under a signature computed over them with a test
 * secret — proving the verifier's HMAC-over-exact-bytes contract survives real Outline JSON
 * formatting/whitespace; (2) the publisher-side subject the deriver builds falls under the
 * consumer-side subscription filter and the parser collapses it onto the handler's single logical key;
 * (3) the handler routes on the fixture's real {@code event} + {@code payload.id} — every committed
 * fixture carries a payload id, so a {@code documents.*} delivery refreshes that exact document and
 * appends the exact actor/clock to the event log (the no-id workspace-reconcile fallback is covered by
 * the hand-built {@code OutlineWebhookMessageHandlerTest}), while a {@code collections.*} delivery
 * refreshes the catalog instead.
 */
class OutlineWebhookFixtureRoutingTest extends BaseUnitTest {

    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/outline");
    private static final byte[] TEST_SECRET = "outline-fixture-test-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-11T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long WORKSPACE_ID = 42L;
    private static final long CONNECTION_ID = 7L;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final OutlineSubjectKeyDeriver DERIVER = new OutlineSubjectKeyDeriver(MAPPER);
    private static final OutlineSubjectParser PARSER = new OutlineSubjectParser();

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineDocumentSyncScheduler syncScheduler;

    @Mock
    private OutlineDocumentEventRepository documentEventRepository;

    @Mock
    private Connection connection;

    static Stream<Path> fixtures() throws IOException {
        if (!Files.isDirectory(FIXTURE_DIR)) {
            return Stream.empty();
        }
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyRealDeliveryFixture_verifiesRoutesAndActsCorrectly(Path fixture) throws IOException {
        byte[] body = Files.readAllBytes(fixture);
        JsonNode payload = MAPPER.readTree(body);
        String subscriptionId = payload.path("webhookSubscriptionId").asString("");
        String event = payload.path("event").asString("");
        String payloadId = payload.path("payload").path("id").asString("");
        String actorId = payload.path("actorId").asString("");
        String createdAtRaw = payload.path("createdAt").asString("");

        assertThat(subscriptionId)
                .as("fixture %s must carry a subscription id", fixture.getFileName())
                .isNotBlank();
        assertThat(event)
                .as("fixture %s must carry an event name", fixture.getFileName())
                .isNotBlank();
        assertThat(payloadId)
                .as("fixture %s must carry a payload id", fixture.getFileName())
                .isNotBlank();

        verifySignatureOverRealBytes(body);
        String subject = verifySubjectRoundTrip(payload, subscriptionId, event);
        verifyHandlerRouting(body, subscriptionId, event, payloadId, actorId, createdAtRaw);

        // The subject the deriver actually emitted for this fixture round-trips through the parser too.
        assertThat(PARSER.parse(subject).kind()).isEqualTo(IntegrationKind.OUTLINE);
    }

    private void verifySignatureOverRealBytes(byte[] body) {
        WebhookSecretSource secretSource = fixedSecretSource();
        OutlineWebhookSignatureVerifier verifier = new OutlineWebhookSignatureVerifier(secretSource, CLOCK);
        long ts = NOW.toEpochMilli();
        String header = "t=" + ts + ",s=" + sign(ts, body, TEST_SECRET);

        VerificationResult result = verifier.verify(new WebhookRequest(body, Map.of("Outline-Signature", header)));

        assertThat(result).isInstanceOf(VerificationResult.Verified.class);
    }

    private String verifySubjectRoundTrip(JsonNode payload, String subscriptionId, String event) {
        String subject = DERIVER.deriveSubject(payload, Map.of());
        String sanitizedEvent = event.toLowerCase(Locale.ROOT).replace('.', '~');
        assertThat(subject).isEqualTo("outline." + subscriptionId + "." + sanitizedEvent);

        String consumerFilterPrefix = ConsumerSubjectMath.subscriptionFilter("outline", subscriptionId)
                .replace(".>", ".");
        assertThat(subject).startsWith(consumerFilterPrefix);
        assertThat(PARSER.parse(subject))
                .isEqualTo(new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE));
        return subject;
    }

    private void verifyHandlerRouting(
            byte[] rawBody,
            String subscriptionId,
            String event,
            String payloadId,
            String actorId,
            String createdAtRaw) {
        when(connectionService.findOutlineSubscription(subscriptionId))
                .thenReturn(Optional.of(new OutlineSubscription(WORKSPACE_ID, "https://wiki.example.com", "secret")));
        lenient().when(connection.getId()).thenReturn(CONNECTION_ID);
        lenient()
                .when(connectionService.findActive(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(connection));

        OutlineWebhookMessageHandler handler = new OutlineWebhookMessageHandler(
                connectionService,
                syncScheduler,
                documentEventRepository,
                MAPPER,
                new OutlineOriginPolicy(Set.of("https://wiki.example.com")));

        // Route the fixture bytes exactly as they arrive off the wire — the handler only trusts
        // event/payload.id/actorId/createdAt for routing (never the document body), but feeding the
        // full real envelope (rather than a hand-built minimal one) exercises the actual JSON shape.
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(rawBody);

        handler.onMessage(message);

        if (event.startsWith("documents.")) {
            ArgumentCaptor<OutlineDocumentModel> model = ArgumentCaptor.forClass(OutlineDocumentModel.class);
            verify(syncScheduler).refreshDocumentNow(eq(WORKSPACE_ID), eq(event), eq(payloadId), model.capture());
            // Every committed fixture carries a full payload.model — the HMAC-authenticated metadata the
            // handler trusts, sparing the sync path its own documents.info round-trip.
            assertThat(model.getValue())
                    .as("event %s (payload id %s) must carry a usable payload.model", event, payloadId)
                    .isNotNull();
            assertThat(model.getValue().getId()).isEqualTo(payloadId);
            assertThat(model.getValue().getCollectionId()).isNotBlank();

            ArgumentCaptor<OutlineDocumentEvent> captor = ArgumentCaptor.forClass(OutlineDocumentEvent.class);
            verify(documentEventRepository).save(captor.capture());
            assertThat(captor.getValue().getWorkspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(captor.getValue().getConnectionId()).isEqualTo(CONNECTION_ID);
            assertThat(captor.getValue().getDocumentId()).isEqualTo(payloadId);
            assertThat(captor.getValue().getEventName()).isEqualTo(event);
            if (!actorId.isBlank()) {
                assertThat(captor.getValue().getActorSubject()).isEqualTo(actorId);
            }
            if (!createdAtRaw.isBlank()) {
                assertThat(captor.getValue().getOccurredAt()).isEqualTo(Instant.parse(createdAtRaw));
            }
        } else if (event.startsWith("collections.")) {
            verify(syncScheduler).refreshCollectionCatalogNow(WORKSPACE_ID, event, payloadId);
            verifyNoInteractions(documentEventRepository);
        } else {
            throw new AssertionError("Unhandled fixture event prefix: " + event);
        }
    }

    private static WebhookSecretSource fixedSecretSource() {
        return new WebhookSecretSource() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.OUTLINE;
            }

            @Override
            public Scope scope() {
                return Scope.SUBSCRIPTION;
            }

            @Override
            public Optional<byte[]> getSecret(SecretLookup lookup) {
                return Optional.of(TEST_SECRET);
            }
        };
    }

    private static String sign(long timestampMillis, byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampMillis + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
