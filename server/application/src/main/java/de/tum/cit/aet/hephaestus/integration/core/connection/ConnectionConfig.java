package de.tum.cit.aet.hephaestus.integration.core.connection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    /** Enabled sync streams (subset of the source's catalog). */
    Set<String> enabledStreams();

    record GitHubAppConfig(
            @Nullable Long installationId,
            @Nullable String orgLogin,
            @Nullable String serverUrl, // null for github.com, set for GHES
            Set<String> enabledStreams)
            implements ConnectionConfig {}

    record GitHubPatConfig(
            @Nullable String orgLogin, @Nullable String serverUrl, Set<String> enabledStreams)
            implements ConnectionConfig {}

    /** GitLab — supports both legacy plaintext token verifier and 19.0+ HMAC whsec_*. */
    record GitLabConfig(
            @Nullable String serverUrl,
            @Nullable Long gitlabGroupId,
            @Nullable Long gitlabWebhookId,
            SigningMode signingMode,
            Set<String> enabledStreams,
            @Nullable GitLabTokenMetadata tokenMetadata,
            GitLabProjectExclusions projectExclusions)
            implements ConnectionConfig {
        public GitLabConfig {
            projectExclusions = Objects.requireNonNullElseGet(projectExclusions, GitLabProjectExclusions::empty);
        }

        public GitLabConfig(
                @Nullable String serverUrl,
                @Nullable Long gitlabGroupId,
                @Nullable Long gitlabWebhookId,
                SigningMode signingMode,
                Set<String> enabledStreams,
                @Nullable GitLabTokenMetadata tokenMetadata) {
            this(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    signingMode,
                    enabledStreams,
                    tokenMetadata,
                    GitLabProjectExclusions.empty());
        }

        public GitLabConfig withTokenMetadata(@Nullable GitLabTokenMetadata metadata) {
            return new GitLabConfig(
                    serverUrl,
                    gitlabGroupId,
                    gitlabWebhookId,
                    signingMode,
                    enabledStreams,
                    metadata,
                    projectExclusions);
        }

        public GitLabConfig withProjectExclusions(GitLabProjectExclusions exclusions) {
            return new GitLabConfig(
                    serverUrl, gitlabGroupId, gitlabWebhookId, signingMode, enabledStreams, tokenMetadata, exclusions);
        }

        public enum SigningMode {
            PLAINTEXT,
            WHSEC,
        }

        /**
         * Returns a copy with {@link #gitlabWebhookId} replaced, stamped after webhook
         * registration/adoption. Pair with {@code connectionService.updateConfig(...)} to
         * persist the swap atomically.
         */
        public GitLabConfig withGitlabWebhookId(@Nullable Long webhookId) {
            return new GitLabConfig(
                    serverUrl, gitlabGroupId, webhookId, signingMode, enabledStreams, tokenMetadata, projectExclusions);
        }

        /**
         * Returns a copy with {@link #gitlabGroupId} replaced, for when the GraphQL group
         * lookup resolves the numeric id on a workspace that only carried the group path.
         */
        public GitLabConfig withGitlabGroupId(@Nullable Long groupId) {
            return new GitLabConfig(
                    serverUrl, groupId, gitlabWebhookId, signingMode, enabledStreams, tokenMetadata, projectExclusions);
        }
    }

    /**
     * Explicit stops survive mirror deletion. Known projects are identified only by native ID;
     * a legacy monitor without a known source ID stops its path until a canonical read binds the ID.
     *
     * <p>A Stop of a known project also records an opaque receipt. Removing the work the workspace kept from that
     * project must name it, so a request prepared before a later Add and Stop cannot act on the newer Stop. A new
     * Stop replaces the receipt and an Add clears it. Stops recorded before receipts existed have none, so their kept
     * work cannot be removed until the project is stopped again.
     */
    record GitLabProjectExclusions(Set<Long> nativeIds, Set<String> unresolvedPaths, Map<Long, UUID> stopReceipts) {
        public GitLabProjectExclusions {
            nativeIds = Set.copyOf(Objects.requireNonNullElse(nativeIds, Set.of()));
            unresolvedPaths = Objects.requireNonNullElse(unresolvedPaths, Set.<String>of()).stream()
                    .map(GitLabProjectExclusions::normalizePath)
                    .collect(Collectors.toUnmodifiableSet());
            stopReceipts = Map.copyOf(Objects.requireNonNullElse(stopReceipts, Map.<Long, UUID>of()));
            if (nativeIds.stream().anyMatch(id -> id <= 0) || unresolvedPaths.contains("")) {
                throw new IllegalArgumentException("Project exclusions require positive IDs or nonblank paths");
            }
            if (!nativeIds.containsAll(stopReceipts.keySet())) {
                throw new IllegalArgumentException("A stop receipt requires the excluded project ID");
            }
        }

        public GitLabProjectExclusions(Set<Long> nativeIds, Set<String> unresolvedPaths) {
            this(nativeIds, unresolvedPaths, Map.of());
        }

        public static GitLabProjectExclusions empty() {
            return new GitLabProjectExclusions(Set.of(), Set.of());
        }

        public boolean excludes(@Nullable Long nativeId, String path) {
            return (nativeId != null && nativeIds.contains(nativeId)) || unresolvedPaths.contains(normalizePath(path));
        }

        public Optional<UUID> stopReceipt(long nativeId) {
            return Optional.ofNullable(stopReceipts.get(nativeId));
        }

        public GitLabProjectExclusions exclude(@Nullable Long nativeId, String path) {
            Set<Long> ids = new HashSet<>(nativeIds);
            Set<String> paths = new HashSet<>(unresolvedPaths);
            if (nativeId == null) paths.add(normalizePath(path));
            else ids.add(nativeId);
            return new GitLabProjectExclusions(ids, paths, stopReceipts);
        }

        /** Excludes a known project under a new receipt, which replaces the receipt of any earlier Stop. */
        public GitLabProjectExclusions stop(long nativeId, String path, UUID receipt) {
            var excluded = exclude(nativeId, path);
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            receipts.put(nativeId, receipt);
            return new GitLabProjectExclusions(excluded.nativeIds(), excluded.unresolvedPaths(), receipts);
        }

        /** Keeps the project excluded and spends its receipt once the work kept from it is removed. */
        public GitLabProjectExclusions withoutStopReceipt(long nativeId) {
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            receipts.remove(nativeId);
            return new GitLabProjectExclusions(nativeIds, unresolvedPaths, receipts);
        }

        public GitLabProjectExclusions include(@Nullable Long nativeId, String path) {
            Set<Long> ids = new HashSet<>(nativeIds);
            Set<String> paths = new HashSet<>(unresolvedPaths);
            Map<Long, UUID> receipts = new HashMap<>(stopReceipts);
            if (nativeId != null) {
                ids.remove(nativeId);
                receipts.remove(nativeId);
            }
            paths.remove(normalizePath(path));
            return new GitLabProjectExclusions(ids, paths, receipts);
        }

        public GitLabProjectExclusions resolvePath(long nativeId, String path) {
            return excludes(null, path) ? include(null, path).exclude(nativeId, path) : this;
        }

        private static String normalizePath(String path) {
            return path.trim().toLowerCase(Locale.ROOT);
        }
    }

    record GitLabTokenMetadata(@Nullable LocalDate expiresAt, Instant checkedAt) {}

    /** Slack bot identity, enabled streams, and message-retention configuration. */
    record SlackConfig(
            @Nullable String teamId,
            @Nullable String teamName,
            @Nullable Integer retentionDays,
            Set<String> enabledStreams)
            implements ConnectionConfig {
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
         * replaced, stamped after the subscription is registered. Pair with
         * {@code connectionService.updateConfig(...)} to persist the swap atomically.
         */
        public OutlineConfig withWebhookSubscription(@Nullable String subscriptionId, @Nullable String route) {
            return new OutlineConfig(serverUrl, subscriptionId, route, enabledStreams);
        }
    }
}
