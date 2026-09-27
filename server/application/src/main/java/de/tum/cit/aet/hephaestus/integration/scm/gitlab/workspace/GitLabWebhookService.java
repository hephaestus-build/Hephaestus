package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    private final ObjectProvider<GitLabTokenRotationClient> rotationClientProvider;
    private final ObjectProvider<GitLabTokenService> tokenServiceProvider;
    private final WebhookProperties webhookProperties;
    private final GitLabRouteCredential routeCredential;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionService connectionService;

    public GitLabWebhookService(
            ObjectProvider<GitLabWebhookClient> webhookClientProvider,
            ObjectProvider<GitLabTokenRotationClient> rotationClientProvider,
            ObjectProvider<GitLabTokenService> tokenServiceProvider,
            WebhookProperties webhookProperties,
            GitLabRouteCredential routeCredential,
            WorkspaceRepository workspaceRepository,
            ConnectionService connectionService) {
        this.webhookClientProvider = webhookClientProvider;
        this.rotationClientProvider = rotationClientProvider;
        this.tokenServiceProvider = tokenServiceProvider;
        this.webhookProperties = webhookProperties;
        this.routeCredential = routeCredential;
        this.workspaceRepository = workspaceRepository;
        this.connectionService = connectionService;
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
    @Transactional
    public void rotateTokenIfNeeded(Workspace workspace) {
        if (!isGitLabWorkspace(workspace)) {
            return;
        }

        var rotationClient = rotationClientProvider.getIfAvailable();
        if (rotationClient == null) {
            log.debug("Token rotation skipped: rotation client unavailable, workspaceId={}", workspace.getId());
            return;
        }

        int thresholdDays = webhookProperties.tokenRotation().thresholdDays();
        if (thresholdDays <= 0) {
            return;
        }

        try {
            var tokenInfo = rotationClient.getTokenInfo(workspace.getId());
            if (tokenInfo.expiresAt() == null) {
                log.debug("Token has no expiry, rotation not needed: workspaceId={}", workspace.getId());
                return;
            }

            LocalDate threshold = LocalDate.now().plusDays(thresholdDays);
            if (tokenInfo.expiresAt().isAfter(threshold)) {
                log.debug(
                        "Token not expiring soon: workspaceId={}, expiresAt={}, threshold={}",
                        workspace.getId(),
                        tokenInfo.expiresAt(),
                        threshold);
                return;
            }

            LocalDate newExpiry =
                    LocalDate.now().plusDays(webhookProperties.tokenRotation().validityDays());
            var rotatedToken = rotationClient.rotateToken(workspace.getId(), newExpiry);

            // Persist new token immediately — old token is already revoked. The token lives on the
            // GitLab Connection's credential blob; rotateBearerToken re-encrypts with the per-row AAD
            // so cross-row substitution is prevented.
            connectionService
                    .rotateBearerToken(
                            workspace.getId(), IntegrationKind.GITLAB, new BearerToken(rotatedToken.token(), null))
                    .orElseThrow(() -> new IllegalStateException("The GitLab token of workspace " + workspace.getId()
                            + " was rotated at the provider but no active GitLab connection was there to store it"));

            // Invalidate token cache so subsequent calls use the new token
            var tokenService = tokenServiceProvider.getIfAvailable();
            if (tokenService != null) {
                tokenService.invalidateCache(workspace.getId());
            }

            log.info(
                    "Rotated GitLab PAT: workspaceId={}, oldExpiry={}, newExpiry={}",
                    workspace.getId(),
                    tokenInfo.expiresAt(),
                    rotatedToken.expiresAt());
        } catch (WebClientResponseException | IllegalStateException e) {
            // Non-fatal for the scheduler: a failure before the provider rotated leaves the old token
            // valid until it expires; a failure after it is what the warning below is for.
            log.warn("Token rotation failed: workspaceId={}", workspace.getId(), e);
        }
    }

    /** Whether this deployment registers group webhooks: GitLab is enabled and a webhook URL and secret are set. */
    public boolean isRegistrationEnabled() {
        return webhookProperties.isConfigured() && webhookClientProvider.getIfAvailable() != null;
    }

    private boolean isGitLabWorkspace(Workspace workspace) {
        return connectionService
                .findActiveProviderKind(workspace.getId())
                .map(k -> k == IntegrationKind.GITLAB)
                .orElse(false);
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
     * knows; the shared legacy hook and hooks of an unknown key are left alone.
     *
     * @param workspace the workspace to register a webhook for
     * @return result indicating success or failure with reason
     */
    public WebhookSetupResult registerWebhook(Workspace workspace) {
        Optional<GitLabConfig> configOpt = gitLabConfig(workspace);
        Optional<Connection> connection = connectionService.findActive(workspace.getId(), IntegrationKind.GITLAB);
        if (configOpt.isEmpty() || connection.isEmpty()) {
            return WebhookSetupResult.skipped("Not a GitLab workspace");
        }

        if (!webhookProperties.isConfigured()) {
            return WebhookSetupResult.skipped("Webhook properties not configured (missing external URL or secret)");
        }
        if (!routeCredential.isConfigured()) {
            return WebhookSetupResult.skipped("Webhook routing secret not configured");
        }

        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            return WebhookSetupResult.skipped(
                    "GitLab webhook client unavailable (hephaestus.integration.gitlab.enabled=false)");
        }

        Long scopeId = workspace.getId();
        String accountLogin = workspace.getAccountLogin();
        if (accountLogin == null || accountLogin.isBlank()) {
            return WebhookSetupResult.skipped("GitLab group path is missing");
        }
        GitLabConfig config = configOpt.get();
        Optional<String> origin = ScmOrigin.of(config.serverUrl());
        Long connectionId = connection.get().getId();
        if (origin.isEmpty() || connectionId == null) {
            return WebhookSetupResult.skipped("GitLab instance is not configured on the connection");
        }
        Long storedWebhookId = config.gitlabWebhookId();
        Long currentGroupId = config.gitlabGroupId();

        try {
            // Step 1: Look up group by path to get numeric ID
            long groupId;
            if (currentGroupId != null) {
                groupId = currentGroupId;
            } else {
                var groupInfo = client.lookupGroup(scopeId, accountLogin);
                groupId = groupInfo.id();
                long resolvedGroupId = groupId;
                updateGitLabConfig(scopeId, cfg -> cfg.withGitlabGroupId(resolvedGroupId));
            }
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
                return WebhookSetupResult.skipped(
                        "Webhook is registered under a routing key this server does not know");
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
                return WebhookSetupResult.skipped("Another registration recorded a different webhook");
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
            if (GitLabWebhookClient.isPermissionOrNotFoundError(e.getStatusCode())) {
                String reason = String.format(
                        "Insufficient permissions (HTTP %d). Requires Owner role on GitLab group '%s' with Premium tier.",
                        e.getStatusCode().value(), workspace.getAccountLogin());
                log.info("Webhook registration failed: workspaceId={}, reason={}", scopeId, reason);
                return WebhookSetupResult.failed(reason);
            }
            int status = e.getStatusCode().value();
            String apiReason = String.format("GitLab API error: %d", status);
            // A provider error response can echo the webhook secret or private request data.
            log.atWarn()
                    .addKeyValue("event.name", "integration.webhook.registration.failed")
                    .addKeyValue("integration.kind", IntegrationKind.GITLAB)
                    .addKeyValue("workspace.id", scopeId)
                    .addKeyValue("http.response.status_code", status)
                    .log("GitLab webhook registration failed");
            return WebhookSetupResult.failed(apiReason);
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
        String baseUrl = Objects.requireNonNull(webhookProperties.externalUrl()).replaceAll("/+$", "");
        return baseUrl + GitLabConnectionWebhookController.PATH_PREFIX + connectionId + "/";
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
     * Deregisters the workspace's webhook from GitLab. Best-effort: never throws.
     *
     * @param workspace the workspace whose webhook to remove
     */
    @Transactional
    public void deregisterWebhook(Workspace workspace) {
        Optional<GitLabConfig> configOpt = gitLabConfig(workspace);
        if (configOpt.isEmpty()) {
            return;
        }
        GitLabConfig config = configOpt.get();
        if (config.gitlabWebhookId() == null || config.gitlabGroupId() == null) {
            return;
        }

        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Webhook deregistration skipped: client unavailable, workspaceId={}", workspace.getId());
            clearWebhookFields(workspace);
            return;
        }

        try {
            client.deregisterGroupWebhook(workspace.getId(), config.gitlabGroupId(), config.gitlabWebhookId());
        } catch (Exception e) {
            // Best-effort: log and continue. 401/403 = GitLab auto-disables failing webhooks.
            log.warn(
                    "Webhook deregistration failed (best-effort): workspaceId={}, webhookId={}, error={}",
                    workspace.getId(),
                    config.gitlabWebhookId(),
                    e.getMessage());
        }

        clearWebhookFields(workspace);
    }

    /** Best-effort teardown for a connection that may no longer be active. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregisterWebhookForConnection(long workspaceId, long connectionId) {
        try {
            prepareWebhookDeregistration(workspaceId, connectionId).ifPresent(Runnable::run);
        } catch (RuntimeException e) {
            log.info(
                    "Webhook deregistration at deactivation was a no-op (best-effort): workspaceId={}, connectionId={}, reason={}",
                    workspaceId,
                    connectionId,
                    e.getMessage());
        }
    }

    /**
     * Reads the connection's stored webhook and PAT and returns the GitLab call that deletes the hook,
     * which touches no database. Empty when no webhook is stored; throws when one is stored but cannot
     * be deleted with what the connection holds.
     */
    public Optional<Runnable> prepareWebhookDeregistration(long workspaceId, long connectionId) {
        Optional<GitLabConfig> configOpt = connectionService
                .findInWorkspace(workspaceId, connectionId)
                .map(c -> c.getConfig())
                .filter(cfg -> cfg instanceof GitLabConfig)
                .map(cfg -> (GitLabConfig) cfg);
        if (configOpt.isEmpty()) {
            return Optional.empty();
        }
        GitLabConfig config = configOpt.get();
        Long groupId = config.gitlabGroupId();
        Long webhookId = config.gitlabWebhookId();
        String serverUrl = config.serverUrl();
        if (webhookId == null || groupId == null) {
            return Optional.empty();
        }
        Optional<BearerToken> bearer = connectionService.findBearerToken(workspaceId, connectionId);
        if (serverUrl == null || serverUrl.isBlank() || bearer.isEmpty()) {
            throw new IllegalStateException("GitLab webhook credentials are unavailable");
        }
        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GitLab webhook client is unavailable");
        }
        String token = bearer.get().token();
        return Optional.of(() -> {
            client.deregisterGroupWebhookWithCredentials(serverUrl, token, groupId, webhookId);
            log.info(
                    "Deregistered GitLab webhook for deactivated connection: workspaceId={}, connectionId={}, webhookId={}",
                    workspaceId,
                    connectionId,
                    webhookId);
        });
    }

    /**
     * Deregisters webhook by workspace ID. Used by purge contributors.
     *
     * <p>Uses {@link Propagation#NOT_SUPPORTED} to suspend the outer purge
     * transaction during the external HTTP call to GitLab. Running HTTP calls
     * inside a transaction is an anti-pattern (ties up a DB connection for the
     * duration of the network round-trip).
     *
     * @param workspaceId the workspace ID
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregisterWebhookByWorkspaceId(Long workspaceId) {
        workspaceRepository.findById(workspaceId).ifPresent(this::deregisterWebhook);
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
    public void checkWebhookHealth() {
        var client = webhookClientProvider.getIfAvailable();
        if (client == null || !webhookProperties.isConfigured()) {
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
                                    || cfg.gitlabWebhookId() == null
                                    || cfg.gitlabGroupId() == null) {
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
                if (!missing && !disabled) {
                    continue; // hook exists and is still delivering — nothing to do
                }

                if (disabled && existing.get().url().startsWith(connectionWebhookUrl(candidate.connectionId()))) {
                    // GitLab auto-disabled the hook after repeated delivery failures: the row still
                    // exists (so getGroupWebhook returns it) but it delivers nothing. A fresh register
                    // adopts by URL, which would re-adopt this same disabled hook — so delete it first,
                    // best-effort, then let registerWebhook create a clean one. A hook that is not this
                    // connection's own, such as the shared legacy hook, is never deleted.
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
                            "Webhook missing or disabled, re-registering: workspaceId={}, webhookId={}",
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
                } else {
                    log.warn(
                            "Failed to re-register webhook: workspaceId={}, reason={}",
                            workspace.getId(),
                            result.failureReason());
                }
            } catch (Exception e) {
                log.debug("Webhook health check failed: workspaceId={}", workspace.getId(), e);
            }
        }

        if (reregistered > 0) {
            log.info("Webhook health check: checked={}, reregistered={}", checked, reregistered);
        }
    }

    private void clearWebhookFields(Workspace workspace) {
        updateGitLabConfig(
                workspace.getId(), cfg -> cfg.withGitlabWebhookId(null).withGitlabGroupId(null));
    }
}
