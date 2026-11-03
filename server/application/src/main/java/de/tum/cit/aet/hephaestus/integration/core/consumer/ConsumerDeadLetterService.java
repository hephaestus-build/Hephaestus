package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import io.nats.client.Message;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterService {
    private static final int MAX_PAGE = 100;

    private final ConsumerDeadLetterRepository repository;

    public ConsumerDeadLetterService(ConsumerDeadLetterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    @WorkspaceAgnostic(
            "Consumer capture supplies the owning workspace explicitly; installation events have no workspace")
    public void capture(@Nullable Long workspaceId, Message message, Throwable failure) {
        var metadata = message.metaData();
        if (metadata == null) throw new IllegalArgumentException("JetStream metadata required");
        repository.capture(new ConsumerDeadLetterRepository.Capture(
                workspaceId,
                metadata.getConsumer(),
                metadata.getStream(),
                metadata.streamSequence(),
                message.getSubject(),
                metadata.timestamp().toInstant().truncatedTo(ChronoUnit.MICROS),
                IntegrationPoisonHandler.diagnosticCause(failure).getClass().getName(),
                Instant.now()));
    }

    @Transactional(readOnly = true)
    public CursorPageDTO<ConsumerDeadLetterDTO> listWorkspace(Long workspaceId, @Nullable String cursor, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE);
        return page(repository.listWorkspace(workspaceId, idAfter(cursor), PageRequest.of(0, capped + 1)), capped);
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Instance administrators can read only unassigned installation rows (workspace IS NULL)")
    public CursorPageDTO<ConsumerDeadLetterDTO> listInstallation(@Nullable String cursor, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE);
        return page(repository.listInstallation(idAfter(cursor), PageRequest.of(0, capped + 1)), capped);
    }

    private static CursorPageDTO<ConsumerDeadLetterDTO> page(List<ConsumerDeadLetter> rows, int size) {
        boolean more = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        return new CursorPageDTO<>(
                page.stream().map(ConsumerDeadLetterDTO::from).toList(),
                more ? String.valueOf(page.getLast().getId()) : null);
    }

    private static long idAfter(@Nullable String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cursor is not valid.", e);
        }
    }
}
