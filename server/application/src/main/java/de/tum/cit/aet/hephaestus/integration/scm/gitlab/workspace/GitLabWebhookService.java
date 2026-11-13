package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
    private final ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle;

    public GitLabWebhookService(
            ObjectProvider<GitLabWebhookClient> webhookClientProvider,
            PublicApiUrls publicApiUrls,
            GitLabRouteCredential routeCredential,
            WorkspaceRepository workspaceRepository,
            ConnectionService connectionService,
            ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle) {
        this.webhookClientProvider = webhookClientProvider;
        this.publicApiUrls = publicApiUrls;
        this.routeCredential = routeCredential;
        this.workspaceRepository = workspaceRepository;
        this.connectionService = connectionService;
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
            if (stored.isPresent() && webhookUrl.equals(stored.get().url())) {
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

            // Step 3: Adopt this route's hook, or register it
            long webhookId = adoptOrRegister(
                    client,
                    scopeId,
                    groupId,
                    webhookUrl,
                    () -> new WebhookConfig(
                            webhookUrl,
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

            // Step 4: Record it, unless another registration recorded a different hook since step 2. The hook is left
            // in
            // place: it may be the one the other registration kept, and one it superseded carries the same immutable
            // credential and is dropped as a duplicate by the next registration.
            if (!recordWebhookId(scopeId, storedWebhookId, webhookId)) {
                log.info("Webhook registration superseded by a concurrent one: workspaceId={}", scopeId);
                return WebhookSetupResult.skipped(WebhookSetupResult.Reason.SUPERSEDED);
            }

            // Step 5: Retire the hook this one replaces, when it is this connection's hook under a known key
            if (stored.isPresent() && stored.get().id() != webhookId && storedKeyId.isPresent()) {
                client.deregisterGroupWebhook(scopeId, groupId, stored.get().id());
                log.info(
                        "Retired replaced webhook: workspaceId={}, webhookId={}",
                        scopeId,
                        stored.get().id());
            }

            log.info("Registered webhook: workspaceId={}, groupId={}, webhookId={}", scopeId, groupId, webhookId);
            return WebhookSetupResult.success(webhookId, groupId);
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
        for (WebhookInfo duplicate : matching.subList(1, matching.size())) {
            client.deregisterGroupWebhook(scopeId, groupId, duplicate.id());
        }
        return kept.id();
    }

    private static List<WebhookInfo> matching(List<WebhookInfo> hooks, String webhookUrl) {
        return hooks.stream()
                .filter(hook -> webhookUrl.equals(hook.url()))
                .sorted(Comparator.comparingLong(WebhookInfo::id))
                .toList();
    }

    /**
     * Records {@code next} as the connection's hook if {@code expected} is still the recorded one. The connection row is
     * versioned, so a concurrent write in between fails this one rather than being overwritten.
     */
    private boolean recordWebhookId(long workspaceId, @Nullable Long expected, long next) {
        AtomicBoolean recorded = new AtomicBoolean();
        try {
            updateGitLabConfig(workspaceId, cfg -> {
                if (!Objects.equals(cfg.gitlabWebhookId(), expected) && !Objects.equals(cfg.gitlabWebhookId(), next)) {
                    return cfg;
                }
                recorded.set(true);
                return cfg.withGitlabWebhookId(next);
            });
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
        return recorded.get();
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
     * Mutates the workspace's GitLab Connection config via {@link ConnectionService#updateConfig}.
     * The cast inside the mutator is safe because we only reach this helper for workspaces
     * whose active SCM connection is GitLab (caller-side filtered by {@link #gitLabConfig}).
     */
    private void updateGitLabConfig(long workspaceId, UnaryOperator<GitLabConfig> mutator) {
        connectionService.updateConfig(workspaceId, IntegrationKind.GITLAB, cfg -> {
            if (!(cfg instanceof GitLabConfig gitLabCfg)) {
                throw new IllegalStateException("Expected GitLabConfig on workspace=" + workspaceId + " but got "
                        + cfg.getClass().getSimpleName());
            }
            return mutator.apply(gitLabCfg);
        });
    }

    /**
     * The GitLab call that deletes the stored hook with the given PAT, which touches no database. Empty when no hook is
     * stored; throws when one is stored but cannot be deleted with what is given.
     */
    public Optional<Runnable> webhookDeregistration(
            long workspaceId, long connectionId, GitLabConfig config, @Nullable BearerToken bearer) {
        Long groupId = config.gitlabGroupId();
        Long webhookId = config.gitlabWebhookId();
        String serverUrl = config.serverUrl();
        if (webhookId == null || groupId == null) {
            return Optional.empty();
        }
        if (serverUrl == null || serverUrl.isBlank() || bearer == null) {
            throw new IllegalStateException("GitLab webhook credentials are unavailable");
        }
        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GitLab webhook client is unavailable");
        }
        String token = bearer.token();
        return Optional.of(() -> {
            client.deregisterGroupWebhookWithCredentials(serverUrl, token, groupId, webhookId);
            log.info(
                    "Deregistered GitLab webhook for deactivated connection: workspaceId={}, connectionId={}, webhookId={}",
                    workspaceId,
                    connectionId,
                    webhookId);
        });
    }

    /** Removes every hook of this connection, including a registration whose ID was never committed. */
    public void removeSuspendedWebhooks(
            long workspaceId, long connectionId, GitLabConfig config, @Nullable BearerToken bearer) {
        if (bearer == null) throw new IllegalStateException("GitLab webhook credentials are unavailable");
        var client = webhookClientProvider.getIfAvailable();
        if (client == null) throw new IllegalStateException("GitLab webhook client is unavailable");
        String prefix = connectionWebhookUrl(connectionId);
        client.removeGroupWebhooksWithCredentials(
                config.serverUrl(),
                bearer.token(),
                config.gitlabGroupId(),
                hook -> Objects.equals(config.gitlabWebhookId(), hook.id())
                        || hook.url().startsWith(prefix));
        log.info(
                "Removed suspended GitLab connection hooks: workspaceId={}, connectionId={}",
                workspaceId,
                connectionId);
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
                    // GitLab auto-disabled the hook after repeated delivery failures: the row still
                    // exists (so getGroupWebhook returns it) but it delivers nothing. A fresh register
                    // adopts by URL, which would re-adopt this same disabled hook — so delete it first,
                    // best-effort, then let registerWebhook create a clean one. A hook that is not this
                    // connection's own, such as one an operator created, is never deleted.
                    log.warn(
                            "Webhook auto-disabled (alert_status=disabled), re-registering: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                    try {
                        client.deregisterGroupWebhook(workspace.getId(), candidate.groupId(), candidate.webhookId());
                    } catch (Exception e) {
                        log.debug(
                                "Failed to delete disabled webhook before re-register (will retry next cycle): workspaceId={}",
                                workspace.getId(),
                                e);
                    }
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
