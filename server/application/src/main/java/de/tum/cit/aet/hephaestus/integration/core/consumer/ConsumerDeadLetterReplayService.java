package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.impl.NatsMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterReplayService {
    private final ConsumerDeadLetterRepository repository;
    private final IntegrationNatsConsumer consumer;
    private final ObjectMapper mapper;

    public ConsumerDeadLetterReplayService(
            ConsumerDeadLetterRepository repository, IntegrationNatsConsumer consumer, ObjectMapper mapper) {
        this.repository = repository;
        this.consumer = consumer;
        this.mapper = mapper;
    }

    @Transactional
    public ConsumerDeadLetterDTO replayWorkspace(Long workspaceId, Long id) {
        return replay(
                workspaceId,
                repository
                        .lockWorkspace(workspaceId, id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Transactional
    @WorkspaceAgnostic("Instance replay selects only unassigned installation rows, never workspace data")
    public ConsumerDeadLetterDTO replayInstallation(Long id) {
        return replay(
                null,
                repository.lockInstallation(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private ConsumerDeadLetterDTO replay(@Nullable Long workspaceId, ConsumerDeadLetter row) {
        if (row.getReplayedAt() != null) return ConsumerDeadLetterDTO.from(row);
        Map<String, List<String>> values = mapper.readValue(row.getHeaders(), new TypeReference<>() {});
        Headers headers = new Headers().put(values);
        var message = new ReplayMessage(row, headers);
        try {
            if (consumer.dispatch(workspaceId, message) == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The event route is no longer available.");
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "We could not replay this event. Check the connection and try again.",
                    failure);
        }
        row.setReplayedAt(Instant.now());
        return ConsumerDeadLetterDTO.from(row);
    }

    /** Preserve the broker timestamp used by SCM event identities, without a live acknowledgement channel. */
    private static final class ReplayMessage extends NatsMessage {
        ReplayMessage(ConsumerDeadLetter row, Headers headers) {
            super(row.getSubject(), row.getAckReply(), headers, row.getPayload());
        }

        @Override
        public boolean isJetStream() {
            return true;
        }

        @Override
        public NatsJetStreamMetaData metaData() {
            return new NatsJetStreamMetaData(this);
        }
    }
}
