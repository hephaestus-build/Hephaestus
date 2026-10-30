package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.Message;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterService {
    private final ConsumerDeadLetterRepository repository;
    private final ObjectMapper mapper;

    public ConsumerDeadLetterService(ConsumerDeadLetterRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    @WorkspaceAgnostic(
            "Consumer capture supplies the owning workspace explicitly; installation events have no workspace")
    public void capture(@Nullable Long workspaceId, Message message, Exception failure) {
        var metadata = message.metaData();
        String reply = message.getReplyTo();
        if (reply == null || metadata == null) throw new IllegalArgumentException("JetStream metadata required");
        var headers = message.getHeaders();
        repository.capture(
                workspaceId,
                metadata.getConsumer(),
                metadata.getStream(),
                metadata.streamSequence(),
                message.getSubject(),
                message.getData(),
                mapper.writeValueAsString(headers == null ? Map.of() : headers.toMap()),
                reply,
                failure.getClass().getName(),
                Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ConsumerDeadLetterDTO> listWorkspace(Long workspaceId, long after) {
        return repository.listWorkspace(workspaceId, after, PageRequest.of(0, 100)).stream()
                .map(ConsumerDeadLetterDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Instance administrators can read only unassigned installation rows (workspace IS NULL)")
    public List<ConsumerDeadLetterDTO> listInstallation(long after) {
        return repository.listInstallation(after, PageRequest.of(0, 100)).stream()
                .map(ConsumerDeadLetterDTO::from)
                .toList();
    }
}
