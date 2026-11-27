package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("The owner row policy scopes the personal goal to its account")
interface PersonalGoalRepository extends JpaRepository<PersonalGoal, Long> {
    @Query(
            value =
                    "SELECT count(pg_advisory_xact_lock(hashtextextended('personal-goal:' || CAST(:accountId AS text), 0)))",
            nativeQuery = true)
    long lockOwner(@Param("accountId") long accountId);
}
