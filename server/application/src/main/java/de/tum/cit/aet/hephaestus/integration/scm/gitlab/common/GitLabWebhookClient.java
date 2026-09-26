package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scope-based client for GitLab webhook CRUD operations.
 *
 * <p>Uses <b>GraphQL</b> for group lookup (reuses existing infrastructure:
 * {@link GitLabGraphQlClientProvider}, {@link GitLabGroupResponse},
 * {@link GitLabSyncConstants#extractNumericId}) and <b>REST</b> for webhook
 * management (no GraphQL mutations exist for webhooks).
 *
 * <p>All methods are scope-aware: they resolve the access token and server URL
 * for each workspace via {@link GitLabTokenService}.
 *
 * @see <a href="https://docs.gitlab.com/ee/api/group_level_webhooks.html">GitLab Group Webhooks API</a>
 */
@Service
@EgressExempt(EgressExemption.WEBHOOK_REGISTRATION)
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int SIGNING_TOKEN_MAJOR = 19;
    private static final int SIGNING_TOKEN_MINOR = 1;

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabTokenService tokenService;
    private final WebClient webClient;

    public GitLabWebhookClient(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabTokenService tokenService,
            WebClient.Builder webClientBuilder) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.tokenService = tokenService;
        this.webClient = webClientBuilder.build();
    }

    /** Resolved server URL and access token for a given scope. */
    record ScopeCredentials(String serverUrl, String token) {
        @Override
        public String toString() {
            return "ScopeCredentials[serverUrl=" + serverUrl + "]";
        }
    }

    private ScopeCredentials resolveCredentials(Long scopeId) {
        return new ScopeCredentials(tokenService.resolveServerUrl(scopeId), tokenService.getAccessToken(scopeId));
    }

    /**
     * Looks up a GitLab group by its full path and returns the stable numeric ID.
     *
     * @param scopeId   the workspace/scope ID for authentication
     * @param groupPath the full path of the group (e.g., {@code "org/team"})
     * @return group info including numeric ID
     * @throws IllegalStateException    if the group is not found
     * @throws IllegalArgumentException if the global ID format is invalid
     */
    public GroupInfo lookupGroup(Long scopeId, String groupPath) {
        GitLabGroupResponse group = graphQlClientProvider
                .forScope(scopeId)
                .documentName("GetGroup")
                .variable("fullPath", groupPath)
                .retrieve("group")
                .toEntity(GitLabGroupResponse.class)
                .block(REQUEST_TIMEOUT);

        if (group == null) {
            throw new IllegalStateException("GitLab group not found: path=" + groupPath + ", scopeId=" + scopeId);
        }

        String id = group.id();
        String name = group.name();
        String fullPath = group.fullPath();
        if (id == null || name == null || fullPath == null) {
            throw new IllegalStateException("GitLab group response is missing required fields: path=" + groupPath);
        }

        long numericId = GitLabSyncConstants.extractNumericId(id);
        return new GroupInfo(numericId, name, fullPath);
    }

    /**
     * Registers a group-level webhook.
     *
     * @param scopeId  the workspace/scope ID
     * @param groupId  the numeric group ID
     * @param config   webhook configuration
     * @return webhook info with the assigned ID
     * @throws WebClientResponseException on API errors (e.g., 403 Forbidden)
     */
    public WebhookInfo registerGroupWebhook(Long scopeId, long groupId, WebhookConfig config) {
        ScopeCredentials credentials = resolveCredentials(scopeId);

        Map<String, Object> response = webClient
                .post()
                .uri(credentials.serverUrl() + "/api/v4/groups/{groupId}/hooks", groupId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                .bodyValue(config.toPayload())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException(
                    "Null response from GitLab when registering webhook: scopeId=" + scopeId + ", groupId=" + groupId);
        }

        if (response.get("id") == null) {
            throw new IllegalStateException(
                    "GitLab webhook response missing 'id' field: scopeId=" + scopeId + ", groupId=" + groupId);
        }
        WebhookInfo registered = webhookInfo(response);
        log.info(
                "Registered GitLab group webhook: scopeId={}, groupId={}, webhookId={}",
                scopeId,
                groupId,
                registered.id());
        return registered;
    }

    /**
     * Rewrites an existing group hook with {@code config}, keeping its id and URL — GitLab drops a
     * hook's secret token when its URL changes.
     *
     * @throws WebClientResponseException on API errors
     */
    public WebhookInfo updateGroupWebhook(Long scopeId, long groupId, long webhookId, WebhookConfig config) {
        ScopeCredentials credentials = resolveCredentials(scopeId);

        Map<String, Object> response = webClient
                .put()
                .uri(credentials.serverUrl() + "/api/v4/groups/{groupId}/hooks/{hookId}", groupId, webhookId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                .bodyValue(config.toPayload())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null || response.get("id") == null) {
            throw new IllegalStateException("GitLab webhook update returned no hook: scopeId=" + scopeId + ", groupId="
                    + groupId + ", webhookId=" + webhookId);
        }
        return webhookInfo(response);
    }

    /**
     * Whether the scope's GitLab instance always stores a hook {@code signing_token} it is sent: 19.1 and
     * later. GitLab 19.0 introduced the field behind the {@code webhook_signing_token} feature flag and
     * ignores it while the flag is off; an older instance ignores the unknown field. Since GitLab never
     * returns the key, a successful update that carried it is the only evidence the hook holds ours, and
     * that evidence holds only where the field cannot be ignored. An unreadable version counts as
     * unsupported.
     */
    public boolean supportsSigningTokens(Long scopeId) {
        ScopeCredentials credentials = resolveCredentials(scopeId);

        Map<String, Object> response = webClient
                .get()
                .uri(credentials.serverUrl() + "/api/v4/version")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null || !(response.get("version") instanceof String version)) {
            return false;
        }
        String[] parts = version.split("[.-]", 3);
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > SIGNING_TOKEN_MAJOR || (major == SIGNING_TOKEN_MAJOR && minor >= SIGNING_TOKEN_MINOR);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Deregisters a group-level webhook. Silently succeeds if the webhook was already deleted (404).
     *
     * @param scopeId   the workspace/scope ID
     * @param groupId   the numeric group ID
     * @param webhookId the webhook ID to delete
     */
    public void deregisterGroupWebhook(Long scopeId, long groupId, long webhookId) {
        deregisterGroupWebhook(resolveCredentials(scopeId), scopeId, groupId, webhookId);
    }

    public void deregisterGroupWebhookWithCredentials(String serverUrl, String token, long groupId, long webhookId) {
        deregisterGroupWebhook(new ScopeCredentials(serverUrl, token), null, groupId, webhookId);
    }

    private void deregisterGroupWebhook(
            ScopeCredentials credentials, @Nullable Long scopeId, long groupId, long webhookId) {
        try {
            webClient
                    .delete()
                    .uri(credentials.serverUrl() + "/api/v4/groups/{groupId}/hooks/{hookId}", groupId, webhookId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                    .retrieve()
                    .toBodilessEntity()
                    .block(REQUEST_TIMEOUT);

            log.info(
                    "Deregistered GitLab group webhook: scopeId={}, groupId={}, webhookId={}",
                    scopeId,
                    groupId,
                    webhookId);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info(
                        "GitLab webhook already deleted: scopeId={}, groupId={}, webhookId={}",
                        scopeId,
                        groupId,
                        webhookId);
            } else {
                throw e;
            }
        }
    }

    /**
     * Gets a specific group webhook by ID.
     *
     * @param scopeId   the workspace/scope ID
     * @param groupId   the numeric group ID
     * @param webhookId the webhook ID
     * @return the webhook info, or empty if not found (404)
     */
    public Optional<WebhookInfo> getGroupWebhook(Long scopeId, long groupId, long webhookId) {
        ScopeCredentials credentials = resolveCredentials(scopeId);

        try {
            Map<String, Object> response = webClient
                    .get()
                    .uri(credentials.serverUrl() + "/api/v4/groups/{groupId}/hooks/{hookId}", groupId, webhookId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(REQUEST_TIMEOUT);

            if (response == null || response.get("id") == null) {
                return Optional.empty();
            }
            return Optional.of(webhookInfo(response));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Lists all group-level webhooks.
     *
     * @param scopeId the workspace/scope ID
     * @param groupId the numeric group ID
     * @return list of webhook info
     */
    public List<WebhookInfo> listGroupWebhooks(Long scopeId, long groupId) {
        ScopeCredentials credentials = resolveCredentials(scopeId);

        List<Map<String, Object>> response = webClient
                .get()
                .uri(credentials.serverUrl() + "/api/v4/groups/{groupId}/hooks?per_page=100", groupId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null) {
            return List.of();
        }

        return Objects.requireNonNull(response).stream()
                .filter(hook -> hook.get("id") != null)
                .map(GitLabWebhookClient::webhookInfo)
                .toList();
    }

    private static WebhookInfo webhookInfo(Map<String, Object> hook) {
        return new WebhookInfo(
                ((Number) Objects.requireNonNull(hook.get("id"))).longValue(),
                Objects.requireNonNull((String) hook.get("url")),
                (String) hook.get("alert_status"),
                Boolean.TRUE.equals(hook.get("signing_token_present")),
                Boolean.TRUE.equals(hook.get("token_present")));
    }

    /**
     * Checks whether a 401, 403, or 404 response indicates an authentication/permission
     * issue or a missing resource (as opposed to a transient failure).
     *
     * <p>Includes 401 (Unauthorized) because an expired or invalid token is non-retryable.
     * Note: 404 may indicate either a missing resource or that the caller lacks
     * permission to view it (GitLab returns 404 for unauthorized access to private resources).
     */
    public static boolean isPermissionOrNotFoundError(HttpStatusCode status) {
        return status.value() == 401 || status.value() == 403 || status.value() == 404;
    }

    public record GroupInfo(long id, String name, String fullPath) {}

    /**
     * A GitLab group hook as read back from the API.
     *
     * @param id          the numeric hook id
     * @param url         the delivery URL
     * @param alertStatus the hook's delivery health from GitLab's project/group hooks API —
     *                    {@code "executable"} (healthy), {@code "disabled"} (auto-disabled after
     *                    repeated failures — GitLab keeps the row but stops delivering), or
     *                    {@code "temporarily_disabled"} (transient backoff GitLab auto-recovers).
     *                    {@code null} when the field is absent (older GitLab, or the register response).
     * @param signingTokenPresent GitLab's {@code signing_token_present}: the hook signs its deliveries.
     *                    GitLab never returns the token itself, and before 19.0 not this field either.
     * @param tokenPresent GitLab's {@code token_present}: the hook has a secret token. Reported since
     *                    GitLab 19.0 like {@code signing_token_present}; {@code false} when absent.
     * @see <a href="https://docs.gitlab.com/api/group_webhooks/">GitLab Group Webhooks API</a>
     */
    public record WebhookInfo(
            long id, String url, @Nullable String alertStatus, boolean signingTokenPresent, boolean tokenPresent) {
        /** Two-arg convenience for callers/tests that don't care about delivery health. */
        public WebhookInfo(long id, String url) {
            this(id, url, null, false, false);
        }

        /**
         * Whether GitLab has auto-disabled this hook (terminal {@code "disabled"} state). A disabled
         * hook still exists on GitLab — so a mere existence check passes forever — but no longer
         * delivers, which is exactly the invisible-failure the health check must heal. The transient
         * {@code "temporarily_disabled"} backoff is deliberately excluded: GitLab re-enables it on its
         * own, so re-registering on it would only churn.
         */
        public boolean isDisabled() {
            return "disabled".equals(alertStatus);
        }
    }

    /**
     * A group hook request body: every event Hephaestus consumes, and the token fields this request sets
     * or clears. A field it leaves out keeps the hook's current token, which is also the only form a
     * GitLab before 19.0 is sent. Clearing is an explicit JSON {@code null}: GitLab assigns only the
     * fields a request carries, and {@code signing_token} validates with {@code allow_nil}. The body is
     * a JSON tree because the application's {@code non_null} default would drop that {@code null} from
     * a map or record.
     *
     * @param tokens {@code token} (the legacy secret token, sent as {@code X-Gitlab-Token}) and
     *               {@code signing_token} (the GitLab 19.0+ key behind {@code webhook-signature}); a
     *               {@code null} value clears that token
     * @see <a href="https://docs.gitlab.com/api/group_webhooks/#add-a-group-hook">Add Group Hook</a>
     */
    public record WebhookConfig(String url, Map<String, @Nullable String> tokens) {

        private static final List<String> EVENTS = List.of(
                "merge_requests_events",
                "issues_events",
                "confidential_issues_events",
                "note_events",
                "confidential_note_events",
                "push_events",
                "tag_push_events",
                "pipeline_events",
                "milestone_events",
                "member_events",
                "subgroup_events",
                "project_events",
                "enable_ssl_verification");

        /** Sets the secret token and leaves any signing token alone. */
        public static WebhookConfig secretToken(String url, String secret) {
            return new WebhookConfig(url, tokens(secret, null, false));
        }

        /** Sets both tokens, so a hook keeps the secret token until the signing token is confirmed. */
        public static WebhookConfig bothTokens(String url, String secret) {
            return new WebhookConfig(url, tokens(secret, secret, true));
        }

        /** Sets the signing token and clears the secret token. */
        public static WebhookConfig signingTokenOnly(String url, String secret) {
            return new WebhookConfig(url, tokens(null, secret, true));
        }

        /** Sets the secret token and clears the signing token. */
        public static WebhookConfig secretTokenOnly(String url, String secret) {
            return new WebhookConfig(url, tokens(secret, null, true));
        }

        private static Map<String, @Nullable String> tokens(
                @Nullable String token, @Nullable String signingToken, boolean includeSigningToken) {
            Map<String, @Nullable String> tokens = new LinkedHashMap<>();
            tokens.put("token", token);
            if (includeSigningToken) {
                tokens.put("signing_token", signingToken);
            }
            return tokens;
        }

        public ObjectNode toPayload() {
            ObjectNode body = JsonNodeFactory.instance.objectNode().put("url", url);
            EVENTS.forEach(event -> body.put(event, true));
            tokens.forEach((field, value) -> {
                if (value == null) {
                    body.putNull(field);
                } else {
                    body.put(field, value);
                }
            });
            return body;
        }
    }
}
