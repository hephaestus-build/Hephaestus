package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Credential observations are scoped to the token that made the call, not just its workspace. */
@Service
@RequiredArgsConstructor
public class GitlabCredentialHealth {
    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ConnectionService connections;
    private final ConnectionRepository repository;
    private final IntegrationAttentionService attention;

    @Transactional
    public void observe(long workspaceId, String token, boolean refused) {
        connections.findActive(workspaceId, IntegrationKind.GITLAB).ifPresent(connection -> {
            if (refused
                    ? connection.getAttentionProblem() == Problem.CREDENTIAL_REVOKED
                    : connection.getAttentionProblem() != Problem.CREDENTIAL_REVOKED) {
                return;
            }
            repository.acquireLifecycleLock(connection.getId(), workspaceId);
            Objects.requireNonNull(entityManager).refresh(connection);
            // An old in-flight request must not degrade (or recover) a replacement token.
            if (connections
                    .findActiveBearerToken(workspaceId, IntegrationKind.GITLAB)
                    .filter(current -> current.token().equals(token))
                    .isEmpty()) {
                return;
            }
            attention.report(connection.getId(), workspaceId, Problem.CREDENTIAL_REVOKED, !refused);
        });
    }

    @Transactional
    public void expiring(long workspaceId, boolean expiring) {
        connections.findActive(workspaceId, IntegrationKind.GITLAB).ifPresent(connection -> {
            // Refusal has priority; expiry must not replace it on every daily check.
            if (connection.getAttentionProblem() != Problem.CREDENTIAL_REVOKED) {
                attention.report(connection.getId(), workspaceId, Problem.CREDENTIAL_EXPIRING, !expiring);
            }
        });
    }
}
