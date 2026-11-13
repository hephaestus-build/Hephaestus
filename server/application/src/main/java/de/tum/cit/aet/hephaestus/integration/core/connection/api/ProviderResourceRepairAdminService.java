package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
public class ProviderResourceRepairAdminService {
    private final ProviderResourceRepairRepository repairs;
    private final ConnectionProviderRepairRepository references;
    private final ProviderResourceRepairs coordination;
    private final ConfigAuditPort audit;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderResourceRepairAdminService(
            ProviderResourceRepairRepository repairs,
            ConnectionProviderRepairRepository references,
            ProviderResourceRepairs coordination,
            ConfigAuditPort audit) {
        this.repairs = repairs;
        this.references = references;
        this.coordination = coordination;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public OffsetPageDTO<ProviderResourceRepairDTO> list(long workspaceId, long connectionId, int page, int size) {
        var repairsPage =
                references.readForConnection(workspaceId, connectionId, PageRequest.of(page, size, Sort.by("id")));
        var replacements = references.readConnectionReplacementCandidates(
                workspaceId,
                connectionId,
                repairsPage.stream().map(row -> row.id()).toList());
        return OffsetPageDTO.from(repairsPage.map(row -> ProviderResourceRepairDTO.from(
                row,
                List.of(),
                replacements.stream()
                        .filter(owner -> owner.repairId().equals(row.id()))
                        .anyMatch(owner -> coordination.replacementInUse(owner, row.resource())))));
    }

    @Transactional(readOnly = true)
    @RequireInstanceAdmin
    @WorkspaceAgnostic("Instance admins can see every unresolved fence, including orphan identities")
    public OffsetPageDTO<ProviderResourceRepairDTO> listInstance(int page, int size) {
        var repairsPage = repairs.readPage(PageRequest.of(page, size, Sort.by("createdAt", "id")));
        var owners =
                references.readOwners(repairsPage.stream().map(row -> row.id()).toList());
        var replacements = references.readReplacementCandidates(
                repairsPage.stream().map(row -> row.id()).toList());
        return OffsetPageDTO.from(repairsPage.map(row -> {
            var holding = owners.stream()
                    .filter(owner -> owner.repairId().equals(row.id()))
                    .toList();
            return ProviderResourceRepairDTO.from(
                    row,
                    holding.stream()
                            .map(owner ->
                                    new ProviderResourceRepairDTO.OwnerDTO(owner.connectionId(), owner.workspaceSlug()))
                            .toList(),
                    replacements.stream()
                            .filter(owner -> owner.repairId().equals(row.id()))
                            .anyMatch(owner -> coordination.replacementInUse(owner, row.resource())));
        }));
    }

    @Transactional
    @RequireInstanceAdmin
    public void release(
            long workspaceId, long connectionId, UUID repairId, ReleaseProviderResourceRepairRequestDTO confirmation) {
        var reference = references
                .findByWorkspaceIdAndConnection_IdAndRepair_Id(workspaceId, connectionId, repairId)
                .orElseThrow(() -> new NoSuchElementException("Provider repair not found"));
        coordination.coordinate(List.of(reference.getRepair().resource()));
        Objects.requireNonNull(entityManager).clear();
        reference = references
                .findByWorkspaceIdAndConnection_IdAndRepair_Id(workspaceId, connectionId, repairId)
                .orElseThrow(() -> new NoSuchElementException("Provider repair not found"));
        release(reference.getRepair(), confirmation);
    }

    @Transactional
    @RequireInstanceAdmin
    @WorkspaceAgnostic("An instance admin releases an orphan by its exact pseudonymous provider identity")
    public void releaseOrphan(UUID repairId, ReleaseProviderResourceRepairRequestDTO confirmation) {
        ProviderResourceRepair repair = repairs.findById(repairId).orElseThrow();
        coordination.coordinate(List.of(repair.resource()));
        Objects.requireNonNull(entityManager).clear();
        repair = repairs.findById(repairId).orElseThrow();
        if (!references.findByRepair_Id(repairId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Release this fence from the connection's administration page.");
        }
        release(repair, confirmation);
    }

    private void release(ProviderResourceRepair repair, ReleaseProviderResourceRepairRequestDTO confirmation) {
        if (!confirmation.confirmedGone() || !repair.getIdentityHash().equals(confirmation.identityHash())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The provider identity or its removal confirmation does not match.");
        }
        if (repair.getState() == ProviderResourceRepair.State.REGISTERING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The provider registration is still in progress. Wait for its result before confirming removal.");
        }
        var resource = repair.resource();
        if (resource.type() == ResourceType.BOT_USER
                && repairs.existsByKindAndOriginAndResourceTypeAndParentId(
                        resource.kind(),
                        resource.origin(),
                        ResourceType.BOT_TOKEN,
                        resource.parentId() + "/" + resource.resourceId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Confirm and release the old bot-token removal before releasing its bot-user fence.");
        }
        CurrentAccount.requireId();
        coordination.clearCompletedHook(repair.getId(), resource);
        // The audit keeps confirmation and attribution, never a copy of the retained provider identifier.
        var owners = references.findByRepair_Id(repair.getId());
        if (owners.isEmpty()) {
            audit.record(new ConfigAuditEntry(
                    ConfigAuditEntityType.PROVIDER_RESOURCE_RELEASE,
                    repair.getId().toString(),
                    null,
                    null,
                    new ReleaseSnapshot(repair.getId(), null, true)));
        } else {
            var auditedWorkspaces = new HashSet<Long>();
            for (var owner : owners) {
                if (auditedWorkspaces.add(owner.getWorkspaceId())) {
                    audit.record(new ConfigAuditEntry(
                            ConfigAuditEntityType.PROVIDER_RESOURCE_RELEASE,
                            repair.getId().toString(),
                            owner.getWorkspaceId(),
                            null,
                            new ReleaseSnapshot(
                                    repair.getId(), owner.getConnection().getId(), true)));
                }
            }
        }
        references.deleteAll(owners);
        repairs.delete(repair);
    }

    public record ReleaseSnapshot(UUID repairId, @Nullable Long connectionId, boolean confirmedGone)
            implements ConfigAuditSnapshot {}
}
