package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabProjectHook;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRegistrations;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitLabResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabTokenLifecycleService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
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
 *   <li><b>Webhook registration:</b> Registers the group hook idempotently. Where the GitLab tier has no group hooks
 *       (Community Edition and Free answer 404 or 403), it registers one hook on each monitored project instead.</li>
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
    private static final String RESOURCE_QUERY = "?hephaestus_resource=";
    /** Covers one reconcile of every project of a large group, each with a few 15-second provider calls. */
    private static final Duration RECONCILE_AT_MOST = Duration.ofMinutes(30);

    private final ObjectProvider<GitLabWebhookClient> webhookClientProvider;
    private final PublicApiUrls publicApiUrls;
    private final GitLabRouteCredential routeCredential;
    private final WorkspaceLookup workspaceLookup;
    private final ConnectionService connectionService;
    private final ProviderResourceRegistrations registrations;
    private final ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle;
    private final WorkspaceRepositoryMonitors monitors;
    private final IntegrationAttentionService attention;
    private final ObjectProvider<LockProvider> locks;

    public GitLabWebhookService(
            ObjectProvider<GitLabWebhookClient> webhookClientProvider,
            PublicApiUrls publicApiUrls,
            GitLabRouteCredential routeCredential,
            WorkspaceLookup workspaceLookup,
            ConnectionService connectionService,
            ProviderResourceRegistrations registrations,
            ObjectProvider<GitLabTokenLifecycleService> tokenLifecycle,
            WorkspaceRepositoryMonitors monitors,
            IntegrationAttentionService attention,
            ObjectProvider<LockProvider> locks) {
        this.webhookClientProvider = webhookClientProvider;
        this.publicApiUrls = publicApiUrls;
        this.routeCredential = routeCredential;
        this.workspaceLookup = workspaceLookup;
        this.connectionService = connectionService;
        this.registrations = registrations;
        this.tokenLifecycle = tokenLifecycle;
        this.monitors = monitors;
        this.attention = attention;
        this.locks = locks;
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
        return registerWebhook(workspace, false);
    }

    /**
     * {@link #registerWebhook(Workspace)}; with {@code verify}, it also reads each stored project hook back and
     * replaces one that GitLab deleted or disabled.
     */
    private WebhookSetupResult registerWebhook(Workspace workspace, boolean verify) {
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Provider calls require no active transaction");
        Long connectionId = connectionService
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .map(Connection::getId)
                .orElse(null);
        if (connectionId == null) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.NOT_GITLAB);
        }
        LockProvider provider = locks.getIfAvailable();
        if (provider == null) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.CLIENT_UNAVAILABLE);
        }
        // Initialization, each sync and the health check reconcile the same hooks. Each reads the stored hooks and
        // then writes them back, so two at once could drop a hook the other recorded and leave it at GitLab.
        Optional<SimpleLock> lock = provider.lock(new LockConfiguration(
                Instant.now(), "gitlab-webhook-" + connectionId, RECONCILE_AT_MOST, Duration.ZERO));
        if (lock.isEmpty()) {
            return WebhookSetupResult.skipped(WebhookSetupResult.Reason.IN_PROGRESS);
        }
        try {
            return reconcile(workspace, verify);
        } finally {
            lock.get().unlock();
        }
    }

    private WebhookSetupResult reconcile(Workspace workspace, boolean verify) {
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

            // A stored group hook that still exists stays in use, whatever the listing answers. A connection on
            // project hooks stays on them: the tier that refused group hooks still refuses them.
            Optional<WebhookInfo> stored = storedWebhookId == null
                    ? Optional.empty()
                    : client.getGroupWebhook(scopeId, groupId, storedWebhookId);
            Optional<Integer> groupRefusal = !config.projectHooks().isEmpty()
                    ? Optional.of(0)
                    : stored.isPresent() ? Optional.empty() : groupHookRefusal(client, scopeId, groupId);
            if (groupRefusal.isPresent()) {
                WebhookSetupResult result = registerProjectHooks(
                        client,
                        scopeId,
                        connectionId,
                        origin.get(),
                        config,
                        webhookUrl,
                        issued.token(),
                        verify,
                        groupRefusal.get());
                reportAvailability(connection.get(), config, scopeId, result);
                return result;
            }

            // A group hook registered by a 1.0 release candidate records no ownership. It posts to this connection's
            // route under a key this server accepts, so the URL that GitLab reports is its ownership. Remove this
            // with the rc-to-1.0 cutover code (#4559).
            String ownership = config.webhookOwnershipHash();
            Long version = connection.get().getVersion();
            if (stored.isPresent()
                    && ownership == null
                    && connection.get().getConfig() instanceof GitLabConfig read
                    && Objects.equals(read.gitlabWebhookId(), storedWebhookId)
                    && read.webhookOwnershipHash() == null
                    && !stored.get().url().contains("?")
                    && ownKeyId(stored.get().url(), connectionId)
                            .filter(routeCredential::isAccepted)
                            .isPresent()) {
                long hookId = stored.get().id();
                String recorded =
                        ProviderResourceAdapter.fingerprint(stored.get().url());
                if (!registrations.recordOwnership(scopeId, connectionId, version, cfg -> {
                    if (!(cfg instanceof GitLabConfig gitlab))
                        throw new IllegalStateException("Expected GitLab config");
                    // Only the hook that was read, and only while it has no ownership.
                    if (!Objects.equals(gitlab.gitlabWebhookId(), hookId) || gitlab.webhookOwnershipHash() != null) {
                        return gitlab;
                    }
                    return gitlab.withWebhookOwnership(hookId, recorded, gitlab.webhookOwnershipMarker());
                })) {
                    return WebhookSetupResult.skipped(WebhookSetupResult.Reason.SUPERSEDED);
                }
                ownership = recorded;
                log.info("Recorded the ownership of a webhook: workspaceId={}, webhookId={}", scopeId, hookId);
            }

            // Step 2: The stored hook is this route's own hook: nothing to do. A hook of this connection under a key
            // this server does not know was recorded by a server that is already on a newer key: leave it to that one.
            if (stored.isPresent()
                    && !stored.get().isDisabled()
                    && (webhookUrl.equals(stored.get().url())
                            || stored.get().url().startsWith(webhookUrl + "?"))
                    && ProviderResourceAdapter.fingerprint(stored.get().url()).equals(ownership)) {
                log.debug(
                        "Webhook already registered: workspaceId={}, webhookId={}",
                        scopeId,
                        stored.get().id());
                reportAvailability(
                        connection.get(),
                        config,
                        scopeId,
                        WebhookSetupResult.success(stored.get().id(), groupId));
                return WebhookSetupResult.success(stored.get().id(), groupId);
            }
            Optional<String> storedKeyId = stored.flatMap(hook -> ownKeyId(hook.url(), connectionId));
            if (storedKeyId.isPresent() && !routeCredential.isAccepted(storedKeyId.get())) {
                return WebhookSetupResult.skipped(WebhookSetupResult.Reason.ROUTING_KEY_UNKNOWN);
            }

            if (storedWebhookId != null) registrations.retireCurrentHook(scopeId, connectionId);
            String marker = UUID.randomUUID().toString();
            webhookUrl += RESOURCE_QUERY + marker;
            String registrationUrl = webhookUrl;
            String ownershipHash = ProviderResourceAdapter.fingerprint(registrationUrl);
            UUID registration = registrations.beginRegistration(
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
                    client, scopeId, groupId, webhookUrl, () -> hookConfig(registrationUrl, issued.token()));

            // A superseded creation stays discoverable through its committed registration marker.
            if (!registrations.completeRegistration(scopeId, connectionId, registration, cfg -> {
                if (!(cfg instanceof GitLabConfig gitlab)) throw new IllegalStateException("Expected GitLab config");
                return gitlab.withWebhookOwnership(webhookId, ownershipHash, marker);
            })) {
                log.info("Webhook registration superseded by a concurrent one: workspaceId={}", scopeId);
                return WebhookSetupResult.skipped(WebhookSetupResult.Reason.SUPERSEDED);
            }

            log.info("Registered webhook: workspaceId={}, groupId={}, webhookId={}", scopeId, groupId, webhookId);
            reportAvailability(connection.get(), config, scopeId, WebhookSetupResult.success(webhookId, groupId));
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
     * The status with which GitLab refused group hooks, or empty when it lists them. Community Edition has no group
     * hook API (404), and an unlicensed instance or a token without the Owner role is refused (403). Project hooks need
     * only the Maintainer role, in every tier.
     */
    private static Optional<Integer> groupHookRefusal(GitLabWebhookClient client, Long scopeId, long groupId) {
        try {
            client.listGroupWebhooks(scopeId, groupId);
            return Optional.empty();
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404) return Optional.of(status);
            throw e;
        }
    }

    /**
     * Keeps one hook on each monitored project, all under one ownership marker. A hook under another key or marker,
     * and the group hook, are released first; a hook of a project that is no longer monitored is released; a monitored
     * project without a hook gets one. A project whose hooks GitLab refuses is skipped and retried later.
     *
     * @param groupRefusal the status that refused group hooks, or 0 when the group already uses project hooks
     */
    private WebhookSetupResult registerProjectHooks(
            GitLabWebhookClient client,
            Long scopeId,
            long connectionId,
            String origin,
            GitLabConfig config,
            String webhookUrl,
            String token,
            boolean verify,
            int groupRefusal) {
        long groupId = config.gitlabGroupId();
        String marker = config.webhookOwnershipMarker();
        boolean current = marker != null
                && config.gitlabWebhookId() == null
                && ProviderResourceAdapter.fingerprint(webhookUrl + RESOURCE_QUERY + marker)
                        .equals(config.webhookOwnershipHash());
        Set<Long> gone = new HashSet<>();
        if (current && verify) {
            String url = webhookUrl + RESOURCE_QUERY + marker;
            for (GitLabProjectHook hook : config.projectHooks()) {
                Optional<WebhookInfo> live = client.getProjectWebhook(scopeId, hook.projectId(), hook.hookId());
                if (live.isEmpty() || !url.equals(live.get().url())) {
                    gone.add(hook.projectId());
                } else if (live.get().isDisabled()) {
                    // GitLab keeps a disabled hook listed under the same URL, so its replacement needs a new marker.
                    current = false;
                }
            }
        }
        if (!current) {
            // The first hook GitLab still has names the key the stored hooks were registered under.
            for (GitLabProjectHook hook : config.projectHooks()) {
                Optional<WebhookInfo> live = client.getProjectWebhook(scopeId, hook.projectId(), hook.hookId());
                if (live.isEmpty()) continue;
                Optional<String> storedKey = ownKeyId(stripQuery(live.get().url()), connectionId);
                if (storedKey.isPresent() && !routeCredential.isAccepted(storedKey.get())) {
                    return WebhookSetupResult.skipped(WebhookSetupResult.Reason.ROUTING_KEY_UNKNOWN);
                }
                break;
            }
        }
        if (!current
                && (config.gitlabWebhookId() != null || !config.projectHooks().isEmpty())) {
            releaseAll(scopeId, connectionId, config, origin);
        }
        if (!current) marker = UUID.randomUUID().toString();
        String url = webhookUrl + RESOURCE_QUERY + Objects.requireNonNull(marker);
        GitLabConfig stored = current ? config : config.withoutWebhooks();

        Set<Long> monitored = monitors.monitors(scopeId).stream()
                .map(RepositoryToMonitor::getNativeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> released = stored.projectHooks().stream()
                .map(GitLabProjectHook::projectId)
                .filter(project -> !monitored.contains(project) || gone.contains(project))
                .collect(Collectors.toSet());
        if (!released.isEmpty()) {
            registrations.releaseHooks(
                    scopeId,
                    connectionId,
                    projectHookResources(stored, origin, released),
                    cfg -> ((GitLabConfig) cfg).withoutProjectHooks(released));
        }

        Set<Long> hooked = stored.projectHooks().stream()
                .map(GitLabProjectHook::projectId)
                .filter(project -> !released.contains(project))
                .collect(Collectors.toSet());
        int refusals = 0;
        int lastRefusal = groupRefusal;
        for (long projectId : monitored.stream().sorted().toList()) {
            if (hooked.contains(projectId)) continue;
            try {
                registerProjectHook(client, scopeId, connectionId, origin, groupId, projectId, url, token, marker);
                hooked.add(projectId);
            } catch (WebClientResponseException e) {
                if (!GitLabWebhookClient.isPermissionOrNotFoundError(e.getStatusCode())) throw e;
                refusals++;
                lastRefusal = e.getStatusCode().value();
                log.atWarn()
                        .addKeyValue("event.name", "integration.webhook.registration.failed")
                        .addKeyValue("integration.kind", IntegrationKind.GITLAB)
                        .addKeyValue("workspace.id", scopeId)
                        .addKeyValue("http.response.status_code", lastRefusal)
                        .log("GitLab refused a project hook");
            }
        }
        if (refusals == 0) {
            log.info(
                    "Project hooks registered: workspaceId={}, groupId={}, projects={}",
                    scopeId,
                    groupId,
                    hooked.size());
            return WebhookSetupResult.projectHooks(groupId);
        }
        return WebhookSetupResult.failed(
                hooked.isEmpty()
                        ? WebhookSetupResult.Reason.HOOKS_REFUSED
                        : WebhookSetupResult.Reason.PROJECT_HOOKS_INCOMPLETE,
                lastRefusal);
    }

    /**
     * Registers or adopts the hook of one project. The listing comes first, so a project whose hooks GitLab refuses
     * leaves no registration marker behind.
     */
    private void registerProjectHook(
            GitLabWebhookClient client,
            Long scopeId,
            long connectionId,
            String origin,
            long groupId,
            long projectId,
            String url,
            String token,
            String marker) {
        List<WebhookInfo> matching = matching(client.listProjectWebhooks(scopeId, projectId), url);
        String ownershipHash = ProviderResourceAdapter.fingerprint(url);
        UUID registration = registrations.beginRegistration(
                scopeId,
                connectionId,
                new Resource(
                        IntegrationKind.GITLAB,
                        origin,
                        ResourceType.REGISTRATION,
                        GitLabResourceAdapter.projectParent(groupId, projectId),
                        marker,
                        ownershipHash));
        if (matching.isEmpty()) {
            client.registerProjectWebhook(scopeId, projectId, hookConfig(url, token));
            matching = matching(client.listProjectWebhooks(scopeId, projectId), url);
            if (matching.isEmpty()) {
                throw new IllegalStateException("Registered webhook is not listed on the project");
            }
        }
        long hookId = matching.getFirst().id();
        if (!registrations.completeRegistration(scopeId, connectionId, registration, cfg -> {
            if (!(cfg instanceof GitLabConfig gitlab)) throw new IllegalStateException("Expected GitLab config");
            return gitlab.withProjectHook(projectId, hookId, ownershipHash, marker);
        })) {
            throw new ObjectOptimisticLockingFailureException(GitLabConfig.class, connectionId);
        }
    }

    /** Captures the removal of the group hook and every project hook, and forgets them. */
    private void releaseAll(Long scopeId, long connectionId, GitLabConfig config, String origin) {
        List<Resource> hooks = config.gitlabWebhookId() != null
                ? List.of(new Resource(
                        IntegrationKind.GITLAB,
                        origin,
                        ResourceType.GROUP_HOOK,
                        config.gitlabGroupId().toString(),
                        config.gitlabWebhookId().toString(),
                        config.webhookOwnershipHash()))
                : projectHookResources(
                        config,
                        origin,
                        config.projectHooks().stream()
                                .map(GitLabProjectHook::projectId)
                                .collect(Collectors.toSet()));
        registrations.releaseHooks(scopeId, connectionId, hooks, cfg -> ((GitLabConfig) cfg).withoutWebhooks());
    }

    private static List<Resource> projectHookResources(GitLabConfig config, String origin, Set<Long> projects) {
        return config.projectHooks().stream()
                .filter(hook -> projects.contains(hook.projectId()))
                .map(hook -> new Resource(
                        IntegrationKind.GITLAB,
                        origin,
                        ResourceType.PROJECT_HOOK,
                        GitLabResourceAdapter.projectParent(config.gitlabGroupId(), hook.projectId()),
                        Long.toString(hook.hookId()),
                        config.webhookOwnershipHash()))
                .toList();
    }

    /**
     * Records whether GitLab delivers to this connection. Only a refusal of every hook raises the problem, so a
     * transient error never claims that GitLab cannot deliver.
     */
    private void reportAvailability(
            Connection connection, GitLabConfig config, Long scopeId, WebhookSetupResult result) {
        // A registered hook clears the record as it is stored. The record, not the notice, stops each sync from
        // running the setup again: another problem can hold the notice.
        if (result.reason() == WebhookSetupResult.Reason.HOOKS_REFUSED && config.webhooksRefusedAt() == null) {
            connectionService.updateConfig(
                    scopeId, IntegrationKind.GITLAB, cfg -> ((GitLabConfig) cfg).withWebhooksRefusedAt(Instant.now()));
        }
        // The connection read before the provider calls only spares a write; the attention service decides under lock.
        Problem shown = connection.getAttentionProblem();
        long connectionId = Objects.requireNonNull(connection.getId());
        boolean delivers = result.registered() || result.reason() == WebhookSetupResult.Reason.PROJECT_HOOKS_INCOMPLETE;
        if (delivers && shown == Problem.WEBHOOK_UNAVAILABLE) {
            attention.reportUnlessOther(connectionId, scopeId, Problem.WEBHOOK_UNAVAILABLE, true);
        } else if (result.reason() == WebhookSetupResult.Reason.HOOKS_REFUSED && shown == null) {
            attention.reportUnlessOther(connectionId, scopeId, Problem.WEBHOOK_UNAVAILABLE, false);
        }
    }

    private static String stripQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static WebhookConfig hookConfig(String url, String token) {
        return new WebhookConfig(
                url,
                token,
                true, // merge_requests_events
                true, // issues_events
                true, // confidential_issues_events
                true, // note_events
                true, // confidential_note_events
                true, // push_events
                false, // tag_push_events: no handler reads tag pushes
                true, // pipeline_events
                true, // milestone_events
                true, // member_events
                true, // subgroup_events
                true, // project_events
                true // enable_ssl_verification
                );
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
     * Runs two minutes after start, so a hook from a 1.0 release candidate records its ownership before an admin
     * can disconnect its workspace, and then every 6 hours. Non-fatal: errors are logged but don't affect other
     * operations.
     */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT6H")
    @SchedulerLock(name = "gitlab-webhook-health", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void checkWebhookHealth() {
        var client = webhookClientProvider.getIfAvailable();
        if (client == null || !routeCredential.isConfigured()) {
            return;
        }

        record GitLabHealthCandidate(
                Workspace workspace,
                long connectionId,
                Long groupId,
                @Nullable Long webhookId,
                @Nullable String ownershipHash) {}

        List<GitLabHealthCandidate> gitLabWorkspaces = workspaceLookup.active().stream()
                .map(ws -> {
                    Connection connection = connectionService
                            .findActive(ws.getId(), IntegrationKind.GITLAB)
                            .orElse(null);
                    Long connectionId = connection != null ? connection.getId() : null;
                    if (connection == null
                            || connectionId == null
                            || !(connection.getConfig() instanceof GitLabConfig cfg)
                            || (cfg.gitlabWebhookId() == null
                                    && cfg.projectHooks().isEmpty()
                                    && cfg.webhooksRefusedAt() == null)) {
                        return null;
                    }
                    return new GitLabHealthCandidate(
                            ws, connectionId, cfg.gitlabGroupId(), cfg.gitlabWebhookId(), cfg.webhookOwnershipHash());
                })
                .filter(Objects::nonNull)
                .toList();

        if (gitLabWorkspaces.isEmpty()) return;

        int checked = 0, reregistered = 0;
        for (GitLabHealthCandidate candidate : gitLabWorkspaces) {
            Workspace workspace = candidate.workspace();
            if (candidate.webhookId() == null) {
                // Project hooks are read back one by one, and a refused connection asks GitLab again.
                try {
                    WebhookSetupResult result = registerWebhook(workspace, true);
                    checked++;
                    if (!result.registered()) {
                        log.warn(
                                "GitLab webhook health check: workspaceId={}, reason={}",
                                workspace.getId(),
                                result.reason());
                    }
                } catch (Exception e) {
                    log.debug("Webhook health check failed: workspaceId={}", workspace.getId(), e);
                }
                continue;
            }
            try {
                Optional<WebhookInfo> existing = client.getGroupWebhook(
                        workspace.getId(), candidate.groupId(), Objects.requireNonNull(candidate.webhookId()));
                checked++;

                boolean missing = existing.isEmpty();
                boolean disabled = existing.isPresent() && existing.get().isDisabled();
                boolean own = existing.isPresent()
                        && existing.get().url().startsWith(connectionWebhookUrl(candidate.connectionId()));
                if (own && !disabled && candidate.ownershipHash() != null) {
                    continue; // this connection's hook exists and is still delivering — nothing to do
                }

                if (own && !disabled) {
                    // A hook from a 1.0 release candidate: registerWebhook records its ownership and keeps it.
                    WebhookSetupResult result = registerWebhook(workspace);
                    if (!result.registered()) {
                        log.warn(
                                "Could not record webhook ownership: workspaceId={}, reason={}",
                                workspace.getId(),
                                result.reason());
                    }
                    continue;
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
