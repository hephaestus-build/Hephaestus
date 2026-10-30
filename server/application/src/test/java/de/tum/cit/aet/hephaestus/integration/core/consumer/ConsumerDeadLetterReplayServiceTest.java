package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ConsumerDeadLetterReplayServiceTest extends BaseUnitTest {
    @Test
    void shouldPreserveTheOriginalEnvelopeAndSkipACompletedReplay() {
        var repository = mock(ConsumerDeadLetterRepository.class);
        var consumer = mock(IntegrationNatsConsumer.class);
        var service = new ConsumerDeadLetterReplayService(
                repository, consumer, JsonMapper.builder().build());
        var row = new ConsumerDeadLetter();
        row.setId(17L);
        row.setStreamName("github");
        row.setStreamSequence(17);
        row.setSubject("github.acme.repo.issues");
        row.setPayload("{}".getBytes(StandardCharsets.UTF_8));
        row.setHeaders("{\"X-Heph-Route\":[\"original\"]}");
        row.setAckReply("$JS.ACK.github.scope.1.17.1.1700000000000000000.0");
        row.setErrorClass("PayloadParsingException");
        row.setFailedAt(Instant.now());
        when(repository.lockWorkspace(42L, 17L)).thenReturn(Optional.of(row));
        when(consumer.dispatch(eq(42L), any())).thenReturn(mock(IntegrationMessageHandler.class));

        var first = service.replayWorkspace(42L, 17L);
        var repeated = service.replayWorkspace(42L, 17L);

        assertThat(first.replayedAt()).isNotNull().isEqualTo(repeated.replayedAt());
        var message = ArgumentCaptor.forClass(Message.class);
        verify(consumer, times(1)).dispatch(eq(42L), message.capture());
        var envelope = message.getValue();
        assertThat(envelope.isJetStream()).isTrue();
        assertThat(envelope.metaData().timestamp().toInstant()).isEqualTo(Instant.ofEpochSecond(1700000000));
        assertThat(envelope.metaData().streamSequence()).isEqualTo(17);
        assertThat(envelope.getData()).isEqualTo(row.getPayload());
        var headers = envelope.getHeaders();
        assertThat(headers).isNotNull();
        if (headers == null) throw new AssertionError("Stored routing headers missing");
        assertThat(headers.getFirst("X-Heph-Route")).isEqualTo("original");
    }
}
