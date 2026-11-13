package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepair.State;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Unresolved pseudonymous provider identities survive deletion of their former owners")
public interface ProviderResourceRepairRepository extends JpaRepository<ProviderResourceRepair, UUID> {
    @Query(
            "SELECT new de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairView(r.id, r.identityHash, r.kind, r.origin, r.resourceType, r.parentId, r.resourceId, r.ownershipHash, r.state, r.createdAt) FROM ProviderResourceRepair r")
    Page<ProviderResourceRepairView> readPage(Pageable pageable);

    @Query(value = "SELECT true FROM pg_advisory_xact_lock(hashtextextended(:identity, 101))", nativeQuery = true)
    boolean lockIdentity(String identity);

    boolean existsByKindAndOriginAndResourceTypeAndParentId(
            IntegrationKind kind, String origin, ResourceType resourceType, String parentId);

    Optional<ProviderResourceRepair> findByIdentityHash(String identityHash);

    List<ProviderResourceRepair> findFirst100ByStateNotAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            State excluded, Instant now);

    @Query(
            "SELECT r.kind AS kind, r.state AS state, count(r) AS amount FROM ProviderResourceRepair r GROUP BY r.kind, r.state")
    List<OpenCount> countOpenByKindAndState();

    interface OpenCount {
        IntegrationKind getKind();

        State getState();

        long getAmount();
    }
}
