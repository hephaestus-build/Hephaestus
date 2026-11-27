package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultVisibilityPolicy {

    private final EvidenceAuthorization evidenceAuthorization;
    private final ResultMarkRepository marks;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ResultSourceAuthorization sources;
    private final FreshnessRead freshness;

    /**
     * The rows a standing may be read off at {@code at}, out of rows the standing reader already selected. Unlike
     * {@link #permitsAt}, a row need not hold its claim at {@code at}: the reader decides which ended live run still
     * stands. Every other fence holds at {@code at}: the workspace, recording before {@code at}, no Incorrect mark,
     * and {@link ResultSourceAuthorization#permitsHumanStanding}.
     *
     * <p>A row with current proof must be verifiable under the practice version current at {@code at}. A row
     * recorded before the current result model (evidence version 0) has no proof to verify: it counts only as an
     * earlier review, and the reader marks what it reads as such. Such a row may name no practice version, since the
     * earlier model did not always record one; the reader then says its criteria are unknown. A version it does name
     * must be one of this workspace's versions of the same practice. Nothing admitted here becomes quotable; quotes
     * keep {@link #permitsAt}.
     */
    public Set<UUID> permitsStandingAt(long workspaceId, Collection<ResultFacts> selected, Instant at) {
        var owned = selected.stream()
                .filter(r -> r.workspaceId() == workspaceId && !r.recordedAt().isAfter(at))
                .toList();
        if (owned.isEmpty()) return Set.of();
        var pinned = pinnedVersions(workspaceId, owned);
        var current =
                versions
                        .findAt(
                                workspaceId,
                                owned.stream()
                                        .map(ResultFacts::practiceId)
                                        .distinct()
                                        .toList(),
                                at)
                        .stream()
                        .collect(Collectors.toMap(
                                v -> Objects.requireNonNull(v.getPractice().getId()), Function.identity()));
        var incorrect = incorrectAt(workspaceId, owned, at);
        return sources.permitsHumanStanding(
                workspaceId,
                owned.stream()
                        .filter(r -> !incorrect.contains(r.id()) && current.containsKey(r.practiceId()))
                        .filter(r -> {
                            var version = r.practiceVersionId() == null ? null : pinned.get(r.practiceVersionId());
                            if (r.migratedHistory()) {
                                return r.practiceVersionId() == null
                                        || (version != null
                                                && Objects.equals(
                                                        version.getPractice().getId(), r.practiceId()));
                            }
                            var currentVersion = current.get(r.practiceId());
                            return currentVersion != null
                                    && verifiable(r, version)
                                    && Objects.requireNonNull(version)
                                            .getReviewDigest()
                                            .equals(currentVersion.getReviewDigest());
                        })
                        .toList());
    }

    /**
     * Runs {@code read} as one read of the workspace, as {@link ResultSourceAuthorization#asOneRead} and
     * {@link ResultFreshness#asOneRead} describe.
     */
    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        return sources.asOneRead(workspaceId, () -> freshness.asOneRead(workspaceId, read));
    }

    private Set<UUID> incorrectAt(long workspaceId, List<ResultFacts> owned, Instant at) {
        return marks
                .findByWorkspaceIdAndResultIdIn(
                        workspaceId, owned.stream().map(ResultFacts::id).toList())
                .stream()
                .filter(m -> m.getKind() == ResultMarkKind.INCORRECT
                        && !m.getCreatedAt().isAfter(at)
                        && (m.getRemovedAt() == null || m.getRemovedAt().isAfter(at)))
                .map(m -> m.getResultId())
                .collect(Collectors.toSet());
    }

    public Set<UUID> permitsAll(long workspaceId, Collection<ResultFacts> results, SourceUsePurpose purpose) {
        return evidenceAuthorization.permitsAll(workspaceId, eligible(workspaceId, results), purpose);
    }

    /** A new delivery verifies each result's full citations, so it keeps the full records that the policy admits. */
    public Set<UUID> permitsForNewDelivery(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        var admitted = eligible(workspaceId, results.stream().map(Result::facts).toList()).stream()
                .map(ResultFacts::id)
                .collect(Collectors.toSet());
        return evidenceAuthorization.permitsForNewDelivery(
                workspaceId,
                results.stream()
                        .filter(result -> admitted.contains(result.getId()))
                        .toList(),
                purpose);
    }

    public Set<UUID> permitsShown(long workspaceId, Collection<ResultFacts> results, SourceUsePurpose purpose) {
        List<ResultFacts> owned = results.stream()
                .filter(result -> result.workspaceId() == workspaceId)
                .toList();
        Map<Long, PracticeVersion> pinned = pinnedVersions(workspaceId, owned);
        List<ResultFacts> verifiable = owned.stream()
                .filter(result -> result.migratedHistory()
                        || (result.practiceVersionId() != null && pinned.containsKey(result.practiceVersionId())))
                .toList();
        return evidenceAuthorization.permitsAll(workspaceId, withoutIncorrect(workspaceId, verifiable), purpose);
    }

    public Set<UUID> permitsHistory(long workspaceId, Collection<ResultFacts> results, SourceUsePurpose purpose) {
        return evidenceAuthorization.permitsAll(
                workspaceId,
                results.stream()
                        .filter(result -> result.workspaceId() == workspaceId)
                        .toList(),
                purpose);
    }

    /** The result's freshness: its sources' freshness, unless the result or its practice says more. */
    public static Freshness freshness(
            ResultFacts result, @Nullable PracticeVersion pinned, PracticeVersion current, Freshness source) {
        if (result.migratedHistory()) return Freshness.recordMissing();
        if (result.endedAt() != null) return Freshness.stale(source.capturedAt());
        if (!verifiable(result, pinned)) return Freshness.recordMissing();
        Objects.requireNonNull(pinned);
        return pinned.getReviewDigest().equals(current.getReviewDigest())
                ? source
                : Freshness.stale(source.capturedAt());
    }

    private static boolean verifiable(ResultFacts result, @Nullable PracticeVersion pinned) {
        return pinned != null && result.carriesCapturedProof();
    }

    public Set<UUID> permitsAt(long workspaceId, Collection<ResultFacts> rows, SourceUsePurpose purpose, Instant at) {
        var owned = rows.stream()
                .filter(r -> r.workspaceId() == workspaceId
                        && !r.recordedAt().isAfter(at)
                        && (r.endedAt() == null || r.endedAt().isAfter(at)))
                .toList();
        var pinned = pinnedVersions(workspaceId, owned);
        var current =
                versions
                        .findAt(
                                workspaceId,
                                owned.stream()
                                        .map(ResultFacts::practiceId)
                                        .distinct()
                                        .toList(),
                                at)
                        .stream()
                        .collect(Collectors.toMap(
                                v -> Objects.requireNonNull(v.getPractice().getId()), Function.identity()));
        var incorrect =
                marks
                        .findByWorkspaceIdAndResultIdIn(
                                workspaceId, owned.stream().map(ResultFacts::id).toList())
                        .stream()
                        .filter(m -> m.getKind() == ResultMarkKind.INCORRECT
                                && !m.getCreatedAt().isAfter(at)
                                && (m.getRemovedAt() == null || m.getRemovedAt().isAfter(at)))
                        .map(m -> m.getResultId())
                        .collect(Collectors.toSet());
        return evidenceAuthorization.permitsAll(
                workspaceId,
                owned.stream()
                        .filter(r -> !incorrect.contains(r.id())
                                && current.containsKey(r.practiceId())
                                && verifiable(r, pinned.get(r.practiceVersionId()))
                                && Objects.requireNonNull(pinned.get(r.practiceVersionId()))
                                        .getReviewDigest()
                                        .equals(current.get(r.practiceId()).getReviewDigest()))
                        .toList(),
                purpose);
    }

    private List<ResultFacts> eligible(long workspaceId, Collection<ResultFacts> results) {
        List<ResultFacts> owned = results.stream()
                .filter(result -> result.workspaceId() == workspaceId)
                .toList();
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<Long, PracticeVersion> pinned = pinnedVersions(workspaceId, owned);
        Map<Long, Practice> current = practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        owned.stream().map(ResultFacts::practiceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(practice -> Objects.requireNonNull(practice.getId()), Function.identity()));
        var sourceStates = freshness.currentness(
                workspaceId, owned.stream().map(ResultFreshness.Claim::of).toList());
        List<ResultFacts> eligible = owned.stream()
                .filter(result -> {
                    Practice practice = current.get(result.practiceId());
                    // The sources are checked first, so the policy checks only the result and its practice.
                    return practice != null
                            && sourceStates.get(result.id()) == Currentness.CURRENT
                            && freshness(
                                                    result,
                                                    pinned.get(result.practiceVersionId()),
                                                    practice.getCurrentVersion(),
                                                    Freshness.current(null))
                                            .currentness()
                                    == Currentness.CURRENT;
                })
                .toList();
        return withoutIncorrect(workspaceId, eligible);
    }

    private Map<Long, PracticeVersion> pinnedVersions(long workspaceId, Collection<ResultFacts> results) {
        Set<Long> ids = results.stream()
                .map(ResultFacts::practiceVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return versions.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
                .collect(Collectors.toMap(version -> Objects.requireNonNull(version.getId()), Function.identity()));
    }

    private List<ResultFacts> withoutIncorrect(long workspaceId, List<ResultFacts> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        Set<UUID> incorrect = marks
                .findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(
                        workspaceId, results.stream().map(ResultFacts::id).toList())
                .stream()
                .filter(mark -> mark.getKind() == ResultMarkKind.INCORRECT)
                .map(mark -> mark.getResultId())
                .collect(Collectors.toSet());
        return results.stream()
                .filter(result -> !incorrect.contains(result.id()))
                .toList();
    }
}
