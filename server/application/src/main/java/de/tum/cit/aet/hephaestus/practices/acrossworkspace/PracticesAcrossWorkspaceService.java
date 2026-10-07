package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Developer;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Standings;
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
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composes Practices across the workspace from one scan of the workspace's observations per count, classified by the
 * same standing and group standing rules the practice profile uses, then counted under {@link CohortPrivacyPolicy}.
 * The splits and the open feedback read the current standing, so the reader's marker is the standing their profile
 * shows; only the tiles read a window. Aggregating in SQL instead would put a second copy of the standing rules
 * beside the profile's, free to disagree with it.
 *
 * <p>What every reader shares is counted once per workspace and window and kept in
 * {@link PracticesAcrossWorkspaceCache}; the reader's own figures are read off that count, or read alone for a
 * reader the count does not cover, and the reader's open feedback is always read now.
 */
@Service
public class PracticesAcrossWorkspaceService {

    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupService practiceGroupService;
    private final WorkspaceMembershipService membershipService;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final InAppFeedbackService inAppFeedbackService;
    private final EvidenceAuthorization evidenceAuthorization;
    private final PracticesAcrossWorkspaceCache cache;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public PracticesAcrossWorkspaceService(
            PracticeStandingService practiceStandingService,
            PracticeGroupStandingService practiceGroupStandingService,
            PracticeGroupService practiceGroupService,
            WorkspaceMembershipService membershipService,
            CurrentDeveloperLookup currentDeveloperLookup,
            InAppFeedbackService inAppFeedbackService,
            EvidenceAuthorization evidenceAuthorization,
            PracticesAcrossWorkspaceCache cache,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.practiceStandingService = practiceStandingService;
        this.practiceGroupStandingService = practiceGroupStandingService;
        this.practiceGroupService = practiceGroupService;
        this.membershipService = membershipService;
        this.currentDeveloperLookup = currentDeveloperLookup;
        this.inAppFeedbackService = inAppFeedbackService;
        this.evidenceAuthorization = evidenceAuthorization;
        this.cache = cache;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** The splits by the current standing and the open feedback, whatever window the tiles read. */
    public PracticesAcrossWorkspaceDTO read(WorkspaceContext context) {
        Long workspaceId = context.id();
        @Nullable Long reader = currentDeveloperLookup.currentDeveloperId().orElse(null);
        // Counted outside any transaction of this request, so a request waiting on another's count holds no
        // database connection while it waits.
        Overview overview =
                cache.get(workspaceId, "overview", () -> asOneRead(workspaceId, () -> countOverview(context)));
        // Open now for the reader, whatever window the tiles read, so the tile never sets a moment beside a span.
        int yourOpenFeedback = reader == null
                ? 0
                : inReadOnly(() -> inAppFeedbackService
                        .countOpen(workspaceId, List.of(reader))
                        .getOrDefault(reader, 0));
        boolean readerCounted = reader != null && overview.marks().containsKey(reader);
        Marks yours = reader == null ? Marks.NONE : overview.marks().getOrDefault(reader, Marks.NONE);
        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (GroupRow group : overview.groups()) {
            List<WorkspacePracticeSplitDTO> practiceSplits = new ArrayList<>();
            for (PracticeRow practice : group.practices()) {
                practiceSplits.add(new WorkspacePracticeSplitDTO(
                        practice.slug(),
                        practice.name(),
                        CohortPrivacyPolicy.marker(
                                practice.split(),
                                readerCounted,
                                yours.practices()
                                        .getOrDefault(practice.slug(), PracticeStandingDTO.Standing.NOT_OBSERVED)),
                        WorkspaceSplitDTO.from(practice.split())));
            }
            rows.add(new WorkspaceGroupSplitDTO(
                    group.slug(),
                    group.name(),
                    group.icon(),
                    group.color(),
                    CohortPrivacyPolicy.marker(
                            group.split(),
                            readerCounted,
                            yours.groups().getOrDefault(group.slug(), PracticeGroupStandingDTO.Standing.NOT_OBSERVED)),
                    WorkspaceSplitDTO.from(group.split()),
                    practiceSplits));
        }
        return new PracticesAcrossWorkspaceDTO(
                CohortPrivacyPolicy.MINIMUM_DEVELOPERS_PER_COUNT,
                WorkspaceTileDTO.of(yourOpenFeedback, overview.openFeedbackMiddle()),
                rows);
    }

    /** The reader's figures over the window beside the middle half of the developers with a standing in it. */
    public PracticesAcrossWorkspaceTilesDTO readTiles(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        @Nullable Long reader = currentDeveloperLookup.currentDeveloperId().orElse(null);
        Tiles tiles = cache.get(
                workspaceId, "tiles:" + window, () -> asOneRead(workspaceId, () -> countTiles(context, window)));
        Figures yours;
        if (reader == null) {
            yours = Figures.of(StandingSnapshot.EMPTY);
        } else if (tiles.figures().containsKey(reader)) {
            yours = tiles.figures().get(reader);
        } else {
            // A reader the workspace does not count, such as a hidden member, still sees their own figures.
            yours = inReadOnly(() -> Figures.of(practiceStandingService
                    .getWorkspaceStandingSnapshots(workspaceId, Set.of(reader), tiles.since())
                    .byDeveloper()
                    .getOrDefault(reader, StandingSnapshot.EMPTY)));
        }
        return new PracticesAcrossWorkspaceTilesDTO(
                window,
                CohortPrivacyPolicy.MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF,
                CohortPrivacyPolicy.count(tiles.developersWithAStanding()),
                yours.practices(),
                WorkspaceTileDTO.of(yours.reviewedWork(), tiles.reviewedWork()),
                WorkspaceTileDTO.of(yours.goingWell(), tiles.goingWell()),
                WorkspaceTileDTO.of(yours.needingAttention(), tiles.needingAttention()));
    }

    private <T> T inReadOnly(Supplier<T> read) {
        return Objects.requireNonNull(readOnly.execute(status -> read.get()));
    }

    /**
     * A count of the whole workspace in one transaction, whose evidence checks are shared: a source the standings
     * and the open feedback both cite is checked once.
     */
    private <T> T asOneRead(long workspaceId, Supplier<T> count) {
        return inReadOnly(
                () -> evidenceAuthorization.asOneRead(workspaceId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, count));
    }

    /**
     * Everything of the overview every reader shares: each split as {@link CohortPrivacyPolicy} releases it, the
     * standings of each developer counted, which only that developer reads as their marker, and the middle half of
     * the open feedback.
     */
    private Overview countOverview(WorkspaceContext context) {
        Long workspaceId = context.id();
        Set<Long> eligible = eligible(workspaceId);
        List<PracticeGroup> groups = practiceGroupService.listGroups(context, true);
        WorkspaceStandings standings =
                practiceStandingService.getCurrentWorkspaceStandingSnapshots(workspaceId, eligible);
        Cohort current = cohort(groups, eligible, standings.byDeveloper());

        // The practices every reader sees, whatever their own evidence says.
        List<List<Practice>> practices = groups.stream()
                .map(group -> standings.eligiblePracticesByGroup().getOrDefault(group.getSlug(), List.of()))
                .toList();
        List<GroupRelease> release = CohortPrivacyPolicy.page(
                current.withAStanding().stream()
                        .map(developer -> developerOf(current, developer, groups, practices))
                        .toList(),
                practices.stream().map(List::size).toList());

        List<GroupRow> rows = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            PracticeGroup group = groups.get(groupIndex);
            GroupRelease groupRelease = release.get(groupIndex);
            List<PracticeRow> practiceRows = new ArrayList<>();
            for (int index = 0; index < practices.get(groupIndex).size(); index++) {
                Practice practice = practices.get(groupIndex).get(index);
                practiceRows.add(new PracticeRow(
                        practice.getSlug(),
                        practice.getName(),
                        groupRelease.practices().get(index)));
            }
            rows.add(new GroupRow(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    groupRelease.group(),
                    List.copyOf(practiceRows)));
        }

        // A marker shows only for a reader the splits count, so only those developers' standings are kept.
        Map<Long, Marks> marks = new HashMap<>();
        for (Long developer : current.withAStanding()) {
            StandingSnapshot snapshot = current.snapshotOf(developer);
            Map<String, PracticeStandingDTO.Standing> practiceStandings = new HashMap<>();
            practices.forEach(list -> list.forEach(
                    practice -> practiceStandings.put(practice.getSlug(), standingIn(snapshot, practice.getSlug()))));
            Map<String, PracticeGroupStandingDTO.Standing> groupStandings = new HashMap<>();
            groups.forEach(
                    group -> groupStandings.put(group.getSlug(), current.groupStandingOf(developer, group.getSlug())));
            marks.put(developer, new Marks(Map.copyOf(groupStandings), Map.copyOf(practiceStandings)));
        }

        Map<Long, Integer> openFeedback = inAppFeedbackService.countOpen(workspaceId, eligible);
        MiddleHalf openMiddle = CohortPrivacyPolicy.middleHalf(eligible.stream()
                .map(developer -> openFeedback.getOrDefault(developer, 0))
                .toList());
        return new Overview(List.copyOf(rows), Map.copyOf(marks), openMiddle);
    }

    /** Everything of one window's tiles every reader shares, and each counted developer's own figures. */
    private Tiles countTiles(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        Set<Long> eligible = eligible(workspaceId);
        Instant since = window.since(clock.instant());
        Cohort inWindow = cohort(
                practiceGroupService.listGroups(context, true),
                eligible,
                practiceStandingService
                        .getWorkspaceStandingSnapshots(workspaceId, eligible, since)
                        .byDeveloper());
        Map<Long, Figures> figures = new HashMap<>();
        eligible.forEach(developer -> figures.put(developer, Figures.of(inWindow.snapshotOf(developer))));
        List<Figures> withAStanding =
                inWindow.withAStanding().stream().map(figures::get).toList();
        return new Tiles(
                since,
                withAStanding.size(),
                Map.copyOf(figures),
                CohortPrivacyPolicy.middleHalf(
                        withAStanding.stream().map(Figures::reviewedWork).toList()),
                CohortPrivacyPolicy.middleHalf(
                        withAStanding.stream().map(Figures::goingWell).toList()),
                CohortPrivacyPolicy.middleHalf(
                        withAStanding.stream().map(Figures::needingAttention).toList()));
    }

    /** The overview as every reader shares it. */
    private record Overview(
            List<GroupRow> groups,
            Map<Long, Marks> marks,
            @Nullable MiddleHalf openFeedbackMiddle) {}

    private record GroupRow(
            String slug,
            String name,
            @Nullable String icon,
            @Nullable String color,
            Split split,
            List<PracticeRow> practices) {}

    private record PracticeRow(String slug, String name, Split split) {}

    /** A counted developer's standing in each group and practice the page shows: their marker, when they read it. */
    private record Marks(
            Map<String, PracticeGroupStandingDTO.Standing> groups,
            Map<String, PracticeStandingDTO.Standing> practices) {
        static final Marks NONE = new Marks(Map.of(), Map.of());
    }

    /** One window's tiles as every reader shares them, read from {@code since}. */
    private record Tiles(
            Instant since,
            int developersWithAStanding,
            Map<Long, Figures> figures,
            @Nullable MiddleHalf reviewedWork,
            @Nullable MiddleHalf goingWell,
            @Nullable MiddleHalf needingAttention) {}

    /** One developer's figures over a window. */
    private record Figures(int practices, int reviewedWork, int goingWell, int needingAttention) {
        static Figures of(StandingSnapshot snapshot) {
            return new Figures(
                    snapshot.practices().size(),
                    PracticesAcrossWorkspaceService.reviewedWork(snapshot),
                    practicesAt(snapshot, PracticeStandingDTO.Standing.STRENGTH),
                    practicesAt(snapshot, PracticeStandingDTO.Standing.DEVELOPING));
        }
    }

    /** The developers the page counts, without the hidden members every workspace total leaves out. */
    private Set<Long> eligible(Long workspaceId) {
        Set<Long> eligible = new LinkedHashSet<>(membershipService.practiceReviewEligibleUserIds(workspaceId));
        eligible.removeAll(membershipService.getHiddenMemberIds(workspaceId));
        return eligible;
    }

    /**
     * The developers read on one basis, the current standing or a window: each one's snapshot and group standings,
     * and the eligible developers with a standing, those with a verdict in a group the page shows.
     */
    private record Cohort(
            Map<Long, StandingSnapshot> snapshots,
            Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings,
            List<Long> withAStanding) {

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
    }

    private Cohort cohort(List<PracticeGroup> groups, Set<Long> eligible, Map<Long, StandingSnapshot> snapshots) {
        Map<Long, Map<String, PracticeGroupStandingDTO.Standing>> groupStandings = new HashMap<>();
        snapshots.forEach((developer, snapshot) -> groupStandings.put(
                developer,
                practiceGroupStandingService.summarize(groups, snapshot).stream()
                        .collect(Collectors.toMap(
                                PracticeGroupStandingDTO::groupSlug, PracticeGroupStandingDTO::standing))));
        // With a standing: a verdict in a group the page shows, the same verdicts the group standings are read off.
        List<Long> withAStanding = eligible.stream()
                .filter(developer -> groupStandings.getOrDefault(developer, Map.of()).values().stream()
                        .anyMatch(PracticeGroupStandingDTO::isVerdict))
                .toList();
        return new Cohort(snapshots, groupStandings, withAStanding);
    }

    private static Developer developerOf(
            Cohort cohort, Long developer, List<PracticeGroup> groups, List<List<Practice>> practices) {
        StandingSnapshot snapshot = cohort.snapshotOf(developer);
        return new Developer(IntStream.range(0, groups.size())
                .mapToObj(index -> new Standings(
                        cohort.groupStandingOf(developer, groups.get(index).getSlug())
                                .asPracticeStanding(),
                        practices.get(index).stream()
                                .map(practice -> standingIn(snapshot, practice.getSlug()))
                                .toList()))
                .toList());
    }

    /** A developer's standing in a practice; one their snapshot does not list is one nothing reached: not observed. */
    private static PracticeStandingDTO.Standing standingIn(StandingSnapshot snapshot, String practiceSlug) {
        StandingSnapshot.PracticeStanding practice = snapshot.practices().get(practiceSlug);
        return practice == null
                ? PracticeStandingDTO.Standing.NOT_OBSERVED
                : practice.dto().standing();
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
