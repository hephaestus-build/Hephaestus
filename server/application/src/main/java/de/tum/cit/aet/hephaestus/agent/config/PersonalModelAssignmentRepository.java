package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import org.springframework.data.jpa.repository.JpaRepository;

/** The instance's personal model assignment, one row for each tier. */
@WorkspaceAgnostic("Personal conversations have no workspace; the instance assigns one model for each tier")
interface PersonalModelAssignmentRepository extends JpaRepository<PersonalModelAssignment, DataHandlingTier> {}
