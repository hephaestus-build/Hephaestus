package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.InstallationReplayAudit;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import io.nats.client.api.MessageInfo;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConsumerDeadLetterReplayServiceTest extends BaseUnitTest {
    @Test
    void shouldPreserveTheOriginalEnvelopeAndSkipACompletedReplay() throws Exception {
        var repository = mock(ConsumerDeadLetterRepository.class);
        var consumer = mock(IntegrationNatsConsumer.class);
        var service = new ConsumerDeadLetterReplayService(
                repository,
                consumer,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                mock(InstallationReplayAudit.class));
        var row = new ConsumerDeadLetter();
        row.setId(17L);
        row.setStreamName("github");
        row.setStreamSequence(17);
        row.setSubject("github.acme.repo.issues");
        row.setConsumerName("scope");
        row.setReceivedAt(Instant.ofEpochSecond(1700000000));
        MessageInfo stored = mock(MessageInfo.class);
        when(stored.getTime()).thenReturn(row.getReceivedAt().atZone(ZoneOffset.UTC));
        when(stored.getSubject()).thenReturn(row.getSubject());
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(stored.getData()).thenReturn(payload);
        when(stored.getHeaders()).thenReturn(new Headers().put("X-Heph-Route", "original"));
        when(consumer.readRetainedMessage("github", 17)).thenReturn(stored);
        doAnswer(call -> call.<Supplier<ConsumerDeadLetterDTO>>getArgument(2).get())
                .when(consumer)
                .serialDispatch(eq(42L), eq("github"), any());
        row.setErrorClass("PayloadParsingException");
        row.setFailedAt(Instant.now());
        when(repository.findWorkspace(42L, 17L)).thenReturn(Optional.of(row));
        when(repository.lockWorkspace(42L, 17L)).thenReturn(Optional.of(row));
        when(consumer.isReplayAdmitted(42L, "github", "github.acme.repo.issues"))
                .thenReturn(true);
        when(consumer.dispatch(eq(42L), any())).thenReturn(mock(IntegrationMessageHandler.class));

        var first = service.replayWorkspace(42L, 17L, 8L);
        var repeated = service.replayWorkspace(42L, 17L, 8L);

        assertThat(first.replayedBy()).isEqualTo(8L);
        assertThat(first.replayedAt()).isNotNull().isEqualTo(repeated.replayedAt());
        var message = ArgumentCaptor.forClass(Message.class);
        verify(consumer, times(1)).dispatch(eq(42L), message.capture());
        var envelope = message.getValue();
        assertThat(envelope.isJetStream()).isTrue();
        assertThat(envelope.metaData().timestamp().toInstant()).isEqualTo(Instant.ofEpochSecond(1700000000));
        assertThat(envelope.metaData().streamSequence()).isEqualTo(17);
        assertThat(envelope.getData()).isEqualTo(payload);
        var headers = envelope.getHeaders();
        assertThat(headers).isNotNull();
        if (headers == null) throw new AssertionError("Stored routing headers missing");
        assertThat(headers.getFirst("X-Heph-Route")).isEqualTo("original");
    }
}
