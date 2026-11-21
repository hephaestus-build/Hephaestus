package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.ProblemLineage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemLineageRepository extends JpaRepository<ProblemLineage, UUID> {
    /** The lineage that a result recorded against its previous comparable result. */
    List<ProblemLineage> findByWorkspaceIdAndResultId(long workspaceId, UUID resultId);
}
