package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("A pairing request precedes any workspace: it holds no workspace, team or content (ADR 0166)")
public interface HostPairingRepository extends JpaRepository<HostPairing, UUID> {

    /** Refuses the account's approved requests, so no grant can be redeemed after its sessions end. */
    @Modifying
    @Query("DELETE FROM HostPairing p WHERE p.approvedAccountId = :accountId")
    int deleteApprovedBy(@Param("accountId") long accountId);
}
