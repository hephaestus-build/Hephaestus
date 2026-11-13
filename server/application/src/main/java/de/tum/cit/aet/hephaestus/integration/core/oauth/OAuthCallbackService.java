package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService.StateBinding;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.OAuthExchangeRepair;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityNotFoundException;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application-layer facade for the OAuth callback flow. Owns all repository access so
 * the {@link OAuthCallbackController} stays a thin HTTP adapter — required by the
 * {@code ArchitectureTest.controllersDoNotAccessRepositories} rule and the
 * {@code CodeQualityTest.controllersAreThin} 5-param ceiling.
 *
 * <p>This is intentionally a separate type from {@link ConnectionService}: that one
 * owns the state-machine + audit invariants and has many callers (credential
 * providers, lifecycle webhooks, purge contributor). This service owns the
 * OAuth-finalization-specific orchestration: find-or-create in-flight Connection,
 * stamp the vendor-side instance_key + display_name, persist credentials placeholder,
 * and transition PENDING → ACTIVE.
 */
@Service
public class OAuthCallbackService {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackService.class);

    /**
     * The kinds whose instance one workspace holds at a time: the states in which a connection holds it, the unique
     * index that settles a race the check here cannot, and the words that name it to the person connecting.
     */
    private record ExclusiveInstance(Set<IntegrationState> holdingStates, String index, String noun, String provider) {}

    private static final Map<IntegrationKind, ExclusiveInstance> EXCLUSIVE_INSTANCES = Map.of(
            IntegrationKind.SLACK,
            new ExclusiveInstance(
                    Set.of(IntegrationState.ACTIVE),
                    "uq_connection_one_active_slack_per_team",
                    "Slack workspace",
                    "Slack"),
            IntegrationKind.GITHUB,
            new ExclusiveInstance(
                    EnumSet.complementOf(EnumSet.of(IntegrationState.UNINSTALLED)),
                    "uq_connection_one_github_installation",
                    "GitHub App installation",
                    "GitHub"));

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final ProviderResourceRepairs repairs;
    private final OAuthExchangeRepair exchangeRepairs;
    private final WorkspaceRepository workspaceRepository;
    private final CredentialBundleConverter credentialBundleConverter;
    private final AccountMembershipQuery membershipQuery;
    private final TransactionTemplate transactionTemplate;

    public String workspaceSlug(long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace does not exist"))
                .getWorkspaceSlug();
    }

    public OAuthCallbackService(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            WorkspaceRepository workspaceRepository,
            CredentialBundleConverter credentialBundleConverter,
            AccountMembershipQuery membershipQuery,
            PlatformTransactionManager transactionManager,
            ProviderResourceRepairs repairs,
            OAuthExchangeRepair exchangeRepairs) {
        this.repairs = repairs;
        this.exchangeRepairs = exchangeRepairs;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.workspaceRepository = workspaceRepository;
        this.credentialBundleConverter = credentialBundleConverter;
        this.membershipQuery = membershipQuery;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Connection findAttemptConnection(StateBinding binding) {
        Connection owner = connectionRepository
                .findByIdAndWorkspaceId(binding.connectionId(), binding.workspaceId())
                .orElseThrow(() ->
                        new IllegalStateException("This connection request was superseded. Start a new connection."));
        if (owner.getKind() != binding.kind() || owner.getLifecycleGeneration() != binding.lifecycleGeneration()) {
            throw new IllegalStateException("This connection request was superseded. Start a new connection.");
        }
        return owner;
    }

    /**
     * Finalize a successful OAuth flow: stamp instance_key + display_name, persist the
     * credential placeholder, and transition PENDING (or ACTIVE on reconnect) → ACTIVE
     * with an audit row attributed to {@code actorAccountId}.
     *
     * <p>Throws {@link InstanceConnectedElsewhereException} if another workspace holds the Slack
     * team or GitHub App installation, and {@link IllegalStateException} if the transition guard
     * rejects the move (e.g. the Connection was UNINSTALLED between create and finalize). The
     * controller translates both into HTTP 409.
     */
    public Connection completeConnection(
            Connection pending, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> complete(pending, completed, actorAccountId)));
        } catch (DataIntegrityViolationException e) {
            ExclusiveInstance exclusive = EXCLUSIVE_INSTANCES.get(pending.getKind());
            if (exclusive == null || !DataIntegrityViolationConstraints.hasName(e, exclusive.index())) {
                throw e;
            }
            // A concurrent connect can commit after the ownership check.
            String conflict = connectedElsewhere(
                            pending.getKind(), pending.getWorkspace().getId(), completed.instanceKey(), actorAccountId)
                    .orElseThrow(() -> e);
            if (completed.exchangeAttemptId() != null) exchangeRepairs.discard(completed.exchangeAttemptId());
            throw new InstanceConnectedElsewhereException(conflict, e);
        } catch (RuntimeException rejected) {
            boolean deterministic = rejected instanceof TargetRefusedException
                    || rejected instanceof InstanceConnectedElsewhereException
                    || rejected instanceof ResponseStatusException;
            if (completed.exchangeAttemptId() != null) {
                if (deterministic) exchangeRepairs.discard(completed.exchangeAttemptId());
                else exchangeRepairs.uncertain(completed.exchangeAttemptId());
            }
            if (!deterministic
                    && pending.getKind() == IntegrationKind.GITHUB
                    && (rejected instanceof DataAccessException || rejected instanceof TransactionException))
                recordAbandoned(pending, completed);
            throw rejected;
        }
    }

    private void recordAbandoned(Connection pending, ConnectFinalization.Completed completed) {
        repairs.observeAbandonedOAuth(
                new IntegrationRef(
                        pending.getKind(), pending.getWorkspace().getId(), completed.instanceKey(), pending.getId()),
                completed.config() == null ? pending.getConfig() : completed.config(),
                completed.credentials());
    }

    private Connection complete(
            Connection pending, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        Connection current = repairs.prepareCredentialCompletion(
                pending,
                completed.config() == null ? pending.getConfig() : completed.config(),
                completed.credentials(),
                completed.exchangeAttemptId());
        Connection connection = resolveCompletionTarget(current, completed, actorAccountId);
        if (completed.exchangeAttemptId() != null) exchangeRepairs.discard(completed.exchangeAttemptId());
        applyVendorMetadata(connection, completed);
        connection.setCredentials(completed.credentials(), credentialBundleConverter);
        connection = connectionRepository.save(connection);

        String correlationId = "oauth-" + completed.instanceKey() + "-" + UUID.randomUUID();
        connection = connectionService.transition(
                connection,
                TransitionRequest.byAccount(
                        IntegrationState.ACTIVE,
                        "OAUTH_COMPLETE",
                        "USER",
                        actorAccountId,
                        correlationId,
                        completed.displayName()));
        log.info(
                "OAuth complete: kind={} workspace={} connection={} instanceKey={} actor={}",
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getId(),
                sanitizeForLog(completed.instanceKey()),
                actorAccountId);
        return connection;
    }

    /**
     * The row to complete. A kind whose instance one workspace holds refuses an instance another workspace holds, and
     * reuses this workspace's own row for it rather than colliding with it on {@code (workspace, kind, instance_key)}.
     */
    @Transactional(readOnly = true)
    public void validateKnownCallbackTarget(Connection connection, Map<String, String> params) {
        String claimed = connection.getKind() == IntegrationKind.GITHUB
                ? params.get("installation_id")
                : params.getOrDefault("team_id", params.get("team"));
        if (claimed != null && !claimed.isBlank()) validateLocalTarget(connection, claimed);
    }

    private void validateTarget(Connection connection, String instanceKey, @Nullable Long actorAccountId) {
        validateLocalTarget(connection, instanceKey);
        if (!EXCLUSIVE_INSTANCES.containsKey(connection.getKind()) || instanceKey.isBlank()) return;
        Optional<String> conflict = connectedElsewhere(
                connection.getKind(), connection.getWorkspace().getId(), instanceKey, actorAccountId);
        if (conflict.isPresent()) throw new InstanceConnectedElsewhereException(conflict.get(), null);
    }

    private void validateLocalTarget(Connection connection, String instanceKey) {
        if (!EXCLUSIVE_INSTANCES.containsKey(connection.getKind()) || instanceKey.isBlank()) return;
        long workspaceId = connection.getWorkspace().getId();
        var existing = connectionRepository.findByWorkspaceIdAndKindAndInstanceKey(
                workspaceId, connection.getKind(), instanceKey);
        if (existing.isPresent() && !Objects.equals(existing.get().getId(), connection.getId())) {
            throw new TargetRefusedException(
                    "This authorization does not belong to that connection. Start the connection again.");
        }
        if (connection.getInstanceKey() != null
                && !instanceKey.equals(connection.getInstanceKey())
                && connection.getState() != IntegrationState.UNINSTALLED) {
            throw new TargetRefusedException(
                    "Disconnect the current connection before connecting a different provider instance.");
        }
    }

    private Connection resolveCompletionTarget(
            Connection connection, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        String instanceKey = completed.instanceKey();
        validateTarget(connection, instanceKey, actorAccountId);
        if (!EXCLUSIVE_INSTANCES.containsKey(connection.getKind())
                || instanceKey.isBlank()
                || connection.getInstanceKey() == null
                || instanceKey.equals(connection.getInstanceKey())) return connection;
        return connectionRepository.save(new Connection(
                connection.getWorkspace(),
                connection.getKind(),
                instanceKey,
                completed.config() == null ? connection.getConfig() : completed.config()));
    }

    /**
     * Why {@code instanceKey} cannot be connected to {@code workspaceId}, if another workspace holds it. The holder is
     * named only to an administrator of it; the log names it for the operator.
     */
    private Optional<String> connectedElsewhere(
            IntegrationKind kind, long workspaceId, String instanceKey, @Nullable Long actorAccountId) {
        ExclusiveInstance exclusive = EXCLUSIVE_INSTANCES.get(kind);
        if (exclusive == null) {
            return Optional.empty();
        }
        return connectionRepository
                .findAllByKindAndInstanceKeyAndStateIn(kind, instanceKey, exclusive.holdingStates())
                .stream()
                .map(Connection::getWorkspace)
                .filter(owner -> owner.getId() != workspaceId)
                .findFirst()
                .map(owner -> {
                    log.warn(
                            "Rejected {} connect: instanceKey={} is held by workspace={}, target workspace={}",
                            kind,
                            sanitizeForLog(instanceKey),
                            owner.getId(),
                            workspaceId);
                    return administers(owner.getId(), actorAccountId)
                            ? "This "
                                    + exclusive.noun()
                                    + " is already connected to the Hephaestus workspace \""
                                    + owner.getDisplayName()
                                    + "\" ("
                                    + owner.getWorkspaceSlug()
                                    + "). Disconnect "
                                    + exclusive.provider()
                                    + " there before you connect it here."
                            : "This "
                                    + exclusive.noun()
                                    + " is already connected to another Hephaestus workspace. A workspace admin there must disconnect "
                                    + exclusive.provider()
                                    + " first.";
                });
    }

    /** The OAuth state's {@code actorAccountId} is the initiating account id; anything else is not an administrator. */
    private boolean administers(long workspaceId, @Nullable Long actorAccountId) {
        if (actorAccountId == null) {
            return false;
        }
        return membershipQuery.isAdministrator(workspaceId, actorAccountId);
    }

    private static void applyVendorMetadata(Connection connection, ConnectFinalization.Completed completed) {
        if (completed.instanceKey() != null) {
            connection.bindInstanceKey(completed.instanceKey());
        }
        if (completed.displayName() != null) {
            connection.setDisplayName(completed.displayName());
        }
        // Slack finalization can return resolved team metadata.
        // A null config retains the pending connection metadata.
        if (completed.config() != null) {
            connection.setConfig(refreshedConfig(connection, completed.config()));
        }
    }

    /**
     * Reauthorizing the Slack team an active or suspended connection already holds refreshes its credential and team
     * name only: the provider knows nothing of the workspace's retention or streams, so its defaults must not replace
     * them. An uninstalled row starts over, because disconnecting erased what those settings governed.
     */
    private static ConnectionConfig refreshedConfig(Connection connection, ConnectionConfig vendor) {
        if ((connection.getState() == IntegrationState.ACTIVE || connection.getState() == IntegrationState.SUSPENDED)
                && vendor instanceof ConnectionConfig.SlackConfig refreshed
                && connection.getConfig() instanceof ConnectionConfig.SlackConfig current
                && refreshed.teamId() != null
                && refreshed.teamId().equals(current.teamId())) {
            return new ConnectionConfig.SlackConfig(
                    refreshed.teamId(),
                    refreshed.botUserId(),
                    refreshed.botTokenFingerprint(),
                    refreshed.teamName(),
                    current.retentionDays(),
                    current.enabledStreams());
        }
        return vendor;
    }

    /** A Slack team or GitHub App installation that another workspace holds; the message is safe to show the caller. */
    private static final class TargetRefusedException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        private TargetRefusedException(String message) {
            super(message);
        }
    }

    static final class InstanceConnectedElsewhereException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InstanceConnectedElsewhereException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
