package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepositoryObservationQueries extends org.springframework.data.repository.Repository<Repository, Long> {

    interface Snapshot {
        @Nullable
        Long getConnectionId();

        long getCredentialsGeneration();

        long getLifecycleGeneration();

        @Nullable
        Long getMonitorId();

        @Nullable
        Long getNativeId();

        long getProofGeneration();

        Instant getObservedAt();
    }

    @Query(value = "SELECT statement_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Query(value = """
            SELECT s.connection_id AS "connectionId", s.credentials_generation AS "credentialsGeneration",
                s.lifecycle_generation AS "lifecycleGeneration", s.monitor_id AS "monitorId",
                s.native_id AS "nativeId", s.proof_generation AS "proofGeneration", s.observed_at AS "observedAt"
            FROM public.capture_repository_observations(:workspaceId, :providerId, CAST(:monitor AS bigint)) AS s
            """, nativeQuery = true)
    List<Snapshot> capture(
            @Param("workspaceId") long workspace,
            @Param("providerId") long provider,
            @Param("monitor") @Nullable Long monitor);

    @Query(value = """
            SELECT public.store_repository_observation(
                :#{#read.scopeId()}, CAST(:#{#read.credential()?.connectionId()} AS bigint),
                CAST(:#{#read.credential()?.generation()} AS bigint),
                CAST(:#{#read.credential()?.lifecycleGeneration()} AS bigint),
                :#{#read.providerId()}, :#{#read.repositoryNativeId()}, CAST(:#{#read.syncTargetId()} AS bigint),
                :#{#read.generation()}, CAST(:#{#read.requestedAt()} AS timestamptz), :observation, CAST(:visibility AS text),
                CAST(:patch AS jsonb))
            """, nativeQuery = true)
    long applyObservation(
            @Param("read") ReadersRead read,
            @Param("observation") String observation,
            @Param("visibility") @Nullable String visibility,
            @Param("patch") @Nullable String patch);

    // The mirror trigger invalidates the matching public proofs in the same transaction.
    @WorkspaceAgnostic(
            "A verified provider-wide loss narrows one shared mirror identified by provider and repository id")
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE public.repository AS r
            SET visibility = CASE WHEN r.visibility = 'PUBLIC' THEN 'UNKNOWN' ELSE r.visibility END,
                metadata_observed_at = clock_timestamp()
            WHERE r.id = :repositoryId AND r.provider_id = :providerId
              AND (r.metadata_observed_at IS NULL OR r.metadata_observed_at < :checkedAt)
            """, nativeQuery = true)
    int restrictPublicAccess(
            @Param("providerId") long providerId,
            @Param("repositoryId") long repositoryId,
            @Param("checkedAt") Instant checkedAt);
}
