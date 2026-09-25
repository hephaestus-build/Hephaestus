package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
        // Eligibility comes from the snapshot rather than a second query: the group standings and this detail
        // trend must agree on which practices count toward a group, and two derivations would drift.
        PracticeStandingService.StandingSnapshot snapshot = standingService.getStandingSnapshot(context.id());
        List<String> eligible = snapshot.eligiblePracticesByGroup().getOrDefault(groupSlug, List.of());
        Set<String> eligibleSet = Set.copyOf(eligible);
        Map<String, List<Observation>> evidence = snapshot.evidenceByPractice().entrySet().stream()
                .filter(entry -> eligibleSet.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        return trendService.detail(groupSlug, eligible, evidence);
    }
}
