package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeGroupTrendQueryService {

    private final PracticeGroupService practiceGroupService;
    private final PracticeStandingService standingService;
    private final PracticeTrendService trendService;

    @Transactional(readOnly = true)
    public PracticeGroupTrendDTO get(WorkspaceContext context, String groupSlug) {
        practiceGroupService.getGroup(context, groupSlug);
        return get(groupSlug, standingService.getStandingSnapshot(context.id()));
    }

    @Transactional(readOnly = true)
    public PracticeGroupTrendDTO get(WorkspaceContext context, Long developerId, String groupSlug) {
        practiceGroupService.getGroup(context, groupSlug);
        return get(groupSlug, standingService.getStandingSnapshot(context.id(), developerId));
    }

    /**
     * Eligibility and the trends come from the snapshot rather than a second derivation: the group standings
     * and this detail trend must agree on which practices count toward a group and on what each one's trend
     * is, and two derivations would drift.
     */
    private PracticeGroupTrendDTO get(String groupSlug, StandingSnapshot snapshot) {
        List<String> eligible = snapshot.eligiblePracticesByGroup().getOrDefault(groupSlug, List.of());
        Set<String> eligibleSet = Set.copyOf(eligible);
        List<PracticeTrend> trends = snapshot.practices().values().stream()
                .filter(standing -> eligibleSet.contains(standing.dto().slug()))
                .map(PracticeStanding::trend)
                .toList();
        return trendService.detail(groupSlug, eligible, trends);
    }
}
