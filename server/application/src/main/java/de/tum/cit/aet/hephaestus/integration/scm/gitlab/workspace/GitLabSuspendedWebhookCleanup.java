package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes suspended connections' hooks under their lifecycle lock, including work whose event was lost. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
public class GitLabSuspendedWebhookCleanup {

    static final String LOCK_NAME = "gitlab-suspended-webhook-cleanup";

    private static final Logger log = LoggerFactory.getLogger(GitLabSuspendedWebhookCleanup.class);

    private final ConnectionRepository connections;
    private final CredentialReader credentials;
    private final GitLabWebhookService webhooks;
    private final TransactionTemplate transactions;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public GitLabSuspendedWebhookCleanup(
            ConnectionRepository connections,
            CredentialReader credentials,
            GitLabWebhookService webhooks,
            PlatformTransactionManager transactionManager) {
        this.connections = connections;
        this.credentials = credentials;
        this.webhooks = webhooks;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Lifecycle locking orders confirmed calls. ADR 0075 records remote completion after a local timeout. */
    @SuppressWarnings("PMD.PreserveStackTrace") // Provider exceptions can contain secrets; only their class may escape.
    public void remove(long workspaceId, long connectionId) {
        try {
            transactions.executeWithoutResult(status -> {
                connections.acquireLifecycleLock(connectionId, workspaceId);
                var connection = connections
                        .findByIdAndWorkspaceId(connectionId, workspaceId)
                        .orElse(null);
                if (connection == null) return;
                Objects.requireNonNull(entityManager).refresh(connection);
                if (connection.getKind() != IntegrationKind.GITLAB
                        || connection.getState() != IntegrationState.SUSPENDED
                        || !(connection.getConfig() instanceof GitLabConfig config)) return;
                @Nullable
                BearerToken bearer = credentials
                        .credentialsOf(connection)
                        .filter(BearerToken.class::isInstance)
                        .map(BearerToken.class::cast)
                        .orElse(null);
                webhooks.removeSuspendedWebhooks(workspaceId, connectionId, config, bearer);
                connection.setConfig(config.withGitlabWebhookId(null));
                connections.save(connection);
            });
        } catch (RuntimeException failure) {
            log.warn(
                    "GitLab suspended webhook cleanup failed: workspaceId={}, connectionId={}, failure={}",
                    workspaceId,
                    connectionId,
                    failure.getClass().getSimpleName());
            throw new IllegalStateException("GitLab webhook cleanup failed for connection " + connectionId);
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @WorkspaceAgnostic("Finds suspended GitLab hooks across workspaces; each removal takes its scoped lifecycle lock")
    public void reconcile() {
        for (var candidate : connections.findSuspendedGitLabWebhookCandidates()) {
            try {
                remove(candidate.workspaceId(), candidate.connectionId());
            } catch (RuntimeException ignored) {
                // remove records the failure; this hook stays stored for the next sweep while other workspaces proceed.
            }
        }
    }
}
