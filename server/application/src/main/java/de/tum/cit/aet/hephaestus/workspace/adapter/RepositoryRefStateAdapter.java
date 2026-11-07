package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL owns the last complete provider-ref observation; local mirrors are disposable.
 * Writes commit independently so a failed capture or handler cannot restore invalidated proof.
 * Callers must not hold an uncommitted write to the same monitored repository row.
 */
@Component
@RequiredArgsConstructor
public class RepositoryRefStateAdapter implements RepositoryRefState {
    private final RepositoryToMonitorRepository monitors;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID begin(long workspaceId, long repositoryId) {
        var readId = UUID.randomUUID();
        monitors.beginGitRefRead(workspaceId, repositoryId, readId);
        return readId;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long workspaceId, long repositoryId, UUID readId, String fingerprint) {
        monitors.recordGitRefs(workspaceId, repositoryId, readId, fingerprint);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> current(long workspaceId, long repositoryId) {
        return monitors.gitRefs(workspaceId, repositoryId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(long nativeId, long providerId) {
        monitors.invalidateGitRefs(nativeId, providerId);
    }
}
