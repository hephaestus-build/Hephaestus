package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConnectionMetadataQuery {
    private final ConnectionRepository connectionRepository;

    public ConnectionMetadataQuery(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    /** Every active connection of the listed workspaces with its configuration, in one read; never a credential. */
    public List<ConnectionRepository.WorkspaceConfigRow> findActiveConnections(Collection<Long> workspaceIds) {
        return workspaceIds.isEmpty() ? List.of() : connectionRepository.findActiveWorkspaceConfigs(workspaceIds);
    }

    /** The active SCM kind of each workspace among {@code connections}. */
    public static Map<Long, IntegrationKind> providerKinds(List<ConnectionRepository.WorkspaceConfigRow> connections) {
        return connections.stream()
                .filter(row -> row.kind() == IntegrationKind.GITHUB || row.kind() == IntegrationKind.GITLAB)
                .collect(Collectors.toMap(
                        ConnectionRepository.WorkspaceConfigRow::workspaceId,
                        ConnectionRepository.WorkspaceConfigRow::kind,
                        (first, second) -> first));
    }

    /** Display metadata and credential presence only; this read never decrypts a credential. */
    public WorkspaceMetadata findWorkspaceMetadata(long workspaceId) {
        var active = connectionRepository.findWorkspaceMetadata(workspaceId);
        var scm = active.stream()
                .filter(row -> row.kind() != IntegrationKind.SLACK)
                .findFirst();
        var slack = active.stream()
                .filter(row -> row.kind() == IntegrationKind.SLACK)
                .findFirst();
        var kind = scm.map(ConnectionRepository.WorkspaceMetadataRow::kind).orElse(null);
        var config = scm.map(ConnectionRepository.WorkspaceMetadataRow::config).orElse(null);
        var credentialPresent = scm.map(ConnectionRepository.WorkspaceMetadataRow::credentialsPresent)
                .orElse(false);
        String serverUrl = null;
        Long installationId = null;
        Instant linkedAt = null;
        boolean hasPat = false;
        boolean webhookRegistered = false;
        if (kind == IntegrationKind.GITHUB && config instanceof ConnectionConfig.GitHubAppConfig app) {
            serverUrl = app.serverUrl();
            installationId = app.installationId();
            linkedAt = scm.map(ConnectionRepository.WorkspaceMetadataRow::createdAt)
                    .orElseThrow();
        } else if (kind == IntegrationKind.GITHUB && config instanceof ConnectionConfig.GitHubPatConfig pat) {
            serverUrl = pat.serverUrl();
            hasPat = credentialPresent;
        } else if (kind == IntegrationKind.GITLAB && config instanceof ConnectionConfig.GitLabConfig group) {
            serverUrl = group.serverUrl();
            hasPat = credentialPresent;
            webhookRegistered = group.gitlabWebhookId() != null;
        }
        return new WorkspaceMetadata(
                kind,
                serverUrl,
                installationId,
                linkedAt,
                hasPat,
                slack.map(ConnectionRepository.WorkspaceMetadataRow::credentialsPresent)
                        .orElse(false),
                slack.map(ConnectionRepository.WorkspaceMetadataRow::id).orElse(null),
                webhookRegistered);
    }

    public record WorkspaceMetadata(
            @Nullable IntegrationKind kind,
            @Nullable String serverUrl,
            @Nullable Long installationId,
            @Nullable Instant installationLinkedAt,
            boolean hasPersonalAccessToken,
            boolean hasSlackToken,
            @Nullable Long slackConnectionId,
            boolean gitlabWebhookRegistered) {}
}
