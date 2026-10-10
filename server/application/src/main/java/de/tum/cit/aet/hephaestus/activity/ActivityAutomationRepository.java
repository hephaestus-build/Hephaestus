package de.tum.cit.aet.hephaestus.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityAutomationRepository extends JpaRepository<ActivityAutomation, ActivityAutomation.Id> {
    void deleteAllByWorkspace_Id(Long workspaceId);
}
