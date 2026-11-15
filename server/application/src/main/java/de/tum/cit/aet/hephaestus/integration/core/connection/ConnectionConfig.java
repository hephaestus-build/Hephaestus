package de.tum.cit.aet.hephaestus.integration.core.connection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Sealed per-kind Connection configuration. Persisted as JSONB via Hibernate 7's
 * native {@code @JdbcTypeCode(SqlTypes.JSON)} on {@code Connection.config}; Jackson 3
 * deserialises the discriminator below back into the right record subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConnectionConfig.GitHubAppConfig.class, name = "GITHUB_APP"),
    @JsonSubTypes.Type(value = ConnectionConfig.GitHubPatConfig.class, name = "GITHUB_PAT"),
    @JsonSubTypes.Type(value = ConnectionConfig.GitLabConfig.class, name = "GITLAB"),
    @JsonSubTypes.Type(value = ConnectionConfig.SlackConfig.class, name = "SLACK"),
    @JsonSubTypes.Type(value = ConnectionConfig.OutlineConfig.class, name = "OUTLINE"),
})
public sealed interface ConnectionConfig
        permits ConnectionConfig.GitHubAppConfig,
                ConnectionConfig.GitHubPatConfig,
                ConnectionConfig.GitLabConfig,
                ConnectionConfig.SlackConfig,
                ConnectionConfig.OutlineConfig {
    default ConnectionConfig updatedBy(UnaryOperator<ConnectionConfig> mutator) {
        ConnectionConfig next = Objects.requireNonNull(mutator.apply(this), "Connection config");
        if (!next.getClass().equals(getClass()))
            throw new IllegalArgumentException("A config update cannot change the connection mode");
        return next;
    }

    /** Enabled sync streams (subset of the source's catalog). */
    Set<String> enabledStreams();

    record GitHubAppConfig(
            @Nullable Long installationId,
            @Nullable Long appId,
            @Nullable String orgLogin,
            @Nullable String serverUrl,
            Set<String> enabledStreams)
            implements ConnectionConfig {
        public GitHubAppConfig(
                @Nullable Long installationId,
                @Nullable String orgLogin,
                @Nullable String serverUrl,
                Set<String> enabledStreams) {
            this(installationId, null, orgLogin, serverUrl, enabledStreams);
        }

        public GitHubAppConfig {
            serverUrl = Objects.requireNonNullElse(serverUrl, PersonSourceNamespace.GITHUB_DEFAULT_URL);
        }
    }

    record GitHubPatConfig(
            @Nullable String orgLogin, @Nullable String serverUrl, Set<String> enabledStreams)
            implements ConnectionConfig {
        public GitHubPatConfig {
            serverUrl = Objects.requireNonNullElse(serverUrl, PersonSourceNamespace.GITHUB_DEFAULT_URL);
        }
    }

    /**
     * GitLab. The connection's hooks are either one group hook ({@link #gitlabWebhookId}) or, where the GitLab tier has
     * no group hooks, one hook on each monitored project ({@link #projectHooks}). All hooks of one registration share
     * the ownership marker and its hash. {@link #webhooksRefusedAt} records that GitLab refused every hook, until one
     * registers.
     */
    record GitLabConfig(
            String serverUrl,
            Long gitlabGroupId,
            @Nullable Long gitlabWebhookId,
            Set<String> enabledStreams,
            @Nullable GitLabTokenMetadata tokenMetadata,
            GitLabProjectExclusions projectExclusions,
            @Nullable String webhookOwnershipHash,
            @Nullable String webhookOwnershipMarker,
            List<GitLabProjectHook> projectHooks,
            @Nullable Instant webhooksRefusedAt)
            implements ConnectionConfig {
        public GitLabConfig {
            if (gitlabGroupId == null || gitlabGroupId <= 0) {
                throw new InvalidInputException(
                        "A GitLab connection requires the provider group ID. Verify the group before connecting.");
            }
            Objects.requireNonNull(serverUrl, "A GitLab connection names its server");
            if (serverUrl.isBlank()) throw new IllegalArgumentException("A GitLab connection names its server");
            projectExclusions = Objects.requireNonNullElseGet(projectExclusions, GitLabProjectExclusions::empty);
            projectHooks = List.copyOf(Objects.requireNonNullElse(projectHooks, List.of()));
            if (gitlabWebhookId != null && !projectHooks.isEmpty()) {
                throw new IllegalArgumentException("A GitLab connection has a group hook or project hooks, not both");
            }
        }

        public GitLabConfig(
                String serverUrl,
                Long gitlabGroupId,
                @Nullable Long gitlabWebhookId,
                Set<String> enabledStreams,
                @Nullable GitLabTokenMetadata tokenMetadata,
                GitLabProjectExclusions projectExclusions) {
            this(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    null,
                    null,
                    List.of(),
                    null);
        }

        public GitLabConfig(
                String serverUrl,
                Long gitlabGroupId,
                @Nullable Long gitlabWebhookId,
                Set<String> enabledStreams,
                @Nullable GitLabTokenMetadata tokenMetadata) {
            this(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    GitLabProjectExclusions.empty());
        }

        /** The group hook and its ownership. Adopt it through {@code ProviderResourceRepairs.completeRegistration}. */
        public GitLabConfig withWebhookOwnership(@Nullable Long id, @Nullable String hash, @Nullable String marker) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    id,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    hash,
                    marker,
                    List.of(),
                    null);
        }

        /**
         * Records the hook of project {@code projectId}, registered under {@code marker}. A marker other than the stored
         * one starts a new registration: the hooks of the earlier one are dropped, and the caller has captured their
         * removal.
         */
        public GitLabConfig withProjectHook(long projectId, long hookId, String hash, String marker) {
            var hooks = new ArrayList<GitLabProjectHook>();
            if (marker.equals(webhookOwnershipMarker)) {
                projectHooks.stream()
                        .filter(hook -> hook.projectId() != projectId)
                        .forEach(hooks::add);
            }
            hooks.add(new GitLabProjectHook(projectId, hookId));
            hooks.sort(Comparator.comparingLong(GitLabProjectHook::projectId));
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    null,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    hash,
                    marker,
                    hooks,
                    null);
        }

        /** Drops the hooks of {@code projectIds}; the caller has captured their removal. */
        public GitLabConfig withoutProjectHooks(Set<Long> projectIds) {
            var hooks = projectHooks.stream()
                    .filter(hook -> !projectIds.contains(hook.projectId()))
                    .toList();
            boolean none = hooks.isEmpty() && gitlabWebhookId == null;
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    none ? null : webhookOwnershipHash,
                    none ? null : webhookOwnershipMarker,
                    hooks,
                    webhooksRefusedAt);
        }

        /** Forgets every hook, after their removal was captured or completed. */
        public GitLabConfig withoutWebhooks() {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    null,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    null,
                    null,
                    List.of(),
                    webhooksRefusedAt);
        }

        /** Records when GitLab refused every hook, or clears it with {@code null}. */
        public GitLabConfig withWebhooksRefusedAt(@Nullable Instant refusedAt) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    webhookOwnershipHash,
                    webhookOwnershipMarker,
                    projectHooks,
                    refusedAt);
        }

        public GitLabConfig withTokenMetadata(@Nullable GitLabTokenMetadata metadata) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    metadata,
                    projectExclusions,
                    webhookOwnershipHash,
                    webhookOwnershipMarker,
                    projectHooks,
                    webhooksRefusedAt);
        }

        public GitLabConfig withProjectExclusions(GitLabProjectExclusions exclusions) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    exclusions,
                    webhookOwnershipHash,
                    webhookOwnershipMarker,
                    projectHooks,
                    webhooksRefusedAt);
        }

        /**
         * Returns a copy with {@link #gitlabWebhookId} replaced, stamped after webhook
         * registration/adoption. Adopt the result through {@code ProviderResourceRepairs.completeRegistration}, which checks its lifecycle.
         */
        public GitLabConfig withGitlabWebhookId(@Nullable Long webhookId) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    webhookId,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    Objects.equals(webhookId, gitlabWebhookId) ? webhookOwnershipHash : null,
                    Objects.equals(webhookId, gitlabWebhookId) ? webhookOwnershipMarker : null,
                    webhookId == null ? projectHooks : List.of(),
                    webhookId == null ? webhooksRefusedAt : null);
        }

        /** Returns a copy with the provider-verified group ID. */
        public GitLabConfig withGitlabGroupId(Long groupId) {
            return new GitLabConfig(
                    serverUrl,
                    groupId,
                    gitlabWebhookId,
                    enabledStreams,
                    tokenMetadata,
                    projectExclusions,
                    webhookOwnershipHash,
                    webhookOwnershipMarker,
                    projectHooks,
                    webhooksRefusedAt);
        }
    }

    /** The hook that Hephaestus registered on one GitLab project, by GitLab's project and hook ids. */
    record GitLabProjectHook(long projectId, long hookId) {
        public GitLabProjectHook {
            if (projectId <= 0 || hookId <= 0) throw new IllegalArgumentException("A project hook has GitLab ids");
        }
    }

    /**
     * Explicit stops identify projects by the provider ID and survive mirror deletion.
     * A fresh Stop replaces its receipt, and Add clears it. Removal of retained work must name that receipt,
     * so a request prepared for an earlier Stop cannot remove the work of a later one.
     * A stop recorded before receipts existed needs another Stop before its retained work can be removed.
     */
    record GitLabProjectExclusions(Set<Long> nativeIds, Map<Long, UUID> stopReceipts) {
        public GitLabProjectExclusions {
            nativeIds = Set.copyOf(Objects.requireNonNullElse(nativeIds, Set.of()));
            stopReceipts = Map.copyOf(Objects.requireNonNullElse(stopReceipts, Map.of()));
            if (nativeIds.stream().anyMatch(id -> id <= 0)) {
                throw new IllegalArgumentException("Project stops require positive provider IDs");
            }
            if (!nativeIds.containsAll(stopReceipts.keySet())) {
                throw new IllegalArgumentException("A stop receipt requires the excluded project ID");
            }
        }

        public static GitLabProjectExclusions empty() {
            return new GitLabProjectExclusions(Set.of(), Map.of());
        }

        public boolean excludes(long nativeId) {
            return nativeIds.contains(nativeId);
        }

        public Optional<UUID> stopReceipt(long nativeId) {
            return Optional.ofNullable(stopReceipts.get(nativeId));
        }

        /** A fresh Stop replaces the receipt, so an older Remove cannot release the newer Stop's work. */
        public GitLabProjectExclusions stop(long nativeId, UUID receipt) {
            Set<Long> ids = new HashSet<>(nativeIds);
            ids.add(nativeId);
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            receipts.put(nativeId, receipt);
            return new GitLabProjectExclusions(ids, receipts);
        }

        /** Keeps the project excluded and spends its receipt once the retained work is removed. */
        public GitLabProjectExclusions withoutStopReceipt(long nativeId) {
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            receipts.remove(nativeId);
            return new GitLabProjectExclusions(nativeIds, receipts);
        }

        public GitLabProjectExclusions include(long nativeId) {
            Set<Long> ids = new HashSet<>(nativeIds);
            ids.remove(nativeId);
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            receipts.remove(nativeId);
            return new GitLabProjectExclusions(ids, receipts);
        }
    }

    record GitLabTokenMetadata(@Nullable LocalDate expiresAt, Instant checkedAt) {}

    /** Slack bot identity, enabled streams, and message-retention configuration. */
    record SlackConfig(
            @Nullable String teamId,
            @Nullable String botUserId,
            @Nullable String botTokenFingerprint,
            @Nullable String teamName,
            @Nullable Integer retentionDays,
            Set<String> enabledStreams)
            implements ConnectionConfig {
        public SlackConfig(
                @Nullable String teamId,
                @Nullable String teamName,
                @Nullable Integer retentionDays,
                Set<String> enabledStreams) {
            this(teamId, null, null, teamName, retentionDays, enabledStreams);
        }

        /** Default bounded-retention window for ingested Slack messages, in days. */
        public static final int DEFAULT_RETENTION_DAYS = 30;

        /** The configured retention window, or {@link #DEFAULT_RETENTION_DAYS} when unset. */
        public int retentionDaysOrDefault() {
            return retentionDays != null ? retentionDays : DEFAULT_RETENTION_DAYS;
        }
    }

    /**
     * Outline — the server host and (when the change-notification subscription is registered)
     * its id plus the route it posts to ({@code keyId/routeId}, from which the receiver derives its signing secret). Which collections are mirrored is NOT config: the
     * {@code outline_collection} registry is the single source of truth, populated post-connect
     * through the admin surface.
     *
     * <p>{@code serverUrl} is validated against the SSRF guard before any request is made.
     * The webhook fields stay {@code null} until a change-notification subscription is registered.
     */
    record OutlineConfig(
            @Nullable String serverUrl,
            @Nullable String webhookSubscriptionId,
            @Nullable String webhookRoute,
            Set<String> enabledStreams)
            implements ConnectionConfig {
        /**
         * Returns a copy with the change-notification subscription id and route
         * replaced, stamped after the subscription is registered. Adopt the result through {@code ProviderResourceRepairs.completeRegistration}, which checks its lifecycle.
         */
        public OutlineConfig withWebhookSubscription(@Nullable String subscriptionId, @Nullable String route) {
            return new OutlineConfig(serverUrl, subscriptionId, route, enabledStreams);
        }
    }
}
