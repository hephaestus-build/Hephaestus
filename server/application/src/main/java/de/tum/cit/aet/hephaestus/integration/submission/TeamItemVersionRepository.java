package de.tum.cit.aet.hephaestus.integration.submission;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TeamItemVersionRepository extends Repository<TeamItemVersion, UUID> {
    /**
     * Ends up to {@code limit} versions whose expiry date passed, by the database clock. It waits for no writer: a
     * version that another transaction holds is skipped, and a later call ends it.
     */
    @WorkspaceAgnostic("Expiry spans workspaces")
    @Modifying
    @Transactional(timeout = 30)
    @Query(value = """
                    WITH due AS MATERIALIZED (
                        SELECT id FROM team_item_version
                        WHERE state = 'AVAILABLE' AND expires_at <= now()
                        ORDER BY id LIMIT :limit FOR NO KEY UPDATE SKIP LOCKED
                    )
                    UPDATE team_item_version v SET state = 'EXPIRED', title = NULL FROM due WHERE v.id = due.id
                    """, nativeQuery = true)
    int expireDue(@Param("limit") int limit);

    /** Whether a version is still available although its expiry date passed, by the database clock. */
    @WorkspaceAgnostic("Expiry spans workspaces")
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM team_item_version"
                    + " WHERE state = 'AVAILABLE' AND expires_at <= now())",
            nativeQuery = true)
    boolean existsDue();

    /** Withdraws the version if it is still available. */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE team_item_version SET state = 'WITHDRAWN', title = NULL"
                    + " WHERE id = :versionId AND workspace_id = :workspaceId AND state = 'AVAILABLE'",
            nativeQuery = true)
    int withdraw(@Param("workspaceId") long workspaceId, @Param("versionId") UUID versionId);

    /** Withdraws every available version that the account submitted in the workspace. */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE team_item_version SET state = 'WITHDRAWN', title = NULL"
                    + " WHERE workspace_id = :workspaceId AND submitted_by = :accountId AND state = 'AVAILABLE'",
            nativeQuery = true)
    int withdrawSubmittedBy(@Param("workspaceId") long workspaceId, @Param("accountId") long accountId);

    /** Ends every version of the account, whatever its state, and removes the account and the title from it. */
    @WorkspaceAgnostic("Account erasure spans workspaces")
    @Modifying
    @Query(
            value = "UPDATE team_item_version SET state = 'ERASED', submitted_by = NULL, title = NULL"
                    + " WHERE submitted_by = :accountId",
            nativeQuery = true)
    void eraseSubmitter(@Param("accountId") long accountId);
}
