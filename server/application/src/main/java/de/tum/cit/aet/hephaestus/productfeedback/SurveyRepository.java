package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SurveyRepository extends JpaRepository<Survey, UUID> {
    /**
     * Every unpaused survey whichever workspace it targets; the caller applies {@link Survey#isOpenFor}
     * so the schedule and the workspace predicate have one home.
     */
    List<Survey> findAllByActiveTrueOrderByStartsAtAscCreatedAtAsc();

    void deleteAllByWorkspaceId(Long workspaceId);
}
