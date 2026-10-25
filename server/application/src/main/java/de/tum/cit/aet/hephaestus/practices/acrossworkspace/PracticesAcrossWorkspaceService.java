package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceTilesDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceTileDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.result.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.StandingScale;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
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
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes Practices across the workspace (ADR 0051) from one standing read of the workspace, classified by the same
 * reader and group summary the Practice profile uses, then counted by {@link WorkspaceSplits}. Each developer counts
 * once, at the standing their profile shows, however much work they had reviewed. The splits and the open feedback
 * read the current standing; only the tiles read a window.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticesAcrossWorkspaceService {
    private final PracticeStandingService standings;
    private final PracticeGroupStandingService groupStandings;
    private final PracticeGroupService practiceGroups;
    private final PracticeReviewCoverageService scope;
    private final ResultRepository results;
    private final AccountMembershipQuery memberships;
    private final PersonProcessingSuppression suppression;
    private final CurrentDeveloperLookup currentDeveloper;
    private final PracticePageFeedbackService feedback;
    private final EvidenceAuthorization evidenceAuthorization;
    private final PracticeTrendService trends;
    private final Clock clock;

    /** The splits by the current standing and the open feedback, whatever window the tiles read. */
    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceDTO read(WorkspaceContext context) {
        return evidenceAuthorization.asOneRead(context.id(), () -> readOverview(context));
    }

    private PracticesAcrossWorkspaceDTO readOverview(WorkspaceContext context) {
        Long workspaceId = context.id();
        Members members = members(workspaceId, trends.horizon());
        PracticeStandingService.WorkspaceStandings read =
                standings.getCurrentWorkspaceStandings(workspaceId, members.read());
        // A group with no practice review is admitted for has nothing to split. No verdict in it can vote, so
        // leaving it out changes no one's standing.
        List<PracticeGroup> groups = practiceGroups.listGroups(context, true).stream()
                .filter(group -> !read.eligiblePracticesByGroup()
                        .getOrDefault(group.getSlug(), List.of())
                        .isEmpty())
                .toList();
        Cohort current = cohort(groups, members, read.byDeveloper());
        StandingSnapshot yours = current.snapshotOf(members.reader());

        List<WorkspaceGroupSplitDTO> rows = new ArrayList<>();
        for (PracticeGroup group : groups) {
            List<WorkspacePracticeSplitDTO> practiceSplits = new ArrayList<>();
            for (Practice practice : read.eligiblePracticesByGroup().getOrDefault(group.getSlug(), List.of())) {
                practiceSplits.add(new WorkspacePracticeSplitDTO(
                        practice.getSlug(),
                        Objects.requireNonNull(practice.getCurrentVersion().getName()),
                        current.readerCounted() ? standingIn(yours, practice.getSlug()) : null,
                        current.readerCounted() ? basisIn(yours, practice.getSlug()) : null,
                        WorkspaceSplits.split(current.withAStanding().stream()
                                .map(developer -> standingIn(current.snapshotOf(developer), practice.getSlug()))
                                .toList())));
            }
            PracticeGroupStandingDTO yourGroup = current.groupStandingOf(members.reader(), group.getSlug());
            rows.add(new WorkspaceGroupSplitDTO(
                    group.getSlug(),
                    group.getName(),
                    group.getIcon(),
                    group.getColor(),
                    current.readerCounted() && yourGroup != null ? yourGroup.standing() : null,
                    current.readerCounted() && yourGroup != null ? yourGroup.basis() : null,
                    WorkspaceSplits.split(current.withAStanding().stream()
                            .map(developer -> groupStanding(current.groupStandingOf(developer, group.getSlug())))
                            .toList()),
                    practiceSplits));
        }

        // Open now for everyone, whatever window the tiles read, so the tile never sets a moment beside a span.
        Map<Long, Integer> openFeedback = feedback.countOpen(workspaceId, members.read());
        @Nullable Long reader = members.reader();
        return new PracticesAcrossWorkspaceDTO(
                new WorkspaceTileDTO(
                        reader == null ? 0 : openFeedback.getOrDefault(reader, 0),
                        WorkspaceSplits.middleHalf(members.counted().stream()
                                .map(developer -> openFeedback.getOrDefault(developer, 0))
                                .toList())),
                current.includesEarlierReviews(),
                rows);
    }

    /** The reader's figures over the window beside the middle half of the developers with a standing in it. */
    @Transactional(readOnly = true)
    public PracticesAcrossWorkspaceTilesDTO readTiles(WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        return evidenceAuthorization.asOneRead(context.id(), () -> readWindow(context, window));
    }

    private PracticesAcrossWorkspaceTilesDTO readWindow(
            WorkspaceContext context, PracticesAcrossWorkspaceWindow window) {
        Long workspaceId = context.id();
        Instant since = window.since(clock.instant());
        Members members = members(workspaceId, since);
        Cohort inWindow = cohort(
                practiceGroups.listGroups(context, true),
                members,
                standings
                        .getWorkspaceStandings(workspaceId, members.read(), since)
                        .byDeveloper());
        StandingSnapshot yours = inWindow.snapshotOf(members.reader());
        List<StandingSnapshot> withAStanding =
                inWindow.withAStanding().stream().map(inWindow::snapshotOf).toList();
        return new PracticesAcrossWorkspaceTilesDTO(
                window,
                withAStanding.size(),
                yours.practices().size(),
                inWindow.includesEarlierReviews(),
                tile(yours, withAStanding, PracticesAcrossWorkspaceService::reviewedWork),
                tile(yours, withAStanding, snapshot -> practicesAt(snapshot, PracticeStanding.STRENGTH)),
                tile(yours, withAStanding, snapshot -> practicesAt(snapshot, PracticeStanding.DEVELOPING)));
    }

    /**
     * The developers the page counts, and the reader, who need not be one of them. Hidden members, members who left,
     * accounts that are not active and suppressed people are never counted.
     *
     * @param counted every developer the page counts, each once
     */
    private record Members(Set<Long> counted, @Nullable Long reader) {
        Set<Long> read() {
            Set<Long> read = new LinkedHashSet<>(counted);
            if (reader != null) {
                read.add(reader);
            }
            return read;
        }
    }

    private Members members(Long workspaceId, Instant since) {
        Set<Long> counted = new LinkedHashSet<>(scope.eligibleAccountIds(workspaceId));
        // Review eligibility leaves out members who chose No AI. Their standing from earlier reviews still counts
        // (ADR 0051), so a member with results in the read joins on the membership terms alone.
        List<Long> recorded = results.developersSince(workspaceId, since).stream()
                .filter(developer ->
                        !counted.contains(developer) && memberships.isActiveVisibleMember(workspaceId, developer))
                .toList();
        Set<Long> suppressed = recorded.isEmpty() ? Set.of() : suppression.suppressedAccountIds(recorded);
        recorded.stream().filter(developer -> !suppressed.contains(developer)).forEach(counted::add);
        return new Members(counted, currentDeveloper.currentDeveloperId().orElse(null));
    }

    /**
     * The developers read on one basis, the current standing or a window: each one's snapshot and group standings,
     * and the counted developers with a standing, a verdict in a group the page shows.
     */
    private record Cohort(
            Map<Long, StandingSnapshot> snapshots,
            Map<Long, Map<String, PracticeGroupStandingDTO>> groupStandings,
            List<Long> withAStanding,
            boolean readerCounted) {

        StandingSnapshot snapshotOf(@Nullable Long developer) {
            return developer == null
                    ? StandingSnapshot.EMPTY
                    : snapshots.getOrDefault(developer, StandingSnapshot.EMPTY);
        }

        /** The developer's standing in the group; null for a caller nothing was read for. */
        @Nullable
        PracticeGroupStandingDTO groupStandingOf(@Nullable Long developer, String groupSlug) {
            return developer == null
                    ? null
                    : groupStandings.getOrDefault(developer, Map.of()).get(groupSlug);
        }

        boolean includesEarlierReviews() {
            return withAStanding.stream()
                    .anyMatch(developer -> groupStandings.getOrDefault(developer, Map.of()).values().stream()
                            .anyMatch(group -> group.basis() == StandingBasis.EARLIER_REVIEWS
                                    || group.basis() == StandingBasis.EARLIER_REVIEWS_CRITERIA_UNKNOWN));
        }
    }

    private Cohort cohort(List<PracticeGroup> groups, Members members, Map<Long, StandingSnapshot> snapshots) {
        Map<Long, Map<String, PracticeGroupStandingDTO>> byDeveloper = new HashMap<>();
        snapshots.forEach((developer, snapshot) -> byDeveloper.put(
                developer,
                groupStandings.summarize(groups, snapshot).stream()
                        .collect(Collectors.toMap(PracticeGroupStandingDTO::groupSlug, group -> group))));
        // With a standing: a verdict in a group the page shows, the same verdicts the group standings are read off.
        List<Long> withAStanding = members.counted().stream()
                .filter(developer -> byDeveloper.getOrDefault(developer, Map.of()).values().stream()
                        .anyMatch(group -> StandingScale.isVerdict(group.standing())))
                .toList();
        return new Cohort(
                snapshots,
                byDeveloper,
                withAStanding,
                members.reader() != null && withAStanding.contains(members.reader()));
    }

    private static PracticeStanding groupStanding(@Nullable PracticeGroupStandingDTO group) {
        return group == null ? PracticeStanding.NOT_OBSERVED : group.standing();
    }

    /** A developer's standing in a practice; one their snapshot does not list is one nothing reached. */
    private static PracticeStanding standingIn(StandingSnapshot snapshot, String practiceSlug) {
        var practice = snapshot.practices().get(practiceSlug);
        return practice == null ? PracticeStanding.NOT_OBSERVED : practice.dto().standing();
    }

    private static @Nullable StandingBasis basisIn(StandingSnapshot snapshot, String practiceSlug) {
        var practice = snapshot.practices().get(practiceSlug);
        return practice == null ? null : practice.dto().basis();
    }

    private static WorkspaceTileDTO tile(
            StandingSnapshot yours, List<StandingSnapshot> withAStanding, ToIntFunction<StandingSnapshot> figure) {
        return new WorkspaceTileDTO(
                figure.applyAsInt(yours),
                WorkspaceSplits.middleHalf(
                        withAStanding.stream().map(figure::applyAsInt).toList()));
    }

    /** Distinct pieces of work any practice's standing was read from, verdict or not: the work that was reviewed. */
    private static int reviewedWork(StandingSnapshot snapshot) {
        return (int) snapshot.practices().values().stream()
                .flatMap(practice -> practice.evidence().stream())
                .map(view -> view.result().getItemId())
                .distinct()
                .count();
    }

    private static int practicesAt(StandingSnapshot snapshot, PracticeStanding standing) {
        return (int) snapshot.practices().values().stream()
                .filter(practice -> practice.dto().standing() == standing)
                .count();
    }
}
