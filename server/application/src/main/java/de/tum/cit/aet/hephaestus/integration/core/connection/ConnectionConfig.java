package de.tum.cit.aet.hephaestus.integration.core.connection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Sealed per-kind Connection configuration. Persisted as JSONB via Hibernate 7's
 * native {@code @JdbcTypeCode(SqlTypes.JSON)} on {@code Connection.config}; Jackson 3
 * deserialises the discriminator below back into the right record subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConnectionConfig.GitHubAccessConfig.class, name = "GITHUB_ACCESS"),
    @JsonSubTypes.Type(value = ConnectionConfig.GitHubAppConfig.class, name = "GITHUB_APP"),
    @JsonSubTypes.Type(value = ConnectionConfig.GitHubPatConfig.class, name = "GITHUB_PAT"),
    @JsonSubTypes.Type(value = ConnectionConfig.GitLabConfig.class, name = "GITLAB"),
    @JsonSubTypes.Type(value = ConnectionConfig.SlackConfig.class, name = "SLACK"),
    @JsonSubTypes.Type(value = ConnectionConfig.OutlineConfig.class, name = "OUTLINE"),
    @JsonSubTypes.Type(value = ConnectionConfig.KeycloakDirectoryConfig.class, name = "KEYCLOAK_DIRECTORY"),
})
public sealed interface ConnectionConfig
        permits ConnectionConfig.GitHubAccessConfig,
                ConnectionConfig.GitHubAppConfig,
                ConnectionConfig.GitHubPatConfig,
                ConnectionConfig.GitLabConfig,
                ConnectionConfig.SlackConfig,
                ConnectionConfig.OutlineConfig,
                ConnectionConfig.KeycloakDirectoryConfig {
    /** Enabled sync streams (subset of the source's catalog). */
    Set<String> enabledStreams();

    /** The target owns policy and authority. The connection owns only its encrypted credential and job lease. */
    record GitHubAccessConfig(Set<String> enabledStreams) implements ConnectionConfig {}

    record KeycloakDirectoryConfig(String registrationId, String issuer, Set<String> enabledStreams)
            implements ConnectionConfig {}

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
            Set<String> enabledStreams)
            implements ConnectionConfig {
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
            return new GitLabConfig(serverUrl, gitlabGroupId, webhookId, signingMode, enabledStreams);
        }

        /**
         * Returns a copy with {@link #gitlabGroupId} replaced, for when the GraphQL group
         * lookup resolves the numeric id on a workspace that only carried the group path.
         */
        public GitLabConfig withGitlabGroupId(@Nullable Long groupId) {
            return new GitLabConfig(serverUrl, groupId, gitlabWebhookId, signingMode, enabledStreams);
        }
    }

    /** Slack bot identity, notification channel, enabled streams, and message-retention configuration. */
    record SlackConfig(
            @Nullable String teamId,
            @Nullable String teamName,
            @Nullable String notificationChannelId,
            @Nullable String teamLabel,
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
     * its id plus signing secret. Which collections are mirrored is NOT config: the
     * {@code outline_collection} registry is the single source of truth, populated post-connect
     * through the admin surface.
     *
     * <p>{@code serverUrl} is validated against the SSRF guard before any request is made.
     * The webhook fields stay {@code null} until a change-notification subscription is registered.
     */
    record OutlineConfig(
            @Nullable String serverUrl,
            @Nullable String webhookSubscriptionId,
            @Nullable String webhookSecret,
            Set<String> enabledStreams)
            implements ConnectionConfig {
        /**
         * Returns a copy with the change-notification subscription id and signing secret
         * replaced, stamped after the subscription is registered. Pair with
         * {@code connectionService.updateConfig(...)} to persist the swap atomically.
         */
        public OutlineConfig withWebhookSubscription(@Nullable String subscriptionId, @Nullable String signingSecret) {
            return new OutlineConfig(serverUrl, subscriptionId, signingSecret, enabledStreams);
        }
    }
}
