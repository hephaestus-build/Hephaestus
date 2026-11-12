package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnServerRole
@Transactional(readOnly = true)
public class OutlineDocumentPermission {
    private final OutlineDocumentRepository documents;
    private final ConnectionService connections;
    private final GitProviderRegistry providers;
    private final PersonIdentityResolver identities;
    private final AccountDirectory accounts;
    private final PersonProcessingSuppression suppression;
    private final OutlineOriginPolicy origins;

    public OutlineDocumentPermission(
            OutlineDocumentRepository documents,
            ConnectionService connections,
            GitProviderRegistry providers,
            PersonIdentityResolver identities,
            AccountDirectory accounts,
            PersonProcessingSuppression suppression,
            OutlineOriginPolicy origins) {
        this.documents = documents;
        this.connections = connections;
        this.providers = providers;
        this.identities = identities;
        this.accounts = accounts;
        this.suppression = suppression;
        this.origins = origins;
    }

    /** The check for every read by or for AI: capture, context and delivery. */
    public boolean permits(long workspaceId, long documentId) {
        return permitted(workspaceId, documentId, SourceReadAudience.PROCESSING);
    }

    /** The check for one audience; only a human recorded standing reads past an author's AI choice. */
    public boolean permits(long workspaceId, long documentId, SourceReadAudience audience) {
        return permitted(workspaceId, documentId, Objects.requireNonNull(audience));
    }

    private boolean permitted(long workspaceId, long documentId, SourceReadAudience audience) {
        var source = context(workspaceId, documentId);
        if (source.isEmpty()) return false;
        var connection = source.get().connection();
        if (connection.getState() != IntegrationState.ACTIVE
                || !(connection.getConfig() instanceof ConnectionConfig.OutlineConfig config)
                || config.serverUrl() == null
                || !origins.allows(config.serverUrl())) return false;
        for (var identity : source.get().people()) {
            if (suppression.isSuppressed(identity.providerId(), identity.subject(), identity.teamId())) return false;
            final Optional<Long> account;
            try {
                account = identities.accountFor(identity);
            } catch (ResponseStatusException conflict) {
                if (conflict.getStatusCode().value() == HttpStatus.CONFLICT.value()) return false;
                throw conflict;
            }
            if (account.isPresent()) {
                if (suppression.isAccountSuppressed(account.get())) return false;
                var profile = accounts.find(account.get());
                if (profile.isEmpty()
                        || (audience == SourceReadAudience.PROCESSING
                                && profile.get().aiChoice() == AiChoice.NO_AI)
                        || (profile.get().status() != Account.Status.ACTIVE
                                && profile.get().status() != Account.Status.UNCLAIMED)) return false;
            }
        }
        return true;
    }

    public boolean workspaceReadable(long workspaceId) {
        return connections.findActiveConnections(workspaceId, IntegrationKind.OUTLINE).stream()
                .map(Connection::getConfig)
                .filter(ConnectionConfig.OutlineConfig.class::isInstance)
                .map(ConnectionConfig.OutlineConfig.class::cast)
                .anyMatch(config -> config.serverUrl() != null && origins.allows(config.serverUrl()));
    }

    /** Identity attribution stays available when current permission is withdrawn. */
    public List<PersonIdentity> identities(long workspaceId, long documentId) {
        return context(workspaceId, documentId).map(Context::people).orElse(List.of());
    }

    private Optional<Context> context(long workspaceId, long documentId) {
        return documents
                .findAccess(workspaceId, documentId)
                .filter(access -> access.workspaceId() == workspaceId && access.id() == documentId)
                .flatMap(access -> connections
                        .findInWorkspace(workspaceId, access.connectionId())
                        .filter(connection -> connection.getKind() == IntegrationKind.OUTLINE)
                        .flatMap(connection -> {
                            if (!(connection.getConfig() instanceof ConnectionConfig.OutlineConfig config)
                                    || config.serverUrl() == null
                                    || config.serverUrl().isBlank()) return Optional.empty();
                            return providers
                                    .findProviderId("OUTLINE", config.serverUrl())
                                    .map(providerId -> new Context(
                                            connection,
                                            access.subjects().stream()
                                                    .map(subject -> new PersonIdentity(
                                                            providerId, subject, connection.getInstanceKey()))
                                                    .toList()));
                        }));
    }

    private record Context(Connection connection, List<PersonIdentity> people) {}
}
