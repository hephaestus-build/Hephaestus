package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The queue of emails that wait for a provider's answer. It spans workspaces, because commits are shared rows. */
@Repository
public interface CommitIdentityResolutionRepository extends JpaRepository<CommitIdentityResolution, Long> {
    interface PendingRepository {
        Long getRepositoryId();

        Long getProviderId();

        Long getNativeId();

        String getProviderType();

        String getServerUrl();

        String getNameWithOwner();
    }

    interface PendingEmail {
        Long getId();

        String getSha();

        @Nullable
        String getEmail();

        int getAttempts();
    }

    // A replacement credential can answer pending work without waiting for the old credential's deadline.
    String DUE = """
        (q.deferred_until IS NULL OR q.deferred_until <= clock_timestamp()
            OR NOT public.scm_credential_is_active(q.deferred_connection_id, q.deferred_credentials_generation))
        """;

    /** One entry per pending repository in queue order. A deferral can still be read through another scope. */
    @WorkspaceAgnostic("Instance-wide repair queue over shared commit rows")
    @Query(value = """
        WITH pending_repositories AS (
            SELECT DISTINCT ON (q.repository_id) q.repository_id, q.attempts, q.id
            FROM commit_identity_resolution q
            WHERE q.state = 'PENDING'
            ORDER BY q.repository_id, q.attempts, q.id
        )
        SELECT q.repository_id AS repositoryId, r.provider_id AS providerId, r.native_id AS nativeId,
               p.type AS providerType, p.server_url AS serverUrl,
               r.name_with_owner AS nameWithOwner
        FROM pending_repositories q
        JOIN repository r ON r.id = q.repository_id
        JOIN identity_provider p ON p.id = r.provider_id
        ORDER BY q.attempts, q.id
        """, nativeQuery = true)
    List<PendingRepository> findPendingRepositories();

    @WorkspaceAgnostic("Instance-wide repair queue over shared commit rows")
    @Query(value = """
        SELECT q.id AS id, gc.sha AS sha, q.attempts AS attempts,
               CASE q.source WHEN 'AUTHOR' THEN gc.author_email
                             WHEN 'COMMITTER' THEN gc.committer_email
                             ELSE cc.email END AS email
        FROM commit_identity_resolution q
        JOIN git_commit gc ON gc.id = q.commit_id
        LEFT JOIN commit_contributor cc ON cc.id = q.contributor_id
        WHERE q.state = 'PENDING' AND q.repository_id = :repositoryId
          AND (:includeDeferred OR
        """ + DUE + """
        )
        ORDER BY q.attempts, q.id
        LIMIT :limit
        """, nativeQuery = true)
    List<PendingEmail> findPendingEmails(
            @Param("repositoryId") long repositoryId,
            @Param("limit") int limit,
            @Param("includeDeferred") boolean includeDeferred);

    /** The emails of a repository that the provider said no account owns. An erased email has none left. */
    @WorkspaceAgnostic("Bound to one repository; the queue spans shared commit rows")
    @Query(value = """
        SELECT CASE q.source WHEN 'AUTHOR' THEN gc.author_email
                             WHEN 'COMMITTER' THEN gc.committer_email
                             ELSE cc.email END
        FROM commit_identity_resolution q
        JOIN git_commit gc ON gc.id = q.commit_id
        LEFT JOIN commit_contributor cc ON cc.id = q.contributor_id
        WHERE q.state = 'NO_ACCOUNT' AND q.repository_id = :repositoryId
        """, nativeQuery = true)
    List<@Nullable String> findNoAccountEmails(@Param("repositoryId") long repositoryId);

    @WorkspaceAgnostic("Instance-wide repair queue over shared commit rows")
    @Query(value = "SELECT count(*) FROM commit_identity_resolution WHERE state = 'PENDING'", nativeQuery = true)
    long countPending();

    /** Provider corrections lock the queue before changing commit links, as stored-email enrichment does. */
    @WorkspaceAgnostic("Keyed lock of one row of the shared repair queue")
    @Query(
            value = "SELECT id FROM commit_identity_resolution WHERE id = :id AND state = 'PENDING' FOR UPDATE",
            nativeQuery = true)
    Optional<Long> lockById(@Param("id") long id);

    @WorkspaceAgnostic("Keyed update of one row of the instance-wide repair queue")
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE commit_identity_resolution
        SET state = :state, attempts = attempts + 1, finished_at = :at
        WHERE id = :id
        """, nativeQuery = true)
    int finish(@Param("id") long id, @Param("state") String state, @Param("at") Instant at);

    @WorkspaceAgnostic("Keyed update of one row of the instance-wide repair queue")
    @Modifying
    @Transactional
    @Query(value = "UPDATE commit_identity_resolution SET attempts = attempts + 1 WHERE id = :id", nativeQuery = true)
    int retryLater(@Param("id") long id);

    /** The provider deferred these emails: they wait until its deadline, on every server, and spend no attempt. */
    @WorkspaceAgnostic("Keyed update of rows of the instance-wide repair queue")
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE commit_identity_resolution
        SET deferred_until = :until, deferred_connection_id = :connectionId,
            deferred_credentials_generation = :generation
        WHERE id IN (:ids) AND state = 'PENDING'
        """, nativeQuery = true)
    int defer(
            @Param("ids") List<Long> ids,
            @Param("connectionId") long connectionId,
            @Param("generation") long generation,
            @Param("until") Instant until);
}
