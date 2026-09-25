package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads what moved between two standings snapshots of one developer.
 *
 * <p>Pure on purpose: both snapshots come from the one standing service, read as of the two edges of the
 * window, so this class owns no standing arithmetic — it only compares labels. The evidence it attaches is
 * the work reviewed inside the window for the practice in question, which is exactly what the two snapshots
 * differ by.
 */
final class ProfileChangeDetector {

    private ProfileChangeDetector() {}

    /**
     * @param before the practice standings as of the window's lower edge
     * @param after the practice standings as of its upper edge
     * @param groupsBefore the group standings as of the lower edge
     * @param groupsAfter the group standings as of the upper edge
     * @param windowWorkByPractice the work reviewed inside the window, per practice slug, newest first
     * @param firstObservedAt when each practice first recorded an observation about the developer
     * @param targets the runs behind the window's work, for naming it
     */
    static List<ProfileChangeDTO> detect(
            OverviewWindow window,
            List<PracticeStandingDTO> before,
            List<PracticeStandingDTO> after,
            List<PracticeGroupStandingDTO> groupsBefore,
            List<PracticeGroupStandingDTO> groupsAfter,
            Map<String, List<Work>> windowWorkByPractice,
            Map<String, Instant> firstObservedAt,
            Map<UUID, Target> targets) {
        Map<String, PracticeStandingDTO> earlier = bySlug(before, PracticeStandingDTO::slug);
        Map<String, PracticeGroupStandingDTO> earlierGroups = bySlug(groupsBefore, PracticeGroupStandingDTO::groupSlug);
        List<ProfileChangeDTO> changes = new ArrayList<>();

        for (PracticeStandingDTO now : after) {
            List<Work> work = windowWorkByPractice.getOrDefault(now.slug(), List.of());
            List<ReviewedWorkRefDTO> evidence = refs(work, targets);
            Instant at = work.isEmpty() ? window.until() : work.getFirst().at();
            PracticeStandingDTO was = earlier.get(now.slug());
            PracticeStandingDTO.Standing standingBefore =
                    was == null ? PracticeStandingDTO.Standing.NOT_OBSERVED : was.standing();
            if (standingBefore != now.standing()
                    && (PracticeStandingDTO.isVerdict(standingBefore)
                            || PracticeStandingDTO.isVerdict(now.standing()))) {
                changes.add(practiceChange(
                        ProfileChangeDTO.Type.STANDING_MOVED,
                        at,
                        now,
                        standingBefore.name(),
                        now.standing().name(),
                        evidence));
            }
            TrendDirection directionBefore = was == null ? null : was.direction();
            TrendDirection direction = now.direction();
            // Only a turn one side of which points somewhere: crossing the minimum evidence into UNCERTAIN, or
            // falling back out of it, changes what the trend may claim, not where the developer's work is going.
            if (directionBefore != null
                    && direction != null
                    && directionBefore != direction
                    && (directionBefore.isDirectional() || direction.isDirectional())) {
                changes.add(practiceChange(
                        ProfileChangeDTO.Type.TREND_TURNED,
                        at,
                        now,
                        directionBefore.name(),
                        direction.name(),
                        evidence));
            }
            Instant first = firstObservedAt.get(now.slug());
            if (first != null && window.contains(first)) {
                changes.add(practiceChange(ProfileChangeDTO.Type.FIRST_OBSERVED, first, now, null, null, evidence));
            }
        }

        Map<String, List<Work>> windowWorkByGroup = windowWorkByGroup(after, windowWorkByPractice);
        for (PracticeGroupStandingDTO now : groupsAfter) {
            PracticeGroupStandingDTO was = earlierGroups.get(now.groupSlug());
            PracticeGroupStandingDTO.Standing standingBefore =
                    was == null ? PracticeGroupStandingDTO.Standing.NOT_OBSERVED : was.standing();
            if (standingBefore == now.standing()
                    || (!PracticeGroupStandingDTO.isVerdict(standingBefore)
                            && !PracticeGroupStandingDTO.isVerdict(now.standing()))) {
                continue;
            }
            List<Work> work = windowWorkByGroup.getOrDefault(now.groupSlug(), List.of());
            changes.add(new ProfileChangeDTO(
                    ProfileChangeDTO.Type.GROUP_MOVED,
                    work.isEmpty() ? window.until() : work.getFirst().at(),
                    null,
                    null,
                    now.groupSlug(),
                    now.groupName(),
                    standingBefore.name(),
                    now.standing().name(),
                    null,
                    null,
                    null,
                    refs(work, targets)));
        }
        return changes;
    }

    /** The work as the page names it, in the work's order. */
    static List<ReviewedWorkRefDTO> refs(List<Work> work, Map<UUID, Target> targets) {
        return work.stream()
                .map(ref -> ReviewedWorkLabels.ref(ref.kind(), ref.id(), targets.get(ref.jobId())))
                .toList();
    }

    private static ProfileChangeDTO practiceChange(
            ProfileChangeDTO.Type type,
            Instant at,
            PracticeStandingDTO practice,
            @Nullable String from,
            @Nullable String to,
            List<ReviewedWorkRefDTO> evidence) {
        return new ProfileChangeDTO(
                type,
                at,
                practice.slug(),
                practice.name(),
                practice.groupSlug(),
                practice.groupName(),
                from,
                to,
                null,
                null,
                null,
                evidence);
    }

    /** The window's work per group: its practices' work merged, one entry per piece of work, newest first. */
    private static Map<String, List<Work>> windowWorkByGroup(
            List<PracticeStandingDTO> practices, Map<String, List<Work>> windowWorkByPractice) {
        Map<String, List<Work>> byGroup = new LinkedHashMap<>();
        for (PracticeStandingDTO practice : practices) {
            if (practice.groupSlug() == null) {
                continue;
            }
            byGroup.merge(
                    practice.groupSlug(),
                    windowWorkByPractice.getOrDefault(practice.slug(), List.of()),
                    (left, right) -> Work.newestFirst(Stream.concat(left.stream(), right.stream())));
        }
        return byGroup;
    }

    private static <T> Map<String, T> bySlug(List<T> items, Function<T, String> slug) {
        return items.stream().collect(Collectors.toMap(slug, Function.identity(), (left, ignored) -> left));
    }
}
