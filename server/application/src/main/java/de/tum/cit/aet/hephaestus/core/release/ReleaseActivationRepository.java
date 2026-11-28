package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.RunningReleaseDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The time on these rows is the database clock, so that the servers of one rollout agree on it. */
@WorkspaceAgnostic("Release history is instance-wide; it names no tenant")
interface ReleaseActivationRepository extends JpaRepository<ReleaseActivation, Long> {
    /**
     * Records the rollout once: every server of it reports it, and only the first report writes a row. The key assumes
     * that Kubernetes keeps the revision sequence of a Deployment. If every ReplicaSet of a Deployment is deleted while
     * the Deployment stays, the controller can number its next rollout 1 again, and this rollout then adds no row.
     */
    @Modifying
    @Query(value = """
            INSERT INTO release_activation
                (environment, deployment_uid, revision, version, channel, commit_sha, image, activated_at)
            VALUES (:#{#release.environment()}, :#{#rollout.deploymentUid()}, :#{#rollout.revision()},
                    :#{#release.version()}, :#{#release.channel().name()}, :#{#release.commit()},
                    :#{#release.image()}, now())
            ON CONFLICT (environment, deployment_uid, revision) DO NOTHING
            """, nativeQuery = true)
    void recordRollout(@Param("release") RunningReleaseDTO release, @Param("rollout") Rollout rollout);

    /** Records one server start that has no rollout to name, and returns its row. */
    @Query(value = """
            INSERT INTO release_activation (environment, version, channel, commit_sha, image, activated_at)
            VALUES (:#{#release.environment()}, :#{#release.version()}, :#{#release.channel().name()},
                    :#{#release.commit()}, :#{#release.image()}, now())
            RETURNING id
            """, nativeQuery = true)
    long recordStart(@Param("release") RunningReleaseDTO release);

    /**
     * The ten newest entries, newest first. A rollout sorts by the first time that it or a later revision of its
     * Deployment was recorded. A rollout recorded late, after its first reports failed, thus keeps its place below the
     * later revisions. A server start sorts by its own time, but never above the newest rollout: a start without a
     * revision cannot tell whether that rollout still runs. A start between two rollouts, when the server could not
     * reach the Kubernetes API, stays between them. At the same time a rollout comes first, then the higher revision.
     */
    @Query(value = """
            WITH keyed AS (
                SELECT a.*,
                       (SELECT min(later.activated_at) FROM release_activation later
                        WHERE later.environment = a.environment AND later.deployment_uid = a.deployment_uid
                          AND later.revision >= a.revision) AS rollout_at
                FROM release_activation a
                WHERE a.environment = :environment
            ), newest AS (
                SELECT max(rollout_at) AS at FROM keyed
            )
            SELECT k.id, k.environment, k.deployment_uid, k.revision, k.version, k.channel, k.commit_sha, k.image,
                   k.activated_at
            FROM keyed k CROSS JOIN newest n
            ORDER BY COALESCE(k.rollout_at, LEAST(k.activated_at, COALESCE(n.at, k.activated_at))) DESC,
                     (k.deployment_uid IS NULL),
                     k.revision DESC NULLS LAST,
                     k.id DESC
            LIMIT 10
            """, nativeQuery = true)
    List<ReleaseActivation> findRecent(@Param("environment") String environment);

    /**
     * Keeps the most recently recorded entries of the environment, which the history shows ten of, and the highest
     * revision of each Deployment. Server starts can outnumber the rollouts, and a deleted newest revision would be
     * recorded again with a new time when its servers next report.
     */
    @Modifying
    @Query(value = """
            DELETE FROM release_activation
            WHERE environment = :environment
              AND id NOT IN (
                SELECT id FROM release_activation WHERE environment = :environment
                ORDER BY activated_at DESC, id DESC LIMIT :keep)
              AND id NOT IN (
                SELECT DISTINCT ON (deployment_uid) id FROM release_activation
                WHERE environment = :environment AND deployment_uid IS NOT NULL
                ORDER BY deployment_uid, revision DESC)
            """, nativeQuery = true)
    void keepNewest(@Param("environment") String environment, @Param("keep") int keep);
}
