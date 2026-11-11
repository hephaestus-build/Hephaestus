package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

@WorkspaceAgnostic("Native identity restrictions survive account erasure")
public interface IdentityRestrictionRepository extends JpaRepository<IdentityRestriction, UUID> {}
