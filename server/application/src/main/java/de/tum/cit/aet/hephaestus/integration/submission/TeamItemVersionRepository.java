package de.tum.cit.aet.hephaestus.integration.submission;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TeamItemVersionRepository extends Repository<TeamItemVersion, UUID> {
    /** Ends every version of the account, whatever its state, and removes the account and the title from it. */
    @WorkspaceAgnostic("Account erasure spans workspaces")
    @Modifying
    @Query(
            value = "UPDATE team_item_version SET state = 'ERASED', submitted_by = NULL, title = NULL"
                    + " WHERE submitted_by = :accountId",
            nativeQuery = true)
    void eraseSubmitter(@Param("accountId") long accountId);
}
