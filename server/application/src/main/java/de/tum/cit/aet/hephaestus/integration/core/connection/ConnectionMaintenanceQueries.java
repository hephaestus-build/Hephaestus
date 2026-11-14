package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fleet inventory for connection reconciliation; each write binds its captured authority to a workspace. */
@Service
@RequiredArgsConstructor
public class ConnectionMaintenanceQueries {
    private final ConnectionRepository connections;

    @Transactional(readOnly = true)
    @WorkspaceAgnostic(
            "Installation reconciliation captures its workspace before provider I/O and revalidates before writes")
    public Optional<InstallationSnapshot> findActiveInstallationSnapshot(long installationId) {
        return connections.findActiveInstallationSnapshot(Long.toString(installationId));
    }

    public record InstallationSnapshot(Long connectionId, Long workspaceId, long lifecycleGeneration, Long version) {}
}
