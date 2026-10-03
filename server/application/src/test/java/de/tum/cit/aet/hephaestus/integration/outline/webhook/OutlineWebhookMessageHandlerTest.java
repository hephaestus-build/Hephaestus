package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.OutlineSubscription;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEvent;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEventRepository;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineDocumentSyncScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer-side handler resolves the body's subscription id to its workspace and routes on the
 * body's event name: {@code documents.*} with a payload id → targeted document refresh, without one →
 * whole-workspace reconcile fallback; {@code collections.*} → catalog refresh; anything else is
 * acknowledged without work. A delivery whose subscription no longer resolves (disconnected between
 * publish and consume) is a no-op rather than a NAK loop. Every {@code documents.*} delivery also
 * appends one row to the per-document event log — best-effort, so a log failure never blocks routing.
 */
class OutlineWebhookMessageHandlerTest extends BaseUnitTest {

    private static final long CONNECTION_ID = 7L;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private OutlineDocumentSyncScheduler syncScheduler;

    @Mock
    private OutlineDocumentEventRepository documentEventRepository;

    @Mock
    private Connection connection;

    private OutlineWebhookMessageHandler handler() {
        return new OutlineWebhookMessageHandler(
                connectionService,
                syncScheduler,
                documentEventRepository,
                JsonMapper.builder().build(),
                new OutlineOriginPolicy(Set.of("https://wiki.example.com")));
    }

    private static Message message(String subscriptionId, String event, @Nullable String payloadId) {
        return message(subscriptionId, event, payloadId, null, null);
    }

    private static Message message(
            String subscriptionId,
            String event,
            @Nullable String payloadId,
            @Nullable String actorId,
            @Nullable String createdAt) {
        Message msg = mock(Message.class);
        String payload = payloadId == null ? "{}" : "{\"id\":\"" + payloadId + "\"}";
        StringBuilder body = new StringBuilder("{\"webhookSubscriptionId\":\"")
                .append(subscriptionId)
                .append("\",\"event\":\"")
                .append(event)
                .append("\"");
        if (actorId != null) {
            body.append(",\"actorId\":\"").append(actorId).append("\"");
        }
        if (createdAt != null) {
            body.append(",\"createdAt\":\"").append(createdAt).append("\"");
        }
        body.append(",\"payload\":").append(payload).append("}");
        when(msg.getData()).thenReturn(body.toString().getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    private void resolves(String subscriptionId, long workspaceId) {
        when(connectionService.findOutlineSubscription(subscriptionId))
                .thenReturn(Optional.of(new OutlineSubscription(workspaceId, "https://wiki.example.com", "secret")));
        lenient().when(connection.getId()).thenReturn(CONNECTION_ID);
        lenient()
                .when(connectionService.findActive(workspaceId, IntegrationKind.OUTLINE))
                .thenReturn(Optional.of(connection));
    }

    @Test
    void removedOriginIsNotPersistedOrRefreshed() {
        when(connectionService.findOutlineSubscription("sub-1"))
                .thenReturn(Optional.of(new OutlineSubscription(42L, "https://removed.example.com", "secret")));

        handler().onMessage(message("sub-1", "documents.update", "doc-9"));

        verifyNoInteractions(syncScheduler, documentEventRepository);
    }

    @Test
    void documentEventWithPayloadId_refreshesThatDocument() {
        resolves("sub-1", 42L);
        OutlineWebhookMessageHandler handler = handler();
        // The handler registers under the single collapsed document key the parser folds every event onto.
        assertThat(handler.key().eventType()).isEqualTo(OutlineWebhookMessageHandler.EVENT_TYPE);

        handler.onMessage(message("sub-1", "documents.update", "doc-9"));

        verify(syncScheduler).refreshDocumentNow(42L, "documents.update", "doc-9", null);
        verify(syncScheduler, never()).syncWorkspaceNow(anyLong());
    }

    @Test
    void documentDeleteEvent_routesTheEventNameThrough() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "documents.delete", "doc-9"));

        verify(syncScheduler).refreshDocumentNow(42L, "documents.delete", "doc-9", null);
    }

    @Test
    void documentEventWithoutPayloadId_fallsBackToWorkspaceReconcile() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "documents.update", null));

        verify(syncScheduler).syncWorkspaceNow(42L);
        verify(syncScheduler, never()).refreshDocumentNow(anyLong(), anyString(), anyString(), any());
        // No document id → nothing to attribute an event row to.
        verifyNoInteractions(documentEventRepository);
    }

    @Test
    void collectionEvent_refreshesTheCatalog() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "collections.delete", "col-3"));

        verify(syncScheduler).refreshCollectionCatalogNow(42L, "collections.delete", "col-3");
        // The event log is a documents.* trail only.
        verifyNoInteractions(documentEventRepository);
    }

    @Test
    void collectionEventWithoutPayloadId_passesNull() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "collections.update", null));

        verify(syncScheduler).refreshCollectionCatalogNow(42L, "collections.update", null);
    }

    @Test
    void unknownEvent_isAcknowledgedWithoutWork() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "webhookSubscriptions.update", "x"));

        verifyNoInteractions(syncScheduler);
        verifyNoInteractions(documentEventRepository);
    }

    @Test
    void unresolvableSubscription_isNoOp() {
        when(connectionService.findOutlineSubscription("gone")).thenReturn(Optional.empty());

        handler().onMessage(message("gone", "documents.update", "doc-9"));

        verifyNoInteractions(syncScheduler);
        verifyNoInteractions(documentEventRepository);
    }

    @Test
    void documentEvent_appendsOneEventRowWithActorAndUpstreamClock() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "documents.update", "doc-9", "actor-uuid", "2026-07-10T12:34:56.000Z"));

        ArgumentCaptor<OutlineDocumentEvent> event = ArgumentCaptor.forClass(OutlineDocumentEvent.class);
        verify(documentEventRepository).save(event.capture());
        assertThat(event.getValue().getWorkspaceId()).isEqualTo(42L);
        assertThat(event.getValue().getConnectionId()).isEqualTo(CONNECTION_ID);
        assertThat(event.getValue().getDocumentId()).isEqualTo("doc-9");
        assertThat(event.getValue().getEventName()).isEqualTo("documents.update");
        assertThat(event.getValue().getActorSubject()).isEqualTo("actor-uuid");
        assertThat(event.getValue().getOccurredAt()).isEqualTo(Instant.parse("2026-07-10T12:34:56Z"));
    }

    @Test
    void documentDeleteEvent_isLoggedToo() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "documents.delete", "doc-9", "actor-uuid", null));

        ArgumentCaptor<OutlineDocumentEvent> event = ArgumentCaptor.forClass(OutlineDocumentEvent.class);
        verify(documentEventRepository).save(event.capture());
        assertThat(event.getValue().getEventName()).isEqualTo("documents.delete");
        assertThat(event.getValue().getOccurredAt()).isNull();
    }

    @Test
    void documentEventWithoutActorOrClock_persistsWithNulls() {
        resolves("sub-1", 42L);

        handler().onMessage(message("sub-1", "documents.publish", "doc-9"));

        ArgumentCaptor<OutlineDocumentEvent> event = ArgumentCaptor.forClass(OutlineDocumentEvent.class);
        verify(documentEventRepository).save(event.capture());
        assertThat(event.getValue().getActorSubject()).isNull();
        assertThat(event.getValue().getOccurredAt()).isNull();
    }

    @Test
    void eventLogFailure_neverBlocksTheRefresh() {
        resolves("sub-1", 42L);
        doThrow(new RuntimeException("insert failed"))
                .when(documentEventRepository)
                .save(any());

        handler().onMessage(message("sub-1", "documents.update", "doc-9"));

        verify(syncScheduler).refreshDocumentNow(42L, "documents.update", "doc-9", null);
    }

    @Test
    void missingActiveConnection_skipsTheEventRowButStillRoutes() {
        when(connectionService.findOutlineSubscription("sub-1"))
                .thenReturn(Optional.of(new OutlineSubscription(42L, "https://wiki.example.com", "secret")));
        when(connectionService.findActive(42L, IntegrationKind.OUTLINE)).thenReturn(Optional.empty());

        handler().onMessage(message("sub-1", "documents.update", "doc-9"));

        verifyNoInteractions(documentEventRepository);
        verify(syncScheduler).refreshDocumentNow(42L, "documents.update", "doc-9", null);
    }

    static Stream<byte[]> malformedBodies() {
        return Stream.of("not json at all {{{".getBytes(StandardCharsets.UTF_8), new byte[0], (byte[]) null);
    }

    @ParameterizedTest(name = "malformed body [{index}] is acked without crash or dispatch")
    @MethodSource("malformedBodies")
    void malformedBody_isAcknowledgedWithoutCrashOrDispatch(byte[] body) {
        Message msg = mock(Message.class);
        when(msg.getData()).thenReturn(body);

        handler().onMessage(msg);

        verifyNoInteractions(syncScheduler);
        verifyNoInteractions(documentEventRepository);
    }

    @Test
    void documentEventWithNonObjectModel_fallsBackToNullWithoutCrashing() {
        // payload.model is a JSON string, not the expected object — parseModel's field lookups on a
        // scalar node must degrade to "no usable model", not throw.
        resolves("sub-1", 42L);
        Message msg = mock(Message.class);
        String body = "{\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\","
                + "\"payload\":{\"id\":\"doc-9\",\"model\":\"not-an-object\"}}";
        when(msg.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));

        handler().onMessage(msg);

        verify(syncScheduler).refreshDocumentNow(42L, "documents.update", "doc-9", null);
    }

    @Test
    void documentEventWithUsableModel_passesItThrough() {
        resolves("sub-1", 42L);
        Message msg = mock(Message.class);
        String body = "{\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\","
                + "\"payload\":{\"id\":\"doc-9\",\"model\":{\"id\":\"doc-9\",\"collectionId\":\"col-1\","
                + "\"title\":\"Doc\",\"url\":\"/doc/doc-9\"}}}";
        when(msg.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));

        handler().onMessage(msg);

        ArgumentCaptor<OutlineDocumentModel> model = ArgumentCaptor.forClass(OutlineDocumentModel.class);
        verify(syncScheduler).refreshDocumentNow(eq(42L), eq("documents.update"), eq("doc-9"), model.capture());
        assertThat(model.getValue()).isNotNull();
        assertThat(model.getValue().getId()).isEqualTo("doc-9");
        assertThat(model.getValue().getCollectionId()).isEqualTo("col-1");
        assertThat(model.getValue().getTitle()).isEqualTo("Doc");
    }

    @Test
    void documentEventWithModelMissingCollectionId_fallsBackToNull() {
        resolves("sub-1", 42L);
        Message msg = mock(Message.class);
        String body = "{\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\","
                + "\"payload\":{\"id\":\"doc-9\",\"model\":{\"id\":\"doc-9\",\"title\":\"Doc\"}}}";
        when(msg.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));

        handler().onMessage(msg);

        verify(syncScheduler).refreshDocumentNow(42L, "documents.update", "doc-9", null);
    }
}
