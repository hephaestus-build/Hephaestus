package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceTileDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.ReviewedWorkKey;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes Practices across the workspace from the standings that already exist: every eligible developer's
 * snapshot read off one scan of the workspace, rolled up to practice groups by the same classifier the practice
 * profile uses, then counted per group under {@link CohortPrivacyPolicy}.
 */
@Service
@RequiredArgsConstructor
public class PracticesAcrossWorkspaceService {

    private static final StandingSnapshot NOTHING_READ = new StandingSnapshot(Map.of(), Map.of());

    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupService practiceGroupService;
    private final WorkspaceMembershipService membershipService;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceDTO read(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        Instant until = clock.instant();
        Instant since = until.minus(window.days(), ChronoUnit.DAYS);

        // Hidden members are left out of every workspace total, so they are left out of these counts too.
        Set<Long> eligible = new LinkedHashSet<>(membershipService.practiceReviewEligibleUserIds(workspaceId));
        eligible.removeAll(membershipService.getHiddenMemberIds(workspaceId));
        @Nullable Long reader = currentDeveloperLookup.currentDeveloperId().orElse(null);
        Set<Long> read = new LinkedHashSet<>(eligible);
        if (reader != null) {
            read.add(reader);
        }
        Map<Long, StandingSnapshot> snapshots =
                practiceStandingService.getWorkspaceStandingSnapshots(workspaceId, read, since, until);

        List<Long> observed = eligible.stream()
                .filter(developer -> hasStanding(snapshots.getOrDefault(developer, NOTHING_READ)))
                .toList();
        boolean readerCounted = reader != null && observed.contains(reader);
        int others = observed.size() - (readerCounted ? 1 : 0);
        StandingSnapshot yours = reader == null ? NOTHING_READ : snapshots.getOrDefault(reader, NOTHING_READ);

        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings = new HashMap<>();
        for (Long developer : observed) {
            groupStandings.put(developer, groupStandings(groups, snapshots.getOrDefault(developer, NOTHING_READ)));
        }
        Map<String, PracticeGroupStandingDTO.Standing> yourGroups = groupStandings(groups, yours);

        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (PracticeGroup group : groups) {
            List<PracticeGroupStandingDTO.Standing> othersInGroup = observed.stream()
                    .filter(developer -> !developer.equals(reader))
                    .map(developer -> Objects.requireNonNull(
                            groupStandings.getOrDefault(developer, Map.of()).get(group.getSlug())))
                    .toList();
            PracticeGroupStandingDTO.Standing yourStanding = Objects.requireNonNull(yourGroups.get(group.getSlug()));
            Split split = CohortPrivacyPolicy.split(othersInGroup, readerCounted ? yourStanding : null);
            rows.add(new WorkspaceGroupSplitDTO(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    yourStanding,
                    split.shape(),
                    split.needsAttention(),
                    split.mixedFeedback(),
                    split.goingWell(),
                    split.hasStanding(),
                    split.noneYet()));
        }

        List<StandingSnapshot> observedSnapshots = observed.stream()
                .map(developer -> snapshots.getOrDefault(developer, NOTHING_READ))
                .toList();
        return new PracticesAcrossWorkspaceDTO(
                window,
                since,
                until,
                CohortPrivacyPolicy.MINIMUM_OTHERS,
                eligible.size(),
                observed.size(),
                readerCounted,
                yours.practices().size(),
                tile(yours, observedSnapshots, others, PracticesAcrossWorkspaceService::reviewedWork),
                tile(
                        yours,
                        observedSnapshots,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.STRENGTH)),
                tile(
                        yours,
                        observedSnapshots,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.DEVELOPING)),
                rows);
    }

    private Map<String, PracticeGroupStandingDTO.Standing> groupStandings(
            List<PracticeGroup> groups, StandingSnapshot snapshot) {
        return practiceGroupStandingService.summarize(groups, snapshot).stream()
                .collect(Collectors.toMap(
                        PracticeGroupStandingDTO::groupSlug, PracticeGroupStandingDTO::standing, (a, b) -> a));
    }

    private static WorkspaceTileDTO tile(
            StandingSnapshot yours,
            List<StandingSnapshot> observed,
            int others,
            ToIntFunction<StandingSnapshot> figure) {
        MiddleHalf middle = CohortPrivacyPolicy.middleHalf(
                observed.stream().map(figure::applyAsInt).toList(), others);
        return new WorkspaceTileDTO(
                figure.applyAsInt(yours), middle == null ? null : middle.low(), middle == null ? null : middle.high());
    }

    private static boolean hasStanding(StandingSnapshot snapshot) {
        return snapshot.practices().values().stream()
                .anyMatch(
                        practice -> PracticeStandingDTO.isVerdict(practice.dto().standing()));
    }

    /** Distinct pieces of work any practice's latest run looked at, verdict or not: the work that was reviewed. */
    private static int reviewedWork(StandingSnapshot snapshot) {
        return (int) snapshot.practices().values().stream()
                .flatMap(practice -> practice.evidence().stream())
                .map(ReviewedWorkKey::of)
                .distinct()
                .count();
    }

    private static int practicesAt(StandingSnapshot snapshot, PracticeStandingDTO.Standing standing) {
        return (int) snapshot.practices().values().stream()
                .filter(practice -> practice.dto().standing() == standing)
                .count();
    }
}
