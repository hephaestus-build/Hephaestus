package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Row;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceTilesDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceTileDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.WorkspaceStandings;
import de.tum.cit.aet.hephaestus.practices.observation.ReviewedWorkKey;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes Practices across the workspace from one scan of the workspace's observations per read, classified by the
 * same standing and group standing rules the practice profile uses, then counted under {@link CohortPrivacyPolicy}.
 * The splits and the open feedback read the current standing, so the reader's marker is the standing their profile
 * shows; only the tiles read a window. Aggregating in SQL instead would put a second copy of the standing rules
 * beside the profile's, free to disagree with it.
 */
@Service
@RequiredArgsConstructor
public class PracticesAcrossWorkspaceService {

    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupService practiceGroupService;
    private final WorkspaceMembershipService membershipService;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final InAppFeedbackService inAppFeedbackService;
    private final Clock clock;

    /** The splits by the current standing and the open feedback, whatever window the tiles read. */
    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceDTO read(WorkspaceContext context) {
        Long workspaceId = context.id();
        Members members = members(workspaceId);
        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        WorkspaceStandings standings =
                practiceStandingService.getCurrentWorkspaceStandingSnapshots(workspaceId, members.read());
        Cohort current = cohort(groups, members, standings.byDeveloper());

        StandingSnapshot yours = current.snapshotOf(members.reader());
        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (PracticeGroup group : groups) {
            // The practices every reader sees, whatever their own evidence says.
            List<Practice> practices = standings.eligiblePracticesByGroup().getOrDefault(group.getSlug(), List.of());
            Function<Long, Row> rowOf = developer -> new Row(
                    current.groupStandingOf(developer, group.getSlug()).asPracticeStanding(),
                    practices.stream()
                            .map(practice -> standingIn(current.snapshotOf(developer), practice.getSlug()))
                            .toList());
            GroupRelease release = CohortPrivacyPolicy.group(
                    current.withAStanding().stream().map(rowOf).toList(), practices.size());
            List<WorkspacePracticeSplitDTO> practiceSplits = new ArrayList<>();
            for (int index = 0; index < practices.size(); index++) {
                Practice practice = practices.get(index);
                Split split = release.practices().get(index);
                practiceSplits.add(new WorkspacePracticeSplitDTO(
                        practice.getSlug(),
                        practice.getName(),
                        CohortPrivacyPolicy.marker(
                                split, current.readerCounted(), standingIn(yours, practice.getSlug())),
                        WorkspaceSplitDTO.from(split)));
            }
            rows.add(new WorkspaceGroupSplitDTO(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    CohortPrivacyPolicy.marker(
                            release.group(),
                            current.readerCounted(),
                            current.groupStandingOf(members.reader(), group.getSlug())),
                    WorkspaceSplitDTO.from(release.group()),
                    practiceSplits));
        }

        // Open now for everyone, whatever window the tiles read, so the tile never sets a moment beside a span.
        Map<Long, Integer> openFeedback = inAppFeedbackService.countOpen(workspaceId, members.read());
        @Nullable Long reader = members.reader();
        MiddleHalf openMiddle = CohortPrivacyPolicy.middleHalf(
                members.eligible().stream()
                        .map(developer -> openFeedback.getOrDefault(developer, 0))
                        .toList(),
                members.eligible().size() - (members.readerEligible() ? 1 : 0));
        return new PracticesAcrossWorkspaceDTO(
                CohortPrivacyPolicy.MINIMUM_OTHERS,
                CohortPrivacyPolicy.totalWithAStanding(current.others(), current.readerCounted()),
                current.readerCounted(),
                WorkspaceTileDTO.of(reader == null ? 0 : openFeedback.getOrDefault(reader, 0), openMiddle),
                rows);
    }

    /** The reader's figures over the window beside the middle half of the developers with a standing in it. */
    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceTilesDTO readTiles(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        Members members = members(workspaceId);
        Cohort inWindow = cohort(
                practiceGroupService.listGroups(context, true),
                members,
                practiceStandingService
                        .getWorkspaceStandingSnapshots(workspaceId, members.read(), window.since(clock.instant()))
                        .byDeveloper());
        StandingSnapshot yours = inWindow.snapshotOf(members.reader());
        List<StandingSnapshot> withAStanding =
                inWindow.withAStanding().stream().map(inWindow::snapshotOf).toList();
        int others = inWindow.others();
        return new PracticesAcrossWorkspaceTilesDTO(
                window,
                CohortPrivacyPolicy.MINIMUM_OTHERS_FOR_MIDDLE_HALF,
                CohortPrivacyPolicy.totalWithAStanding(others, inWindow.readerCounted()),
                yours.practices().size(),
                tile(yours, withAStanding, others, PracticesAcrossWorkspaceService::reviewedWork),
                tile(
                        yours,
                        withAStanding,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.STRENGTH)),
                tile(
                        yours,
                        withAStanding,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.DEVELOPING)));
    }

    /**
     * The developers the page counts, without the hidden members every workspace total leaves out, and the reader,
     * who need not be one of them.
     */
    private record Members(Set<Long> eligible, @Nullable Long reader) {

        Set<Long> read() {
            Set<Long> read = new LinkedHashSet<>(eligible);
            if (reader != null) {
                read.add(reader);
            }
            return read;
        }

        boolean readerEligible() {
            return reader != null && eligible.contains(reader);
        }
    }

    private Members members(Long workspaceId) {
        Set<Long> eligible = new LinkedHashSet<>(membershipService.practiceReviewEligibleUserIds(workspaceId));
        eligible.removeAll(membershipService.getHiddenMemberIds(workspaceId));
        return new Members(eligible, currentDeveloperLookup.currentDeveloperId().orElse(null));
    }

    /**
     * The developers read on one basis, the current standing or a window: each one's snapshot and group standings,
     * and the eligible developers with a standing, those with a verdict in a group the page shows.
     */
    private record Cohort(
            Map<Long, StandingSnapshot> snapshots,
            Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings,
            List<Long> withAStanding,
            boolean readerCounted) {

        StandingSnapshot snapshotOf(@Nullable Long developer) {
            return developer == null
                    ? StandingSnapshot.EMPTY
                    : snapshots.getOrDefault(developer, StandingSnapshot.EMPTY);
        }

        /** The developer's standing in the group; not observed for a caller nothing was read for. */
        PracticeGroupStandingDTO.Standing groupStandingOf(@Nullable Long developer, String groupSlug) {
            return developer == null
                    ? PracticeGroupStandingDTO.Standing.NOT_OBSERVED
                    : groupStandings
                            .getOrDefault(developer, Map.of())
                            .getOrDefault(groupSlug, PracticeGroupStandingDTO.Standing.NOT_OBSERVED);
        }

        /** The developers with a standing other than the reader. */
        int others() {
            return withAStanding.size() - (readerCounted ? 1 : 0);
        }
    }

    private Cohort cohort(List<PracticeGroup> groups, Members members, Map<Long, StandingSnapshot> snapshots) {
        Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings = new HashMap<>();
        snapshots.forEach((developer, snapshot) -> groupStandings.put(
                developer,
                practiceGroupStandingService.summarize(groups, snapshot).stream()
                        .collect(Collectors.toMap(
                                PracticeGroupStandingDTO::groupSlug, PracticeGroupStandingDTO::standing))));
        // With a standing: a verdict in a group the page shows, the same verdicts the group standings are read off.
        List<Long> withAStanding = members.eligible().stream()
                .filter(developer -> groupStandings.getOrDefault(developer, Map.of()).values().stream()
                        .anyMatch(PracticeGroupStandingDTO::isVerdict))
                .toList();
        return new Cohort(
                snapshots,
                groupStandings,
                withAStanding,
                members.reader() != null && withAStanding.contains(members.reader()));
    }

    /** A developer's standing in a practice; one their snapshot does not list is one nothing reached: not observed. */
    private static PracticeStandingDTO.Standing standingIn(StandingSnapshot snapshot, String practiceSlug) {
        StandingSnapshot.PracticeStanding practice = snapshot.practices().get(practiceSlug);
        return practice == null
                ? PracticeStandingDTO.Standing.NOT_OBSERVED
                : practice.dto().standing();
    }

    private static WorkspaceTileDTO tile(
            StandingSnapshot yours,
            List<StandingSnapshot> withAStanding,
            int others,
            ToIntFunction<StandingSnapshot> figure) {
        return WorkspaceTileDTO.of(
                figure.applyAsInt(yours),
                CohortPrivacyPolicy.middleHalf(
                        withAStanding.stream().map(figure::applyAsInt).toList(), others));
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
