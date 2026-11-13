package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabTokenLifecycleService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Orchestrates GitLab webhook auto-registration and PAT rotation for workspaces.
 *
 * <p>This service is the workspace-side orchestrator that coordinates:
 * <ol>
 *   <li><b>Token rotation:</b> Checks PAT expiry and rotates before it expires</li>
 *   <li><b>Webhook registration:</b> Registers group-level webhooks idempotently</li>
 *   <li><b>Webhook deregistration:</b> Best-effort cleanup during workspace purge</li>
 * </ol>
 *
 * <p>All GitLab API calls are delegated to clients in {@code integration.scm.gitlab.common}.
 * This service handles idempotency, entity updates, and error handling.
 *
 * <p>Dependencies are injected via {@link ObjectProvider} to gracefully handle cases
 * where GitLab integration is disabled ({@code hephaestus.integration.gitlab.enabled=false}).
 */
@Service
public class GitLabWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);

    private final ObjectProvider<GitLabWebhookClient> webhookClientProvider;
    private final PublicApiUrls publicApiUrls;
    private final GitLabRouteCredential routeCredential;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionService connectionService;
    private final ProviderResourceRepairs repairs;
    private final ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle;

    public GitLabWebhookService(
            ObjectProvider<GitLabWebhookClient> webhookClientProvider,
            PublicApiUrls publicApiUrls,
            GitLabRouteCredential routeCredential,
            WorkspaceRepository workspaceRepository,
            ConnectionService connectionService,
            ProviderResourceRepairs repairs,
            ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle) {
        this.webhookClientProvider = webhookClientProvider;
        this.publicApiUrls = publicApiUrls;
        this.routeCredential = routeCredential;
        this.workspaceRepository = workspaceRepository;
        this.connectionService = connectionService;
        this.repairs = repairs;
        this.tokenLifecycle = tokenLifecycle;
    }

    /**
     * Checks if the workspace's PAT is expiring soon and rotates it if needed.
     *
     * <p>Rotation happens <em>before</em> webhook registration and sync to ensure
     * all subsequent API calls use a fresh token. The old token is immediately
     * revoked by GitLab, so the new token must be persisted right away.
     *
     * @param workspace the workspace to check
     */
    public void rotateTokenIfNeeded(Workspace workspace) {
        var lifecycle = tokenLifecycle.getIfAvailable();
        if (lifecycle != null) lifecycle.check(workspace.getId());
    }

    /** Whether this deployment registers group webhooks: it can sign their routes and has the GitLab webhook client. */
    public boolean isRegistrationEnabled() {
        return routeCredential.isConfigured() && webhookClientProvider.getIfAvailable() != null;
    }

    private Optional<GitLabConfig> gitLabConfig(Workspace workspace) {
        return connectionService.findActiveGitLabConfig(workspace.getId());
    }

    /**
     * Registers the connection's own group hook for the current routing key, idempotently.
     *
     * <p>The hook URL names the connection, the key and the signed route, and its token is the deterministic
     * {@link GitLabRouteCredential} for them, so every registration of one route produces the same hook. A hook is
     * never edited: an exact URL is adopted, anything else gets a new hook. Its id is recorded only if the stored id is
     * still the one this registration started from, so a slower registration under an older key cannot replace a newer
     * hook. Only then is the replaced hook deleted, and only when it is this connection's hook under a key this server
     * knows; a hook an operator created and hooks of an unknown key are left alone.
     *
     * @param workspace the workspace to register a webhook for
     * @return result indicating success or failure with reason
     */
    public WebhookSetupResult registerWebhook(Workspace workspace) {
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Provider calls require no active transaction");
        Optional<GitLabConfig> configOpt = gitLabConfig(workspace);
        Optional<Connection> connection = connectionService.findActive(workspace.getId(), IntegrationKind.GITLAB);
        if (configOpt.isEmpty() || connection.isEmpty()) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.NOT_GITLAB);
        }

        if (!routeCredential.isConfigured()) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.ROUTING_SECRET_MISSING);
        }

        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.CLIENT_UNAVAILABLE);
        }

        Long scopeId = workspace.getId();
        String accountLogin = workspace.getAccountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.GROUP_PATH_MISSING);
        }
        GitLabConfig config = configOpt.get();
        Optional<String> origin = ScmOrigin.of(config.serverUrl());
        Long connectionId = connection.get().getId();
        if (origin.isEmpty() || connectionId == null) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.CONNECTION_NOT_CONFIGURED);
        }
        Long storedWebhookId = config.gitlabWebhookId();

        try {
            long groupId = config.gitlabGroupId();
            GitLabRouteCredential.Issued issued = routeCredential.issue(
                    new GitLabRouteCredential.Route(connectionId, scopeId, origin.get(), groupId, accountLogin));
            String webhookUrl = connectionWebhookUrl(connectionId) + issued.keyId() + "/" + issued.routeId();

            // Step 2: The stored hook is this route's own hook: nothing to do. A hook of this connection under a key
            // this server does not know was recorded by a server that is already on a newer key: leave it to that one.
            Optional<WebhookInfo> stored = storedWebhookId == null
                    ? Optional.empty()
                    : client.getGroupWebhook(scopeId, groupId, storedWebhookId);
            if (stored.isPresent()
                    && !stored.get().isDisabled()
                    && (webhookUrl.equals(stored.get().url())
                            || (stored.get().url().startsWith(webhookUrl + "?")
                                    && ProviderResourceAdapter.fingerprint(
                                                    stored.get().url())
                                            .equals(config.webhookOwnershipHash())))) {
                log.debug(
                        "Webhook already registered: workspaceId={}, webhookId={}",
                        scopeId,
                        stored.get().id());
                return WebhookSetupResult.success(stored.get().id(), groupId);
            }
            Optional<String> storedKeyId = stored.flatMap(hook -> ownKeyId(hook.url(), connectionId));
            if (storedKeyId.isPresent() && !routeCredential.isAccepted(storedKeyId.get())) {
                return WebhookSetupResult.skipped(WebhookSetupResult.Reason.ROUTING_KEY_UNKNOWN);
            }

            if (storedWebhookId != null) repairs.retireCurrentHook(scopeId, connectionId);
            String marker = UUID.randomUUID().toString();
            webhookUrl += "?hephaestus_resource=" + marker;
            String registrationUrl = webhookUrl;
            String ownershipHash = ProviderResourceAdapter.fingerprint(registrationUrl);
            UUID registration = repairs.beginRegistration(
                    scopeId,
                    connectionId,
                    new Resource(
                            IntegrationKind.GITLAB,
                            origin.get(),
                            ResourceType.REGISTRATION,
                            Long.toString(groupId),
                            marker,
                            ownershipHash));

            long webhookId = adoptOrRegister(
                    client,
                    scopeId,
                    groupId,
                    webhookUrl,
                    () -> new WebhookConfig(
                            registrationUrl,
                            issued.token(),
                            true, // merge_requests_events
                            true, // issues_events
                            true, // confidential_issues_events
                            true, // note_events
                            true, // confidential_note_events
                            true, // push_events
                            true, // tag_push_events
                            true, // pipeline_events
                            true, // milestone_events
                            true, // member_events
                            true, // subgroup_events
                            true, // project_events
                            true // enable_ssl_verification
                            ));

            // A superseded creation stays discoverable through its committed registration marker.
            if (!repairs.completeRegistration(scopeId, connectionId, registration, cfg -> {
                if (!(cfg instanceof GitLabConfig gitlab)) throw new IllegalStateException("Expected GitLab config");
                return gitlab.withWebhookOwnership(webhookId, ownershipHash, marker);
            })) {
                log.info("Webhook registration superseded by a concurrent one: workspaceId={}", scopeId);
                return WebhookSetupResult.skipped(WebhookSetupResult.Reason.SUPERSEDED);
            }

            log.info("Registered webhook: workspaceId={}, groupId={}, webhookId={}", scopeId, groupId, webhookId);
            return WebhookSetupResult.success(webhookId, groupId);
        } catch (ObjectOptimisticLockingFailureException changed) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.SUPERSEDED);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            // A provider error response can echo the webhook secret or private request data.
            log.atWarn()
                    .addKeyValue("event.name", "integration.webhook.registration.failed")
                    .addKeyValue("integration.kind", IntegrationKind.GITLAB)
                    .addKeyValue("workspace.id", scopeId)
                    .addKeyValue("http.response.status_code", status)
                    .log("GitLab webhook registration failed");
            return WebhookSetupResult.failed(
                    GitLabWebhookClient.isPermissionOrNotFoundError(e.getStatusCode())
                            ? WebhookSetupResult.Reason.PERMISSION_DENIED
                            : WebhookSetupResult.Reason.API_ERROR,
                    status);
        }
    }

    /**
     * The id of the one hook with exactly {@code webhookUrl}. Two concurrent registrations of the same route can both
     * create one; each then keeps the lowest id and deletes the rest, which carry the same token.
     */
    private long adoptOrRegister(
            GitLabWebhookClient client, Long scopeId, long groupId, String webhookUrl, Supplier<WebhookConfig> config) {
        List<WebhookInfo> matching = matching(client.listGroupWebhooks(scopeId, groupId), webhookUrl);
        if (matching.isEmpty()) {
            client.registerGroupWebhook(scopeId, groupId, config.get());
            matching = matching(client.listGroupWebhooks(scopeId, groupId), webhookUrl);
            if (matching.isEmpty()) {
                throw new IllegalStateException("Registered webhook is not listed on the group");
            }
        }
        WebhookInfo kept = matching.getFirst();
        return kept.id();
    }

    private static List<WebhookInfo> matching(List<WebhookInfo> hooks, String webhookUrl) {
        return hooks.stream()
                .filter(hook -> webhookUrl.equals(hook.url()))
                .sorted(Comparator.comparingLong(WebhookInfo::id))
                .toList();
    }

    /** The key id in {@code url} when it is one of this connection's own hooks. */
    private Optional<String> ownKeyId(String url, long connectionId) {
        String prefix = connectionWebhookUrl(connectionId);
        if (!url.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] segments = url.substring(prefix.length()).split("/", -1);
        return segments.length == 2 ? Optional.of(segments[0]) : Optional.empty();
    }

    private String connectionWebhookUrl(long connectionId) {
        return publicApiUrls
                .originUri(GitLabConnectionWebhookController.PATH_PREFIX + "{connectionId}/", connectionId)
                .toString();
    }

    /**
     * Periodic health check for GitLab webhooks.
     * <p>
     * GitLab auto-disables webhooks after 40 consecutive failures. This scheduled
     * method verifies that registered webhooks still exist and re-registers them
     * if they were deleted or disabled externally.
     * <p>
     * Runs every 6 hours. Non-fatal: errors are logged but don't affect other operations.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    @SchedulerLock(name = "gitlab-webhook-health", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void checkWebhookHealth() {
        var client = webhookClientProvider.getIfAvailable();
        if (client == null || !routeCredential.isConfigured()) {
            return;
        }

        record GitLabHealthCandidate(Workspace workspace, long connectionId, Long groupId, Long webhookId) {}

        List<GitLabHealthCandidate> gitLabWorkspaces =
                workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE).stream()
                        .map(ws -> {
                            Connection connection = connectionService
                                    .findActive(ws.getId(), IntegrationKind.GITLAB)
                                    .orElse(null);
                            Long connectionId = connection != null ? connection.getId() : null;
                            if (connection == null
                                    || connectionId == null
                                    || !(connection.getConfig() instanceof GitLabConfig cfg)
                                    || cfg.gitlabWebhookId() == null) {
                                return null;
                            }
                            return new GitLabHealthCandidate(
                                    ws, connectionId, cfg.gitlabGroupId(), cfg.gitlabWebhookId());
                        })
                        .filter(Objects::nonNull)
                        .toList();

        if (gitLabWorkspaces.isEmpty()) return;

        int checked = 0, reregistered = 0;
        for (GitLabHealthCandidate candidate : gitLabWorkspaces) {
            Workspace workspace = candidate.workspace();
            try {
                Optional<WebhookInfo> existing =
                        client.getGroupWebhook(workspace.getId(), candidate.groupId(), candidate.webhookId());
                checked++;

                boolean missing = existing.isEmpty();
                boolean disabled = existing.isPresent() && existing.get().isDisabled();
                boolean own = existing.isPresent()
                        && existing.get().url().startsWith(connectionWebhookUrl(candidate.connectionId()));
                if (own && !disabled) {
                    continue; // this connection's hook exists and is still delivering — nothing to do
                }

                if (!missing && !own) {
                    // The stored id names a hook that does not post to this connection's route, such as one on the
                    // removed shared endpoint: it delivers nothing Hephaestus accepts. registerWebhook adopts or
                    // creates the connection's own hook and leaves the foreign one alone.
                    log.warn(
                            "Webhook is not the connection's own, re-registering: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                } else if (disabled) {
                    // Registration captures the old target before creating a distinct ownership marker.
                    log.warn(
                            "Webhook auto-disabled (alert_status=disabled), re-registering: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                } else {
                    log.warn(
                            "Webhook missing, re-registering: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                }

                WebhookSetupResult result = registerWebhook(workspace);
                if (result.registered()) {
                    reregistered++;
                    log.info(
                            "Re-registered webhook: workspaceId={}, newWebhookId={}",
                            workspace.getId(),
                            result.webhookId());
                } else if (result.status() == WebhookSetupResult.Status.SKIPPED) {
                    log.warn(
                            "Failed to re-register webhook: workspaceId={}, reason={}",
                            workspace.getId(),
                            result.reason());
                }
            } catch (Exception e) {
                log.debug("Webhook health check failed: workspaceId={}", workspace.getId(), e);
            }
        }

        if (reregistered > 0) {
            log.info("Webhook health check: checked={}, reregistered={}", checked, reregistered);
        }
    }
}
