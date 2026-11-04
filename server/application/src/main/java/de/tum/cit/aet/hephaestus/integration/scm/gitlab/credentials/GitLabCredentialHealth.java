package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GitLab credential observations. {@link IntegrationAttentionService#observeCredential} owns the refusal rule. */
@Service
@RequiredArgsConstructor
public class GitLabCredentialHealth {
    private final ConnectionService connections;
    private final IntegrationAttentionService attention;

    public void observe(long workspaceId, String token, boolean refused) {
        attention.observeCredential(IntegrationKind.GITLAB, workspaceId, token, refused);
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
