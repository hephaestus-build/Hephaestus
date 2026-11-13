package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConnectionProviderRepairRepository extends JpaRepository<ConnectionProviderRepair, UUID> {
    @Query(
            "SELECT new de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairView(r.id, r.identityHash, r.kind, r.origin, r.resourceType, r.parentId, r.resourceId, r.ownershipHash, r.state, r.createdAt, r.observedTeamId) FROM ConnectionProviderRepair ref JOIN ref.repair r WHERE ref.workspaceId = :workspaceId AND ref.connection.id = :connectionId")
    Page<ProviderResourceRepairView> readForConnection(long workspaceId, long connectionId, Pageable pageable);

    record OwnerRow(
            UUID repairId,
            long connectionId,
            long workspaceId,
            String workspaceSlug,
            @Nullable String instanceKey,
            ConnectionConfig config,
            IntegrationState state) {}

    @WorkspaceAgnostic("Instance-admin read batches only the ownership links of the current repair page")
    @Query(
            "SELECT new de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository$OwnerRow(ref.repair.id, c.id, ref.workspaceId, c.workspace.workspaceSlug, c.instanceKey, c.config, c.state) FROM ConnectionProviderRepair ref JOIN ref.connection c WHERE ref.repair.id IN :repairIds")
    List<OwnerRow> readOwners(Collection<UUID> repairIds);

    @WorkspaceAgnostic(
            "Instance-admin read batches active replacement connections only in the current page's owning workspaces")
    @Query(
            "SELECT new de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository$OwnerRow(ref.repair.id, c.id, ref.workspaceId, c.workspace.workspaceSlug, c.instanceKey, c.config, c.state) FROM ConnectionProviderRepair ref JOIN Connection c ON c.workspace.id = ref.workspaceId AND c.kind = ref.repair.kind WHERE ref.repair.id IN :repairIds AND c.state = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState.ACTIVE")
    List<OwnerRow> readReplacementCandidates(Collection<UUID> repairIds);

    @Query(
            "SELECT new de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository$OwnerRow(ref.repair.id, c.id, ref.workspaceId, c.workspace.workspaceSlug, c.instanceKey, c.config, c.state) FROM ConnectionProviderRepair ref JOIN Connection c ON c.workspace.id = ref.workspaceId AND c.kind = ref.repair.kind WHERE ref.workspaceId = :workspaceId AND c.workspace.id = :workspaceId AND ref.connection.id = :connectionId AND ref.repair.id IN :repairIds AND c.state = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState.ACTIVE")
    List<OwnerRow> readConnectionReplacementCandidates(long workspaceId, long connectionId, Collection<UUID> repairIds);

    List<ConnectionProviderRepair> findByWorkspaceIdAndConnection_Id(long workspaceId, long connectionId);

    Optional<ConnectionProviderRepair> findByWorkspaceIdAndConnection_IdAndRepair_Id(
            long workspaceId, long connectionId, UUID repairId);

    @WorkspaceAgnostic("Dispatch resolves credentials only through surviving scoped ownership references")
    @Query("SELECT ref FROM ConnectionProviderRepair ref WHERE ref.repair.id = :repairId ORDER BY ref.id")
    List<ConnectionProviderRepair> findByRepair_Id(UUID repairId);
}
