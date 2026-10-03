package de.tum.cit.aet.hephaestus.integration.scm.github.user.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHBot;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHEnterpriseUserAccount;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMannequin;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganization;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub user references.
 * <p>
 * Used across all entities: issues, pull requests, comments, reviews, etc.
 * Provides factory methods for creating from both REST (webhook) and GraphQL responses.
 * <p>
 * <b>Profile Fields:</b> Contains optional profile fields (bio, company, location, blog)
 * that are populated when fetching full user details via GraphQL GetUser query.
 * These may be null for minimal user references from webhooks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUserDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("login") String login,
        @JsonProperty("avatar_url") @Nullable String avatarUrl,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("name") @Nullable String name,
        @JsonProperty("email") @Nullable String email,
        // Profile fields - populated from full user fetch, may be null from webhooks
        @JsonProperty("bio") @Nullable String bio,
        @JsonProperty("company") @Nullable String company,
        @JsonProperty("location") @Nullable String location,
        @JsonProperty("blog") @Nullable String blog,
        @JsonProperty("followers") @Nullable Integer followers,
        @JsonProperty("following") @Nullable Integer following,
        // Timestamp fields - populated from full user fetch via GraphQL
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        // User type - determined from GitHub API type field or GraphQL __typename
        @JsonProperty("type") User.Type type) {
    /**
     * Compact constructor for minimal user references.
     * Profile fields default to null, type defaults to USER.
     */
    public GitHubUserDTO(
            @Nullable Long id,
            @Nullable Long databaseId,
            String login,
            @Nullable String avatarUrl,
            @Nullable String htmlUrl,
            @Nullable String name,
            @Nullable String email) {
        this(
                id,
                databaseId,
                login,
                avatarUrl,
                htmlUrl,
                name,
                email,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                User.Type.USER);
    }

    /**
     * Constructor with type for creating user DTOs with explicit type information.
     */
    public GitHubUserDTO(
            @Nullable Long id,
            @Nullable Long databaseId,
            String login,
            @Nullable String avatarUrl,
            @Nullable String htmlUrl,
            @Nullable String name,
            @Nullable String email,
            User.Type type) {
        this(
                id,
                databaseId,
                login,
                avatarUrl,
                htmlUrl,
                name,
                email,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                type);
    }

    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubUserDTO from a GraphQL GHActor interface.
     * <p>
     * Handles all GHActor implementations: GHUser, GHBot, GHMannequin, GHOrganization, GHEnterpriseUserAccount.
     * Uses pattern matching to extract fields specific to each implementation.
     *
     * @param actor the GHActor (may be null)
     * @return GitHubUserDTO or null if actor is null
     */
    @Nullable
    public static GitHubUserDTO fromActor(@Nullable GHActor actor) {
        if (actor == null) {
            return null;
        }
        return switch (actor) {
            case GHUser user -> fromUser(user);
            case GHBot bot -> fromBot(bot);
            case GHMannequin mannequin -> fromMannequin(mannequin);
            case GHOrganization org -> fromOrganization(org);
            case GHEnterpriseUserAccount enterprise -> fromEnterpriseUserAccount(enterprise);
            default -> fromActorBase(actor);
        };
    }

    /**
     * Creates a GitHubUserDTO from a GraphQL GHUser model.
     * <p>
     * Captures all profile fields available from the GetUser query:
     * bio, company, location, websiteUrl, followers, following.
     */
    @Nullable
    public static GitHubUserDTO fromUser(@Nullable GHUser user) {
        if (user == null) {
            return null;
        }
        return new GitHubUserDTO(
                null,
                user.getDatabaseId() != null ? user.getDatabaseId().longValue() : null,
                user.getLogin(),
                uriToString(user.getAvatarUrl()),
                uriToString(user.getUrl()),
                user.getName(),
                user.getEmail(),
                // Profile fields
                user.getBio(),
                user.getCompany(),
                user.getLocation(),
                uriToString(user.getWebsiteUrl()),
                user.getFollowers() != null ? user.getFollowers().getTotalCount() : null,
                user.getFollowing() != null ? user.getFollowing().getTotalCount() : null,
                // Timestamp fields
                toInstant(user.getCreatedAt()),
                toInstant(user.getUpdatedAt()),
                // User type
                User.Type.USER);
    }

    /**
     * Creates a GitHubUserDTO from a GraphQL GHBot model.
     */
    @Nullable
    public static GitHubUserDTO fromBot(@Nullable GHBot bot) {
        if (bot == null) {
            return null;
        }
        return new GitHubUserDTO(
                null,
                bot.getDatabaseId() != null ? bot.getDatabaseId().longValue() : null,
                bot.getLogin(),
                uriToString(bot.getAvatarUrl()),
                uriToString(bot.getUrl()),
                null,
                null,
                User.Type.BOT);
    }

    /**
     * Creates a GitHubUserDTO from a GraphQL GHMannequin model.
     * Mannequins are placeholder accounts for imported data, treated as USER type.
     */
    @Nullable
    public static GitHubUserDTO fromMannequin(@Nullable GHMannequin mannequin) {
        if (mannequin == null) {
            return null;
        }
        return new GitHubUserDTO(
                null,
                mannequin.getDatabaseId() != null ? mannequin.getDatabaseId().longValue() : null,
                mannequin.getLogin(),
                uriToString(mannequin.getAvatarUrl()),
                uriToString(mannequin.getUrl()),
                null,
                mannequin.getEmail(),
                User.Type.USER);
    }

    /**
     * Creates a GitHubUserDTO from a GraphQL GHOrganization model (when acting as GHActor).
     */
    @Nullable
    public static GitHubUserDTO fromOrganization(@Nullable GHOrganization org) {
        if (org == null) {
            return null;
        }
        return new GitHubUserDTO(
                null,
                org.getDatabaseId() != null ? org.getDatabaseId().longValue() : null,
                org.getLogin(),
                uriToString(org.getAvatarUrl()),
                null,
                org.getName(),
                org.getEmail(),
                User.Type.ORGANIZATION);
    }

    /**
     * Creates a GitHubUserDTO from a GraphQL GHEnterpriseUserAccount model.
     * Enterprise user accounts are treated as USER type.
     */
    @Nullable
    public static GitHubUserDTO fromEnterpriseUserAccount(@Nullable GHEnterpriseUserAccount enterprise) {
        if (enterprise == null) {
            return null;
        }
        return new GitHubUserDTO(
                null,
                null,
                enterprise.getLogin(),
                uriToString(enterprise.getAvatarUrl()),
                uriToString(enterprise.getUrl()),
                enterprise.getName(),
                null,
                User.Type.USER);
    }

    /**
     * Fallback factory for GHActor interface when concrete type is unknown.
     * Defaults to USER type when the specific actor type cannot be determined.
     */
    private static GitHubUserDTO fromActorBase(GHActor actor) {
        return new GitHubUserDTO(
                null,
                null,
                actor.getLogin(),
                uriToString(actor.getAvatarUrl()),
                uriToString(actor.getUrl()),
                null,
                null,
                User.Type.USER);
    }

    /**
     * Determines the User.Type from GitHub's REST API type string.
     * <p>
     * GitHub REST API returns "User", "Bot", or "Organization" in the type field.
     * This method converts these strings to the corresponding User.Type enum.
     *
     * @param gitHubType the type string from GitHub REST API (may be null)
     * @return the corresponding User.Type, defaults to USER if null or unknown
     */
    public static User.Type parseGitHubType(@Nullable String gitHubType) {
        if (gitHubType == null) {
            return User.Type.USER;
        }
        return switch (gitHubType.toLowerCase(Locale.ROOT)) {
            case "bot" -> User.Type.BOT;
            case "organization" -> User.Type.ORGANIZATION;
            default -> User.Type.USER;
        };
    }

    /**
     * Returns the effective type, using parseGitHubType logic if type is null.
     * This is useful when the DTO was created from webhook data where the type
     * field may need to be parsed from a string.
     */
    public User.Type getEffectiveType() {
        return type != null ? type : User.Type.USER;
    }
}
