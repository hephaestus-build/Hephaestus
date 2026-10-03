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
 * Composes Practices across the workspace from the standings that already exist: every eligible developer's
 * snapshot read off one scan of the workspace, rolled up to practice groups by the same classifier the practice
 * profile uses, then counted per group and per practice under {@link CohortPrivacyPolicy}.
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
        Map<Long, StandingSnapshot> snapshots = practiceStandingService.getWorkspaceStandingSnapshots(
                workspaceId, read, since == null ? Instant.EPOCH : since, now);
        StandingSnapshot yours = reader == null ? NOTHING_READ : snapshots.getOrDefault(reader, NOTHING_READ);

        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings = new HashMap<>();
        for (Long developer : read) {
            groupStandings.put(developer, groupStandings(groups, snapshots.getOrDefault(developer, NOTHING_READ)));
        }
        // Observed: a verdict in a group the page shows, the same verdicts the group standings are read off.
        List<Long> observed = eligible.stream()
                .filter(developer -> Objects.requireNonNull(groupStandings.get(developer)).values().stream()
                        .anyMatch(PracticeGroupStandingDTO::isVerdict))
                .toList();
        boolean readerEligible = reader != null && eligible.contains(reader);
        boolean readerCounted = reader != null && observed.contains(reader);
        int others = observed.size() - (readerCounted ? 1 : 0);

        Map<String, PracticeGroupStandingDTO> yourGroups =
                practiceGroupStandingService.summarize(groups, yours).stream()
                        .collect(Collectors.toMap(
                                PracticeGroupStandingDTO::groupSlug, Function.identity(), (a, b) -> a));
        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (PracticeGroup group : groups) {
            PracticeGroupStandingDTO yourGroup = Objects.requireNonNull(yourGroups.get(group.getSlug()));
            List<PracticeStandingDTO> practices = yours.practices().values().stream()
                    .map(StandingSnapshot.PracticeStanding::dto)
                    .filter(practice -> group.getSlug().equals(practice.groupSlug()))
                    .toList();
            Function<Long, Row> rowOf = developer -> new Row(
                    Bucket.of(Objects.requireNonNull(Objects.requireNonNull(groupStandings.get(developer))
                            .get(group.getSlug()))),
                    practices.stream()
                            .map(practice ->
                                    practiceBucket(snapshots.getOrDefault(developer, NOTHING_READ), practice.slug()))
                            .toList());
            GroupRelease release =
                    CohortPrivacyPolicy.group(observed.stream().map(rowOf).toList(), practices.size());
            List<WorkspacePracticeSplitDTO> practiceSplits = new ArrayList<>();
            for (int index = 0; index < practices.size(); index++) {
                PracticeStandingDTO practice = practices.get(index);
                practiceSplits.add(new WorkspacePracticeSplitDTO(
                        practice.slug(),
                        practice.name(),
                        practice.standing(),
                        WorkspaceSplitDTO.from(release.practices().get(index))));
            }
            rows.add(new WorkspaceGroupSplitDTO(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    yourGroup.standing(),
                    yourGroup.direction(),
                    yourGroup.trendSupport(),
                    WorkspaceSplitDTO.from(release.group()),
                    practiceSplits));
        }

        List<StandingSnapshot> observedSnapshots = observed.stream()
                .map(developer -> snapshots.getOrDefault(developer, NOTHING_READ))
                .toList();
        // Open now, for the reader and the workspace alike, so the tile sets one moment beside one moment: every
        // eligible developer, whatever the window, counted by the profile's own rule in one pass.
        Set<Long> recipients = new LinkedHashSet<>(eligible);
        if (reader != null) {
            recipients.add(reader);
        }
        Map<Long, Integer> openFeedback = inAppFeedbackService.countOpen(workspaceId, recipients, now);
        MiddleHalf openMiddle = CohortPrivacyPolicy.middleHalf(
                eligible.stream()
                        .map(developer -> openFeedback.getOrDefault(developer, 0))
                        .toList(),
                eligible.size() - (readerEligible ? 1 : 0));
        return new PracticesAcrossWorkspaceDTO(
                window,
                CohortPrivacyPolicy.MINIMUM_OTHERS,
                CohortPrivacyPolicy.observedTotal(others, readerCounted),
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
                new WorkspaceTileDTO(
                        reader == null ? 0 : openFeedback.getOrDefault(reader, 0),
                        openMiddle == null ? null : openMiddle.low(),
                        openMiddle == null ? null : openMiddle.high()),
                rows);
    }

    /** A practice the developer's snapshot does not list is one nothing reached for them: none yet. */
    private static Bucket practiceBucket(StandingSnapshot snapshot, String practiceSlug) {
        StandingSnapshot.PracticeStanding practice = snapshot.practices().get(practiceSlug);
        return practice == null ? Bucket.NONE_YET : Bucket.of(practice.dto().standing());
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
