package de.tum.cit.aet.hephaestus.integration.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.PayloadParsingException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for {@link AbstractIntegrationMessageHandler}. Locks the three contracts
 * that the concrete handlers depend on:
 * <ul>
 *   <li>Subject last-segment validation (with the {@code tag_push} vs {@code push}
 *       overlap regression),</li>
 *   <li>Deserialization-failure to {@link PayloadParsingException} translation,</li>
 *   <li>Tx-template wrapping of {@code handleEvent}.</li>
 * </ul>
 */
class AbstractIntegrationMessageHandlerTest extends BaseUnitTest {

    private NatsMessageDeserializer deserializer;
    private TransactionTemplate transactionTemplate;
    private AtomicReference<String> capturedPayload;

    @BeforeEach
    void setUp() {
        deserializer = mock(NatsMessageDeserializer.class);
        transactionTemplate = mock(TransactionTemplate.class);
        lenient()
                .doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        capturedPayload = new AtomicReference<>();
    }

    @Test
    void key_returnsConstructorKey() {
        TestHandler handler = new TestHandler(IntegrationKind.GITHUB, "repository.issues");
        assertThat(handler.key()).isEqualTo(new EventTypeKey(IntegrationKind.GITHUB, "repository.issues"));
    }

    @Test
    void matchingSubject_deserializesAndDispatches() throws IOException {
        TestHandler handler = new TestHandler(IntegrationKind.GITHUB, "repository.issues");
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("github.org.repo.issues");
        when(deserializer.deserialize(msg, String.class)).thenReturn("payload");

        handler.onMessage(msg);

        assertThat(capturedPayload.get()).isEqualTo("payload");
        verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void mismatchedSubject_rejected() throws IOException {
        TestHandler handler = new TestHandler(IntegrationKind.GITHUB, "repository.issues");
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("github.org.repo.pull_request");

        handler.onMessage(msg);

        verify(deserializer, never()).deserialize(any(), any());
        assertThat(capturedPayload.get()).isNull();
    }

    @Test
    @DisplayName("tag_push subject is rejected by a 'push' handler (suffix-overlap guard)")
    void suffixOverlap_isNotMatchedByLastSegmentRule() throws IOException {
        TestHandler handler = new TestHandler(IntegrationKind.GITLAB, "push");
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("gitlab.ns.proj.tag_push");

        handler.onMessage(msg);

        verify(deserializer, never()).deserialize(any(), any());
        assertThat(capturedPayload.get()).isNull();
    }

    @Test
    void deserializeIOException_translatesToPayloadParsing() throws IOException {
        TestHandler handler = new TestHandler(IntegrationKind.GITLAB, "merge_request");
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("gitlab.ns.proj.merge_request");
        when(deserializer.deserialize(msg, String.class)).thenThrow(new IOException("bad json"));

        assertThatThrownBy(() -> handler.onMessage(msg))
                .isInstanceOf(PayloadParsingException.class)
                .hasMessageContaining("Payload parsing failed");
    }

    @Test
    void runtimeException_propagates() throws IOException {
        TestHandler handler = new TestHandler(IntegrationKind.GITLAB, "merge_request") {
            @Override
            protected void handleEvent(String eventPayload) {
                throw new RuntimeException("boom");
            }
        };
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("gitlab.ns.proj.merge_request");
        when(deserializer.deserialize(msg, String.class)).thenReturn("payload");

        assertThatThrownBy(() -> handler.onMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void blankEventType_rejected() {
        assertThatThrownBy(() -> new TestHandler(IntegrationKind.GITHUB, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKind_rejected() {
        assertThatThrownBy(() -> new TestHandler(null, "push")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventTypeWithTrailingDot_rejected() {
        assertThatThrownBy(() -> new TestHandler(IntegrationKind.GITHUB, "repository."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last segment");
    }

    private class TestHandler extends AbstractIntegrationMessageHandler<String> {

        TestHandler(@Nullable IntegrationKind kind, String eventType) {
            super(kind, eventType, String.class, deserializer, transactionTemplate);
        }

        @Override
        protected void handleEvent(String eventPayload) {
            capturedPayload.set(eventPayload);
        }
    }
}
