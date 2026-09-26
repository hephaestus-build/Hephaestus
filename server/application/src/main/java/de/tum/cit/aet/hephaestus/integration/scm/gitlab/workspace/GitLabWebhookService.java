package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig.SigningMode;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabWebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionService connectionService;

    public GitLabWebhookService(
            ObjectProvider<GitLabWebhookClient> webhookClientProvider,
            ObjectProvider<GitLabTokenRotationClient> rotationClientProvider,
            ObjectProvider<GitLabTokenService> tokenServiceProvider,
            WebhookProperties webhookProperties,
            WorkspaceRepository workspaceRepository,
            ConnectionService connectionService) {
        this.webhookClientProvider = webhookClientProvider;
        this.rotationClientProvider = rotationClientProvider;
        this.tokenServiceProvider = tokenServiceProvider;
        this.webhookProperties = webhookProperties;
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
     * Registers a group-level webhook for the workspace, idempotently.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If webhook ID is already stored, verify it still exists on GitLab</li>
     *   <li>Look up group by path to get numeric ID (GraphQL)</li>
     *   <li>Check if a webhook with our URL already exists (adopt if found)</li>
     *   <li>Register new webhook via REST</li>
     * </ol>
     *
     * @param workspace the workspace to register a webhook for
     * @return result indicating success or failure with reason
     */
    public WebhookSetupResult registerWebhook(Workspace workspace) {
        Optional<GitLabConfig> configOpt = gitLabConfig(workspace);
        if (configOpt.isEmpty()) {
            return WebhookSetupResult.skipped("Not a GitLab workspace");
        }
        return registerWebhook(workspace, configOpt.get(), configOpt.get().signingMode(), false);
    }

    /**
     * Puts an ACTIVE GitLab connection's group hook into {@code mode}, without storing the mode: the
     * result is a success only once GitLab reported the hook in {@code mode}. Unlike ordinary
     * registration, a switch to {@code PLAINTEXT} removes the hook's signing token.
     *
     * @throws NoSuchElementException             when the workspace has no GitLab connection {@code connectionId}
     * @throws GitLabSigningModeConflictException when that connection is not ACTIVE
     */
    public WebhookSetupResult applySigningMode(long workspaceId, long connectionId, SigningMode mode) {
        Connection connection = connectionService
                .findInWorkspace(workspaceId, connectionId)
                .filter(c -> c.getKind() == IntegrationKind.GITLAB)
                .orElseThrow(() -> new NoSuchElementException("GitLab connection " + connectionId + " not found"));
        if (connection.getState() != IntegrationState.ACTIVE
                || !(connection.getConfig() instanceof GitLabConfig config)) {
            throw new GitLabSigningModeConflictException(
                    "Only an active GitLab connection's webhook signing mode can change");
        }
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace " + workspaceId + " is gone"));
        return registerWebhook(workspace, config, mode, mode == SigningMode.PLAINTEXT);
    }

    private WebhookSetupResult registerWebhook(
            Workspace workspace, GitLabConfig config, SigningMode mode, boolean removeSigningToken) {
        if (!webhookProperties.isConfigured()) {
            return WebhookSetupResult.skipped("Webhook properties not configured (missing external URL or secret)");
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
        String baseUrl = Objects.requireNonNull(webhookProperties.externalUrl()).replaceAll("/+$", "");
        String webhookUrl = baseUrl + "/webhooks/gitlab";

        Long currentWebhookId = config.gitlabWebhookId();
        Long currentGroupId = config.gitlabGroupId();

        String secret = Objects.requireNonNull(webhookProperties.secret());
        boolean signed = mode == SigningMode.WHSEC;
        if (signed && GitlabWebhookSignatureVerifier.signingKey(secret) == null) {
            return WebhookSetupResult.failed("Signing-token mode needs WEBHOOK_SECRET to be a GitLab signing token:"
                    + " whsec_ followed by the base64 of 32 bytes");
        }
        try {
            // Before 19.1 GitLab may ignore signing_token, so a hook could sign with no key or a foreign one.
            if (signed && !client.supportsSigningTokens(scopeId)) {
                return WebhookSetupResult.failed("Signing-token mode needs GitLab 19.1 or later;"
                        + " this GitLab instance only verifies webhooks with the legacy secret token");
            }

            // Step 1: If we already have a webhook ID, verify it still exists
            if (currentWebhookId != null && currentGroupId != null) {
                Optional<WebhookInfo> existing = client.getGroupWebhook(scopeId, currentGroupId, currentWebhookId);
                if (existing.isPresent()) {
                    log.debug("Webhook already registered: workspaceId={}, webhookId={}", scopeId, currentWebhookId);
                    return settle(
                            client,
                            scopeId,
                            currentGroupId,
                            existing.get(),
                            webhookUrl,
                            secret,
                            mode,
                            removeSigningToken);
                }
                // Webhook was deleted externally — clear local id and re-register
                log.info(
                        "Stored webhook no longer exists on GitLab, re-registering: workspaceId={}, webhookId={}",
                        scopeId,
                        currentWebhookId);
                updateGitLabConfig(scopeId, cfg -> cfg.withGitlabWebhookId(null));
            }

            // Step 2: Look up group by path to get numeric ID
            long groupId;
            if (currentGroupId != null) {
                groupId = currentGroupId;
            } else {
                var groupInfo = client.lookupGroup(scopeId, accountLogin);
                groupId = groupInfo.id();
                long resolvedGroupId = groupId;
                updateGitLabConfig(scopeId, cfg -> cfg.withGitlabGroupId(resolvedGroupId));
            }

            // Step 3: Check if a webhook with our URL already exists (adopt it)
            List<WebhookInfo> existingHooks = client.listGroupWebhooks(scopeId, groupId);
            Optional<WebhookInfo> matchingHook = existingHooks.stream()
                    .filter(hook -> webhookUrl.equals(hook.url()))
                    .findFirst();

            if (matchingHook.isPresent()) {
                long adoptedId = matchingHook.get().id();
                updateGitLabConfig(scopeId, cfg -> cfg.withGitlabWebhookId(adoptedId));
                log.info(
                        "Adopted existing webhook: workspaceId={}, groupId={}, webhookId={}",
                        scopeId,
                        groupId,
                        adoptedId);
                return settle(
                        client, scopeId, groupId, matchingHook.get(), webhookUrl, secret, mode, removeSigningToken);
            }

            // Step 4: Register new webhook — a signed one with both tokens, so it stays verifiable by its
            // secret token if this GitLab ignores signing_token
            WebhookInfo registered = client.registerGroupWebhook(
                    scopeId,
                    groupId,
                    signed
                            ? WebhookConfig.bothTokens(webhookUrl, secret)
                            : WebhookConfig.secretToken(webhookUrl, secret));
            updateGitLabConfig(scopeId, cfg -> cfg.withGitlabWebhookId(registered.id()));

            log.info(
                    "Registered new webhook: workspaceId={}, groupId={}, webhookId={}",
                    scopeId,
                    groupId,
                    registered.id());
            return settle(client, scopeId, groupId, registered, webhookUrl, secret, mode, removeSigningToken);
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
     * Brings a hook into {@code mode} one token at a time, so that after every request it still carries a
     * token the receiver verifies: the signing token is set beside the secret token before the secret
     * token goes, and the secret token returns before the signing token goes. Each step counts only once
     * GitLab reports it, and the final state only once a fresh read shows it at this deployment's URL.
     *
     * <p>GitLab reports only that some signing token is set, never which, so a signed hook is always sent
     * our key again, beside the secret token unless the hook already has none; from GitLab 19.1 a
     * successful update stores what it was sent. Ordinary registration never removes a signing token an operator may have set, but it does not
     * call such a hook registered either: the receiver verifies only the signature once one is sent, with
     * a key nothing here can confirm.
     */
    private static WebhookSetupResult settle(
            GitLabWebhookClient client,
            long scopeId,
            long groupId,
            WebhookInfo hook,
            String url,
            String secret,
            SigningMode mode,
            boolean removeSigningToken) {
        long id = hook.id();
        if (mode == SigningMode.WHSEC) {
            boolean signingTokenOnly = hook.signingTokenPresent() && !hook.tokenPresent() && url.equals(hook.url());
            if (!signingTokenOnly
                    && !client.updateGroupWebhook(scopeId, groupId, id, WebhookConfig.bothTokens(url, secret))
                            .signingTokenPresent()) {
                return WebhookSetupResult.failed(
                        "GitLab did not store the webhook's signing token. The webhook keeps its secret token");
            }
            client.updateGroupWebhook(scopeId, groupId, id, WebhookConfig.signingTokenOnly(url, secret));
            return confirm(client, scopeId, groupId, id, url, true, false);
        }
        if (hook.signingTokenPresent()) {
            if (!removeSigningToken) {
                return WebhookSetupResult.failed("The webhook carries a signing token this connection does not"
                        + " use, so its deliveries verify only if that token is this deployment's; switch the"
                        + " connection's signing mode explicitly to settle it");
            }
            if (!client.updateGroupWebhook(scopeId, groupId, id, WebhookConfig.secretToken(url, secret))
                    .tokenPresent()) {
                return WebhookSetupResult.failed(
                        "GitLab did not store the webhook's secret token. The webhook keeps its signing token");
            }
            client.updateGroupWebhook(scopeId, groupId, id, WebhookConfig.secretTokenOnly(url, secret));
            return confirm(client, scopeId, groupId, id, url, false, true);
        }
        if (!url.equals(hook.url())
                // Sending the token keeps it: GitLab drops a hook's secret token when only its URL changes.
                && !url.equals(client.updateGroupWebhook(scopeId, groupId, id, WebhookConfig.secretToken(url, secret))
                        .url())) {
            return WebhookSetupResult.failed("GitLab kept the webhook's previous URL");
        }
        return WebhookSetupResult.success(id, groupId);
    }

    private static WebhookSetupResult confirm(
            GitLabWebhookClient client,
            long scopeId,
            long groupId,
            long id,
            String url,
            boolean signed,
            boolean secretToken) {
        Optional<WebhookInfo> hook = client.getGroupWebhook(scopeId, groupId, id);
        if (hook.isEmpty() || !url.equals(hook.get().url())) {
            return WebhookSetupResult.failed("GitLab no longer shows the webhook at " + url);
        }
        if (hook.get().signingTokenPresent() != signed || hook.get().tokenPresent() != secretToken) {
            return WebhookSetupResult.failed(
                    signed
                            ? "GitLab kept the webhook's secret token beside its signing token"
                            : "GitLab kept the webhook's signing token; remove it under the group's"
                                    + " Settings → Webhooks, then retry");
        }
        return WebhookSetupResult.success(id, groupId);
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

    /**
     * Deletes the workspace's GitLab group webhook upstream while its Connection is still ACTIVE —
     * the disconnect flow calls this from {@code GitlabConnectionStrategy.revoke}, which runs BEFORE
     * the {@code UNINSTALLED} transition purges the PAT. That ordering matters: the GitLab token
     * provider refuses to hand out a token for a non-active scope, so this is the only window in
     * which the group hook can actually be deleted vendor-side. Best-effort — never throws.
     *
     * <p>Deliberately does NOT clear the stored {@code gitlabWebhookId}/{@code gitlabGroupId} on the
     * config (contrast {@link #deregisterWebhook(Workspace)}): the disconnect transaction holds the
     * same Connection row and saves it moments after {@code revoke} returns, so a config rewrite here
     * would bump the row version and fail that save with an optimistic-lock error. The stored ids go
     * inert once the row leaves ACTIVE and {@link #registerWebhook}'s self-heal replaces a stale id on
     * any future reconnect. Runs {@link Propagation#NOT_SUPPORTED} so the external HTTP call does not
     * tie up (or run inside) the disconnect transaction.
     *
     * @param workspaceId the workspace whose still-ACTIVE GitLab webhook to remove
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregisterActiveWebhook(long workspaceId) {
        Optional<GitLabConfig> configOpt = connectionService.findActiveGitLabConfig(workspaceId);
        if (configOpt.isEmpty()) {
            return;
        }
        GitLabConfig config = configOpt.get();
        if (config.gitlabWebhookId() == null || config.gitlabGroupId() == null) {
            return;
        }
        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Webhook deregistration skipped: client unavailable, workspaceId={}", workspaceId);
            return;
        }
        try {
            client.deregisterGroupWebhook(workspaceId, config.gitlabGroupId(), config.gitlabWebhookId());
        } catch (Exception e) {
            // Best-effort: log and continue so the local UNINSTALLED transition still proceeds.
            log.warn(
                    "Webhook deregistration failed (best-effort): workspaceId={}, webhookId={}, error={}",
                    workspaceId,
                    config.gitlabWebhookId(),
                    e.getMessage());
        }
    }

    /** Best-effort teardown for a connection that may no longer be active. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregisterWebhookForConnection(long workspaceId, long connectionId) {
        try {
            deregisterWebhookForConnectionInternal(workspaceId, connectionId);
        } catch (RuntimeException e) {
            log.info(
                    "Webhook deregistration at deactivation was a no-op (best-effort): workspaceId={}, connectionId={}, reason={}",
                    workspaceId,
                    connectionId,
                    e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deregisterWebhookForConnectionStrict(long workspaceId, long connectionId) {
        deregisterWebhookForConnectionInternal(workspaceId, connectionId);
    }

    private void deregisterWebhookForConnectionInternal(long workspaceId, long connectionId) {
        Optional<GitLabConfig> configOpt = connectionService
                .findInWorkspace(workspaceId, connectionId)
                .map(c -> c.getConfig())
                .filter(cfg -> cfg instanceof GitLabConfig)
                .map(cfg -> (GitLabConfig) cfg);
        if (configOpt.isEmpty()) {
            return;
        }
        GitLabConfig config = configOpt.get();
        if (config.gitlabWebhookId() == null || config.gitlabGroupId() == null) {
            return;
        }
        Optional<BearerToken> bearer = connectionService.findBearerToken(workspaceId, connectionId);
        if (config.serverUrl() == null || config.serverUrl().isBlank() || bearer.isEmpty()) {
            throw new IllegalStateException("GitLab webhook credentials are unavailable");
        }
        var client = webhookClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GitLab webhook client is unavailable");
        }
        client.deregisterGroupWebhookWithCredentials(
                config.serverUrl(), bearer.get().token(), config.gitlabGroupId(), config.gitlabWebhookId());
        log.info(
                "Deregistered GitLab webhook for deactivated connection: workspaceId={}, connectionId={}, webhookId={}",
                workspaceId,
                connectionId,
                config.gitlabWebhookId());
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

        record GitLabHealthCandidate(Workspace workspace, Long groupId, Long webhookId, boolean signed) {}

        List<GitLabHealthCandidate> gitLabWorkspaces =
                workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE).stream()
                        .map(ws -> {
                            Optional<GitLabConfig> cfg = gitLabConfig(ws);
                            if (cfg.isEmpty()
                                    || cfg.get().gitlabWebhookId() == null
                                    || cfg.get().gitlabGroupId() == null) {
                                return null;
                            }
                            return new GitLabHealthCandidate(
                                    ws,
                                    cfg.get().gitlabGroupId(),
                                    cfg.get().gitlabWebhookId(),
                                    cfg.get().signingMode() == SigningMode.WHSEC);
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
                if (!candidate.signed()
                        && existing.isPresent()
                        && existing.get().signingTokenPresent()) {
                    // Signed with a key nothing here can confirm: recreating it, even once GitLab disabled
                    // it, would silently drop that signature, so only an explicit switch may.
                    log.warn(
                            "Webhook carries a signing token its connection does not use; switch the signing mode"
                                    + " explicitly: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                    continue;
                }
                // Signing-token mode wants the signing token alone, which an interrupted switch leaves not.
                boolean mismatched = existing.isPresent()
                        && candidate.signed()
                        && (!existing.get().signingTokenPresent()
                                || existing.get().tokenPresent());
                if (!missing && !disabled && !mismatched) {
                    continue; // hook exists and is still delivering — nothing to do
                }

                if (disabled) {
                    // GitLab auto-disabled the hook after repeated delivery failures: the row still
                    // exists (so getGroupWebhook returns it) but it delivers nothing. A fresh register
                    // adopts by URL, which would re-adopt this same disabled hook — so delete it first,
                    // best-effort, then let registerWebhook create a clean one.
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
                } else if (missing) {
                    log.warn(
                            "Webhook missing (deleted externally), re-registering: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                } else {
                    // An interrupted switch; registerWebhook finishes it.
                    log.warn(
                            "Webhook does not match its signing mode, reconciling it: workspaceId={}, webhookId={}",
                            workspace.getId(),
                            candidate.webhookId());
                }

                if (missing || disabled) {
                    // Clear stored ID so registerWebhook creates a new one
                    updateGitLabConfig(workspace.getId(), cfg -> cfg.withGitlabWebhookId(null));
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
