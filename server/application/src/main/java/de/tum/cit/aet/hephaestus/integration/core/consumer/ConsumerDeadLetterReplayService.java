package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.InstallationReplayAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Queues a dead letter again. The event kept its payload, so a replay needs no broker and no retention window: the
 * consumer claims it at the head of its scope, and its handler skips the steps that it committed before.
 */
@Service
@ConditionalOnServerRole
public class ConsumerDeadLetterReplayService {

    private final ProviderEventStore store;
    private final InstallationReplayAudit audit;

    public ConsumerDeadLetterReplayService(ProviderEventStore store, InstallationReplayAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public ConsumerDeadLetterDTO replayWorkspace(Long workspaceId, Long id, Long actorId) {
        return replay(workspaceId, id, actorId);
    }

    @Transactional
    @WorkspaceAgnostic("Instance replay selects only unassigned installation rows, never workspace data")
    public ConsumerDeadLetterDTO replayInstallation(Long id, Long actorId) {
        ConsumerDeadLetterDTO replayed = replay(null, id, actorId);
        if (!audit.record(actorId, id)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The replay could not be recorded.");
        }
        return replayed;
    }

    private ConsumerDeadLetterDTO replay(@Nullable Long workspaceId, long id, long actorId) {
        return store.replay(workspaceId, id, actorId)
                .map(ConsumerDeadLetterDTO::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
