package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The mentor's rows are account-owned under row-level security; their SQL names the account or runs as the owner. */
@WorkspaceAgnostic("The mentor's rows are account-owned; row-level security scopes them to the bound account")
public interface MentorSourceRepository extends Repository<MentorSource, UUID> {
    /** Every account's rows of the workspace, locking the sources first as intake does. Returns the sources erased. */
    @Query(value = "SELECT public.mentor_erase_workspace(:workspaceId)", nativeQuery = true)
    int eraseWorkspace(@Param("workspaceId") long workspaceId);
}
