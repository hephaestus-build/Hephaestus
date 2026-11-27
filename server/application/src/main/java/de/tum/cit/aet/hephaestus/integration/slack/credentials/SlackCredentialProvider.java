package de.tum.cit.aet.hephaestus.integration.slack.credentials;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackCredentialProvider implements ApiCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(SlackCredentialProvider.class);

    private final ConnectionService connectionService;
    private final CredentialReader credentialReader;

    public SlackCredentialProvider(ConnectionService connectionService, CredentialReader credentialReader) {
        this.connectionService = connectionService;
        this.credentialReader = credentialReader;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public Optional<CredentialBundle> resolve(IntegrationRef ref) {
        Optional<Connection> connection = connectionService.findReferenced(ref);
        if (connection.isEmpty()) {
            log.debug("Slack credential resolve: no Connection for workspace={}", ref.workspaceId());
            return Optional.empty();
        }
        Connection conn = connection.get();
        if (conn.getCredentialsEncrypted() == null) {
            log.warn("Slack Connection {} has no credentials blob — cannot resolve bot token", conn.getId());
            return Optional.empty();
        }
        return credentialReader.credentialsOf(conn);
    }

    public record BoundBot(String userId, BearerToken credential) {}

    /** One credential snapshot from the pinned active installation, including its verified bot user. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<BoundBot> resolveActiveBinding(long workspaceId, long connectionId, String teamId) {
        return connectionService.findReferenced(new IntegrationRef(IntegrationKind.SLACK, workspaceId, teamId, connectionId))
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE
                        && teamId.equals(connection.getInstanceKey()))
                .flatMap(connection -> {
                    if (!(connection.getConfig() instanceof ConnectionConfig.SlackConfig config)
                            || !teamId.equals(config.teamId()) || config.botUserId() == null) {
                        return Optional.empty();
                    }
                    String botUserId = config.botUserId();
                    return credentialReader.credentialsOf(connection)
                            .flatMap(bundle -> bundle instanceof BearerToken token
                                    ? Optional.of(new BoundBot(botUserId, token))
                                    : Optional.empty());
                });
    }

    /** Resolve the active installation and its reply eligibility in one snapshot before any Slack call. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<BearerToken> resolveActiveTeam(long workspaceId, String teamId, BooleanSupplier eligible) {
        if (!eligible.getAsBoolean()) return Optional.empty();
        return credentialReader.findActiveBearerToken(workspaceId, IntegrationKind.SLACK, teamId);
    }
}
