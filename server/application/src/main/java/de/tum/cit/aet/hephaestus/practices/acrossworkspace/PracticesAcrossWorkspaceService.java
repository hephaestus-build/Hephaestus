package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Row;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
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
import de.tum.cit.aet.hephaestus.practices.observation.ReviewedWorkKey;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes Practices across the workspace from the standings that already exist, read off one scan of the workspace
 * per basis and rolled up to practice groups by the same classifier the practice profile uses, then counted under
 * {@link CohortPrivacyPolicy}. The group and practice splits count every eligible developer by their current
 * standing, the one their practice profile shows, so the reader's marker is their profile's standing; only the tiles
 * read the window.
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
    private final InAppFeedbackService inAppFeedbackService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceDTO read(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        Instant now = clock.instant();
        @Nullable Instant since = window.since(now);

        // Hidden members are left out of every workspace total, so they are left out of these counts too.
        Set<Long> eligible = new LinkedHashSet<>(membershipService.practiceReviewEligibleUserIds(workspaceId));
        eligible.removeAll(membershipService.getHiddenMemberIds(workspaceId));
        @Nullable Long reader = currentDeveloperLookup.currentDeveloperId().orElse(null);
        Set<Long> read = new LinkedHashSet<>(eligible);
        if (reader != null) {
            read.add(reader);
        }
        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        // The bars: every developer as their practice profile shows them now, whatever the window.
        Cohort current = cohort(
                groups,
                eligible,
                reader,
                practiceStandingService.getCurrentWorkspaceStandingSnapshots(workspaceId, read));
        // The tiles: the evidence in the window alone.
        Cohort inWindow = cohort(
                groups,
                eligible,
                reader,
                practiceStandingService.getWorkspaceStandingSnapshots(
                        workspaceId, read, since == null ? Instant.EPOCH : since, now));
        boolean readerEligible = reader != null && eligible.contains(reader);

        // The practices every reader sees, whatever their own evidence says.
        Map<String, List<Practice>> eligiblePractices = practiceStandingService.eligiblePracticesByGroup(workspaceId);
        Map<String, PracticeGroupStandingDTO> yourGroups = current.groupStandingsOf(reader);
        StandingSnapshot yoursNow = current.snapshotOf(reader);
        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (PracticeGroup group : groups) {
            List<Practice> practices = eligiblePractices.getOrDefault(group.getSlug(), List.of());
            Function<Long, Row> rowOf = developer -> new Row(
                    Bucket.of(Objects.requireNonNull(
                                    current.groupStandingsOf(developer).get(group.getSlug()))
                            .standing()),
                    practices.stream()
                            .map(practice -> practiceBucket(current.snapshotOf(developer), practice.getSlug()))
                            .toList());
            GroupRelease release = CohortPrivacyPolicy.group(
                    current.withAStanding().stream().map(rowOf).toList(), practices.size());
            List<WorkspacePracticeSplitDTO> practiceSplits = new ArrayList<>();
            for (int index = 0; index < practices.size(); index++) {
                Practice practice = practices.get(index);
                practiceSplits.add(new WorkspacePracticeSplitDTO(
                        practice.getSlug(),
                        practice.getName(),
                        yourStanding(yoursNow, practice.getSlug()),
                        WorkspaceSplitDTO.from(release.practices().get(index))));
            }
            rows.add(new WorkspaceGroupSplitDTO(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    Objects.requireNonNull(yourGroups.get(group.getSlug())).standing(),
                    WorkspaceSplitDTO.from(release.group()),
                    practiceSplits));
        }

        StandingSnapshot yours = inWindow.snapshotOf(reader);
        List<StandingSnapshot> snapshotsWithAStanding =
                inWindow.withAStanding().stream().map(inWindow::snapshotOf).toList();
        int others = inWindow.others();
        // Open now, for the reader and the workspace alike, so the tile sets one moment beside one moment: every
        // eligible developer, whatever the window, counted by the profile's own rule in one pass.
        Map<Long, Integer> openFeedback = inAppFeedbackService.countOpen(workspaceId, read);
        MiddleHalf openMiddle = CohortPrivacyPolicy.middleHalf(
                eligible.stream()
                        .map(developer -> openFeedback.getOrDefault(developer, 0))
                        .toList(),
                eligible.size() - (readerEligible ? 1 : 0));
        return new PracticesAcrossWorkspaceDTO(
                window,
                CohortPrivacyPolicy.MINIMUM_OTHERS,
                CohortPrivacyPolicy.totalWithAStanding(current.others(), current.readerCounted()),
                current.readerCounted(),
                CohortPrivacyPolicy.totalWithAStanding(others, inWindow.readerCounted()),
                yours.practices().size(),
                tile(yours, snapshotsWithAStanding, others, PracticesAcrossWorkspaceService::reviewedWork),
                tile(
                        yours,
                        snapshotsWithAStanding,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.STRENGTH)),
                tile(
                        yours,
                        snapshotsWithAStanding,
                        others,
                        snapshot -> practicesAt(snapshot, PracticeStandingDTO.Standing.DEVELOPING)),
                new WorkspaceTileDTO(
                        reader == null ? 0 : openFeedback.getOrDefault(reader, 0),
                        openMiddle == null ? null : openMiddle.low(),
                        openMiddle == null ? null : openMiddle.high()),
                rows);
    }

    /**
     * The developers read on one basis, the current standing or a window: each one's snapshot and group standings,
     * and the eligible developers with a standing, those with a verdict in a group the page shows.
     */
    private record Cohort(
            Map<Long, StandingSnapshot> snapshots,
            Map<Long, Map<String, PracticeGroupStandingDTO>> groupStandings,
            Map<String, PracticeGroupStandingDTO> nothingRead,
            List<Long> withAStanding,
            boolean readerCounted) {

        /** The developer's snapshot, or that of someone nothing reached when nothing was read for them. */
        StandingSnapshot snapshotOf(@Nullable Long developer) {
            return developer == null ? NOTHING_READ : snapshots.getOrDefault(developer, NOTHING_READ);
        }

        Map<String, PracticeGroupStandingDTO> groupStandingsOf(@Nullable Long developer) {
            return developer == null ? nothingRead : groupStandings.getOrDefault(developer, nothingRead);
        }

        /** The developers with a standing other than the reader. */
        int others() {
            return withAStanding.size() - (readerCounted ? 1 : 0);
        }
    }

    private Cohort cohort(
            List<PracticeGroup> groups,
            Set<Long> eligible,
            @Nullable Long reader,
            Map<Long, StandingSnapshot> snapshots) {
        Map<Long, Map<String, PracticeGroupStandingDTO>> groupStandings = new HashMap<>();
        snapshots.forEach((developer, snapshot) -> groupStandings.put(developer, groupStandings(groups, snapshot)));
        // With a standing: a verdict in a group the page shows, the same verdicts the group standings are read off.
        List<Long> withAStanding = eligible.stream()
                .filter(developer -> groupStandings.getOrDefault(developer, Map.of()).values().stream()
                        .anyMatch(group -> PracticeGroupStandingDTO.isVerdict(group.standing())))
                .toList();
        return new Cohort(
                snapshots,
                groupStandings,
                groupStandings(groups, NOTHING_READ),
                withAStanding,
                reader != null && withAStanding.contains(reader));
    }

    /** The reader's own standing in a practice, or not observed when nothing of theirs was read. */
    private static PracticeStandingDTO.Standing yourStanding(StandingSnapshot yours, String practiceSlug) {
        StandingSnapshot.PracticeStanding practice = yours.practices().get(practiceSlug);
        return practice == null
                ? PracticeStandingDTO.Standing.NOT_OBSERVED
                : practice.dto().standing();
    }

    /** A practice the developer's snapshot does not list is one nothing reached for them: none yet. */
    private static Bucket practiceBucket(StandingSnapshot snapshot, String practiceSlug) {
        StandingSnapshot.PracticeStanding practice = snapshot.practices().get(practiceSlug);
        return practice == null ? Bucket.NONE_YET : Bucket.of(practice.dto().standing());
    }

    private Map<String, PracticeGroupStandingDTO> groupStandings(
            List<PracticeGroup> groups, StandingSnapshot snapshot) {
        return practiceGroupStandingService.summarize(groups, snapshot).stream()
                .collect(Collectors.toMap(PracticeGroupStandingDTO::groupSlug, Function.identity(), (a, b) -> a));
    }

    private static WorkspaceTileDTO tile(
            StandingSnapshot yours,
            List<StandingSnapshot> withAStanding,
            int others,
            ToIntFunction<StandingSnapshot> figure) {
        MiddleHalf middle = CohortPrivacyPolicy.middleHalf(
                withAStanding.stream().map(figure::applyAsInt).toList(), others);
        return new WorkspaceTileDTO(
                figure.applyAsInt(yours), middle == null ? null : middle.low(), middle == null ? null : middle.high());
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
