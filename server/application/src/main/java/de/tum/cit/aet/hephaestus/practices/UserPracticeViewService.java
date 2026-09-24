package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.UserPracticeViewController.UserPracticeSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReviewedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPracticeViewService {
    private final PracticeGroupService groups;
    private final PracticeService practices;
    private final CatalogOriginPresenter presenter;
    private final PracticeStandingService standings;
    private final PracticeGroupStandingService groupStandings;

    public UserPracticeSummaryDTO summary(WorkspaceContext workspace, Long userId) {
        var visibleGroups = groups.listGroups(workspace, true);
        var snapshot = standings.getStandingSnapshot(workspace.id(), userId);
        return new UserPracticeSummaryDTO(
                presenter.presentGroups(workspace.id(), visibleGroups),
                groupStandings.summarize(visibleGroups, snapshot),
                snapshot.practices(),
                practices.listReviewedPractices(workspace).stream()
                        .map(ReviewedPracticeDTO::from)
                        .toList());
    }
}
