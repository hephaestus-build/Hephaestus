package de.tum.cit.aet.hephaestus.integration.scm.domain.user;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {
    interface IdentityProfileView {
        long getProviderId();

        long getNativeId();

        String getLogin();

        @Nullable
        String getDisplayName();

        String getAvatarUrl();

        String getProfileUrl();
    }

    @Query("""
        SELECT u.provider.id AS providerId, u.nativeId AS nativeId, u.login AS login,
               u.name AS displayName, u.avatarUrl AS avatarUrl, u.htmlUrl AS profileUrl
        FROM User u WHERE u.provider.id IN :providerIds AND u.nativeId IN :nativeIds
        """)
    List<IdentityProfileView> findIdentityProfiles(
            @Param("providerIds") Collection<Long> providerIds, @Param("nativeIds") Collection<Long> nativeIds);

    @Query("""
            SELECT u
            FROM User u
            WHERE LOWER(u.login) = LOWER(:login)
            ORDER BY u.id
        """)
    List<User> findAllByLogin(@Param("login") String login);

    /**
     * Finds a user by login. If multiple providers have the same login, returns the first match.
     */
    default Optional<User> findByLogin(String login) {
        List<User> users = findAllByLogin(login);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    @Query("""
            SELECT u
            FROM User u
            WHERE LOWER(u.login) = LOWER(:login)
              AND u.provider.id = :providerId
        """)
    Optional<User> findByLoginAndProviderId(@Param("login") String login, @Param("providerId") Long providerId);

    @Query("""
            SELECT u
            FROM User u
            WHERE LOWER(u.email) = LOWER(:email)
              AND u.provider.id = :providerId
            ORDER BY u.id
        """)
    List<User> findAllByEmailAndProviderId(@Param("email") String email, @Param("providerId") Long providerId);

    @Query("""
            SELECT u.id
            FROM User u
            WHERE LOWER(u.login) = LOWER(:login)
              AND u.provider.id = :providerId
              AND u.provider.type = de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType.GITHUB
        """)
    Optional<Long> findGitHubUserIdByLogin(@Param("login") String login, @Param("providerId") Long providerId);

    @Query("""
            SELECT u
            FROM User u
            WHERE u.type = 'USER'
        """)
    List<User> findAllHuman();

    /** Only a verified actor pinned by the workspace boundary or a non-HTTP caller identifies the developer. */
    default Optional<User> getCurrentUser() {
        return CurrentScmIdentityHolder.getUserId().flatMap(this::findById);
    }

    default User getCurrentUserElseThrow() {
        return getCurrentUser().orElseThrow(() -> new EntityNotFoundException("User", "current authenticated user"));
    }

    Optional<User> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    /**
     * The human actors behind an account's enabled links to a git provider, oldest link first. A link with a team key or
     * a non-numeric subject, or one whose actor has not synced yet, has no row. The guarded cast keeps a non-numeric
     * subject from failing the query, and {@code disabled_at IS NULL} lets the partial index on the account's links
     * serve it.
     */
    @NativeQuery("""
        SELECT u.id, p.id, p.type, p.server_url, CAST(u.native_id AS text), u.login, COALESCE(u.name, u.login), true
        FROM identity i
        JOIN identity_provider p ON p.id = i.provider_id
        JOIN "user" u ON u.provider_id = i.provider_id
            AND u.native_id = CASE
                WHEN i.provider_user_key ~ '^[0-9]+$' AND length(i.provider_user_key) <= 18
                    THEN CAST(i.provider_user_key AS bigint)
                END
        WHERE i.account_id = :accountId AND i.disabled_at IS NULL AND i.team_key = '' AND u.type = 'USER'
        ORDER BY i.id
        """)
    List<GitProviderRegistry.ActorProfile> findAccountActors(@Param("accountId") long accountId);

    /**
     * Try to acquire a transaction-scoped advisory lock on the given login.
     * <p>
     * The lock key is derived from {@code hashtext(providerId || ':' || LOWER(login))}, so only
     * operations on the same provider instance and (case-insensitive) login contend.
     *
     * @return true if the lock was acquired, false if another transaction holds it
     */
    @Query(
            value = "SELECT pg_try_advisory_xact_lock(hashtext(CONCAT(:providerId\\:\\:text, ':', LOWER(:login))))",
            nativeQuery = true)
    boolean tryAcquireLoginLock(@Param("login") String login, @Param("providerId") Long providerId);

    /** Serializes login checks, reassignment and upsert for a provider within the same transaction. */
    @Query(
            value = "SELECT pg_advisory_xact_lock(hashtext(CONCAT(:providerId\\:\\:text, ':', LOWER(:login))))",
            nativeQuery = true)
    void acquireLoginLock(@Param("login") String login, @Param("providerId") Long providerId);

    /**
     * Releases a login reassigned by current provider evidence. Requires {@link #acquireLoginLock};
     * saved signup metadata is not sufficient evidence to rename another actor.
     */
    @Modifying
    @Query(value = """
        UPDATE "user" SET login = 'RENAMED_' || id
        WHERE LOWER("user".login) = LOWER(:login)
          AND "user".native_id != :nativeId
          AND "user".provider_id = :providerId
        """, nativeQuery = true)
    void freeLoginConflicts(
            @Param("login") String login, @Param("nativeId") Long nativeId, @Param("providerId") Long providerId);

    /**
     * Upserts by immutable provider/native id. Acquire the login lock first and either reject a
     * conflicting login or release it using current provider evidence; never infer reassignment from saved metadata.
     * {@code type} is the account type the caller knows; {@code null} leaves a stored row's type as it is, and a new
     * row is a {@code USER}.
     */
    default void upsertUser(
            Long nativeId,
            Long providerId,
            String login,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String htmlUrl,
            @Nullable String type,
            @Nullable String email,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
        upsertUser(
                nativeId,
                providerId,
                login,
                name,
                avatarUrl,
                htmlUrl,
                type,
                type != null ? type : User.Type.USER.name(),
                email,
                createdAt,
                updatedAt);
    }

    /**
     * {@link #upsertUser(Long, Long, String, String, String, String, String, String, Instant, Instant)} for a caller
     * that has a better guess than {@code USER} for an account it does not know the type of: {@code insertType} applies
     * only to a row this statement creates, so a guess never overwrites a stored type. A {@code null} {@code type}
     * keeps a stored row's type in the same statement.
     */
    @Modifying
    @Query(value = """
        INSERT INTO "user" (native_id, provider_id, login, name, avatar_url, html_url, type, email, created_at, updated_at)
        VALUES (:nativeId, :providerId, :login, :name, :avatarUrl, :htmlUrl, COALESCE(CAST(:type AS VARCHAR), :insertType),
                :email, :createdAt, :updatedAt)
        ON CONFLICT (provider_id, native_id) DO UPDATE SET
            login = EXCLUDED.login,
            name = COALESCE(EXCLUDED.name, "user".name),
            avatar_url = EXCLUDED.avatar_url,
            html_url = EXCLUDED.html_url,
            type = COALESCE(CAST(:type AS VARCHAR), "user".type),
            email = COALESCE(EXCLUDED.email, "user".email),
            created_at = COALESCE(EXCLUDED.created_at, "user".created_at),
            updated_at = COALESCE(EXCLUDED.updated_at, "user".updated_at)
        """, nativeQuery = true)
    void upsertUser(
            @Param("nativeId") Long nativeId,
            @Param("providerId") Long providerId,
            @Param("login") String login,
            @Param("name") @Nullable String name,
            @Param("avatarUrl") @Nullable String avatarUrl,
            @Param("htmlUrl") @Nullable String htmlUrl,
            @Param("type") @Nullable String type,
            @Param("insertType") String insertType,
            @Param("email") @Nullable String email,
            @Param("createdAt") @Nullable Instant createdAt,
            @Param("updatedAt") @Nullable Instant updatedAt);

    /**
     * Backfills the email for a single user only when the current value is NULL.
     * <p>
     * Used by the GitLab commit→MR linker to enrich identities whose primary
     * email was not populated during the user sync (e.g. TUM accounts whose
     * {@code publicEmail} is hidden but whose commit identity reveals an
     * institutional address). Never overwrites an existing email.
     *
     * @return number of rows updated (0 when the user already has an email)
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE \"user\" SET email = :email WHERE id = :userId AND email IS NULL", nativeQuery = true)
    int backfillEmailIfNull(@Param("userId") Long userId, @Param("email") String email);
}
